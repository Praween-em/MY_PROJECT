/**
 * user.js — PostgreSQL User model
 */

const { query, withTransaction } = require('../config/db');
const { mapUser } = require('./mapUser');
const { getPlanById } = require('./plans');
const {
  generateReferralCode,
  isQualifyingPlan,
  extendSubscriptionEnd,
  checkRewardMilestone,
  REWARD_DAYS,
} = require('../services/referral');

async function getUserByPhone(phone) {
  const { rows } = await query('SELECT * FROM users WHERE phone = $1', [phone]);
  return mapUser(rows[0]);
}

async function getUserByReferralCode(code) {
  const normalized = String(code || '').toUpperCase();
  const { rows } = await query('SELECT * FROM users WHERE referral_code = $1', [normalized]);
  return mapUser(rows[0]);
}

async function ensureUser(phone) {
  let user = await getUserByPhone(phone);
  if (user) {
    if (!user.referralCode) {
      const code = generateReferralCode(phone);
      const { rows } = await query(
        `UPDATE users SET referral_code = $1, updated_at = NOW() WHERE phone = $2 RETURNING *`,
        [code, phone]
      );
      return mapUser(rows[0]);
    }
    return user;
  }

  const referralCode = generateReferralCode(phone);
  try {
    const { rows } = await query(
      `INSERT INTO users (phone, referral_code)
       VALUES ($1, $2)
       RETURNING *`,
      [phone, referralCode]
    );
    return mapUser(rows[0]);
  } catch (err) {
    // Race: another request created the user
    if (err.code === '23505') return getUserByPhone(phone);
    throw err;
  }
}

async function applyReferralCode(phone, code) {
  const user = await ensureUser(phone);
  if (user.referredByPhone) {
    throw new Error('Referral code already applied');
  }
  if (!code || code.toUpperCase() === user.referralCode) {
    throw new Error('Invalid referral code');
  }

  const referrer = await getUserByReferralCode(code);
  if (!referrer) throw new Error('Referral code not found');

  await withTransaction(async (client) => {
    await client.query(
      `UPDATE users SET referred_by_phone = $1, updated_at = NOW() WHERE phone = $2`,
      [referrer.phone, phone]
    );
    await client.query(
      `UPDATE users
       SET total_referrals = total_referrals + 1, updated_at = NOW()
       WHERE phone = $1`,
      [referrer.phone]
    );
  });

  return referrer.phone;
}

async function updateSubscription(phone, {
  subscriptionStart,
  subscriptionEnd,
  planType,
  razorpayOrderId,
  razorpayPaymentId,
}) {
  await query(
    `UPDATE users SET
       subscription_start = $1,
       subscription_end = $2,
       plan_type = $3,
       razorpay_order_id = $4,
       razorpay_payment_id = $5,
       updated_at = NOW()
     WHERE phone = $6`,
    [subscriptionStart, subscriptionEnd, planType, razorpayOrderId, razorpayPaymentId, phone]
  );
}

async function computeSubscriptionEnd(planType, fromDate = new Date()) {
  const plan = await getPlanById(planType);
  const days = plan?.durationDays ?? (planType === 'quarterly' ? 90 : 30);
  const d = new Date(fromDate);
  d.setDate(d.getDate() + days);
  return d.toISOString();
}

async function processReferralOnPayment(phone, planType) {
  if (!isQualifyingPlan(planType)) return null;

  const user = await getUserByPhone(phone);
  if (!user?.referredByPhone || user.referralPaymentCounted) return null;

  const referrerPhone = user.referredByPhone;
  const referrer = await getUserByPhone(referrerPhone);
  if (!referrer) return null;

  const prevPaid = referrer.paidReferrals || 0;
  const newPaid = prevPaid + 1;
  const { grantReward, rewardMonths } = checkRewardMilestone(prevPaid, newPaid);

  await query(
    `UPDATE users SET referral_payment_counted = TRUE, updated_at = NOW() WHERE phone = $1`,
    [phone]
  );

  let rewardEnd = null;
  if (grantReward) {
    const days = REWARD_DAYS * rewardMonths;
    rewardEnd = extendSubscriptionEnd(referrer.subscriptionEnd, days);
    await query(
      `UPDATE users SET
         paid_referrals = $1,
         referral_rewards_earned = referral_rewards_earned + $2,
         subscription_end = $3,
         updated_at = NOW()
       WHERE phone = $4`,
      [newPaid, rewardMonths, rewardEnd, referrerPhone]
    );
  } else {
    await query(
      `UPDATE users SET paid_referrals = $1, updated_at = NOW() WHERE phone = $2`,
      [newPaid, referrerPhone]
    );
  }

  return { referrerPhone, newPaid, grantReward, rewardEnd };
}

/**
 * Activate or extend subscription after payment. Idempotent per razorpayPaymentId.
 */
async function activateSubscription(phone, {
  planId,
  razorpayOrderId,
  razorpayPaymentId,
  amount = null,
}) {
  await ensureUser(phone);
  const user = await getUserByPhone(phone);

  if (user?.razorpayPaymentId === razorpayPaymentId) {
    return {
      alreadyProcessed: true,
      subscriptionEnd: user.subscriptionEnd,
      referralResult: null,
    };
  }

  // Also idempotent if payment row already exists
  const existingPay = await query(
    'SELECT 1 FROM payments WHERE payment_id = $1',
    [razorpayPaymentId]
  );
  if (existingPay.rows.length) {
    return {
      alreadyProcessed: true,
      subscriptionEnd: user.subscriptionEnd,
      referralResult: null,
    };
  }

  const baseDate =
    user?.subscriptionEnd && new Date(user.subscriptionEnd) > new Date()
      ? new Date(user.subscriptionEnd)
      : new Date();
  const now = new Date().toISOString();
  const subscriptionEnd = await computeSubscriptionEnd(planId, baseDate);

  await withTransaction(async (client) => {
    await client.query(
      `UPDATE users SET
         subscription_start = COALESCE(subscription_start, $1::timestamptz),
         subscription_end = $2,
         plan_type = $3,
         razorpay_order_id = $4,
         razorpay_payment_id = $5,
         updated_at = NOW()
       WHERE phone = $6`,
      [now, subscriptionEnd, planId, razorpayOrderId, razorpayPaymentId, phone]
    );

    const plan = await getPlanById(planId);
    const resolvedAmount = amount ?? plan?.amount ?? null;

    await client.query(
      `INSERT INTO payments (user_id, order_id, payment_id, plan_type, amount, status)
       VALUES ($1, $2, $3, $4, $5, 'captured')
       ON CONFLICT (payment_id) DO NOTHING`,
      [user.id, razorpayOrderId, razorpayPaymentId, planId, resolvedAmount]
    );
  });

  const referralResult = await processReferralOnPayment(phone, planId);
  return { alreadyProcessed: false, subscriptionEnd, referralResult };
}

async function searchUsers(q, limit = 50) {
  const term = String(q || '').trim();
  if (!term) {
    const { rows } = await query(
      `SELECT * FROM users ORDER BY created_at DESC LIMIT $1`,
      [limit]
    );
    return rows.map(mapUser);
  }
  const { rows } = await query(
    `SELECT * FROM users
     WHERE phone ILIKE $1 OR referral_code ILIKE $1
     ORDER BY created_at DESC
     LIMIT $2`,
    [`%${term}%`, limit]
  );
  return rows.map(mapUser);
}

async function adminUpdateUser(phone, patch) {
  const fields = [];
  const values = [];
  let i = 1;

  const map = {
    subscriptionStart: 'subscription_start',
    subscriptionEnd: 'subscription_end',
    planType: 'plan_type',
    maxDevices: 'max_devices',
    status: 'status',
  };

  for (const [key, col] of Object.entries(map)) {
    if (patch[key] !== undefined) {
      fields.push(`${col} = $${i++}`);
      values.push(patch[key]);
    }
  }

  if (!fields.length) return getUserByPhone(phone);

  fields.push('updated_at = NOW()');
  values.push(phone);

  const { rows } = await query(
    `UPDATE users SET ${fields.join(', ')} WHERE phone = $${i} RETURNING *`,
    values
  );
  return mapUser(rows[0]);
}

module.exports = {
  getUserByPhone,
  getUserByReferralCode,
  ensureUser,
  applyReferralCode,
  updateSubscription,
  activateSubscription,
  processReferralOnPayment,
  computeSubscriptionEnd,
  searchUsers,
  adminUpdateUser,
};
