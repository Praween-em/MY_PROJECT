/**
 * user.js — DynamoDB User model
 */

const { GetCommand, PutCommand, UpdateCommand, QueryCommand } = require('@aws-sdk/lib-dynamodb');
const { db, TABLE } = require('../config/dynamodb');
const {
  generateReferralCode,
  isQualifyingPlan,
  extendSubscriptionEnd,
  checkRewardMilestone,
  REWARD_DAYS,
} = require('../services/referral');

const REFERRAL_CODE_INDEX = 'referralCode-index';

async function getUserByPhone(phone) {
  const { Item } = await db.send(new GetCommand({ TableName: TABLE, Key: { phone } }));
  return Item || null;
}

async function getUserByReferralCode(code) {
  const normalized = code.toUpperCase();
  const { Items } = await db.send(new QueryCommand({
    TableName: TABLE,
    IndexName: REFERRAL_CODE_INDEX,
    KeyConditionExpression: 'referralCode = :code',
    ExpressionAttributeValues: { ':code': normalized },
    Limit: 1,
  }));
  return Items?.[0] || null;
}

async function putUser(user) {
  await db.send(new PutCommand({
    TableName: TABLE,
    Item: { ...user, updatedAt: new Date().toISOString() },
  }));
}

async function ensureUser(phone) {
  let user = await getUserByPhone(phone);
  if (!user) {
    user = {
      phone,
      referralCode: generateReferralCode(phone),
      totalReferrals: 0,
      paidReferrals: 0,
      referralRewardsEarned: 0,
      createdAt: new Date().toISOString(),
    };
    await putUser(user);
  } else if (!user.referralCode) {
    user.referralCode = generateReferralCode(phone);
    await putUser(user);
  }
  return user;
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

  await db.send(new UpdateCommand({
    TableName: TABLE,
    Key: { phone },
    UpdateExpression: 'SET referredByPhone = :ref, updatedAt = :ua',
    ExpressionAttributeValues: {
      ':ref': referrer.phone,
      ':ua': new Date().toISOString(),
    },
  }));

  await db.send(new UpdateCommand({
    TableName: TABLE,
    Key: { phone: referrer.phone },
    UpdateExpression: 'SET totalReferrals = if_not_exists(totalReferrals, :zero) + :one, updatedAt = :ua',
    ExpressionAttributeValues: {
      ':zero': 0,
      ':one': 1,
      ':ua': new Date().toISOString(),
    },
  }));

  return referrer.phone;
}

async function updateSubscription(phone, {
  subscriptionStart,
  subscriptionEnd,
  planType,
  razorpayOrderId,
  razorpayPaymentId,
}) {
  await db.send(new UpdateCommand({
    TableName: TABLE,
    Key: { phone },
    UpdateExpression:
      'SET subscriptionStart = :ss, subscriptionEnd = :se, planType = :pt, ' +
      'razorpayOrderId = :oid, razorpayPaymentId = :pid, updatedAt = :ua',
    ExpressionAttributeValues: {
      ':ss': subscriptionStart,
      ':se': subscriptionEnd,
      ':pt': planType,
      ':oid': razorpayOrderId,
      ':pid': razorpayPaymentId,
      ':ua': new Date().toISOString(),
    },
  }));
}

/**
 * Activate or extend subscription after payment. Idempotent per razorpayPaymentId.
 */
async function activateSubscription(phone, {
  planId,
  razorpayOrderId,
  razorpayPaymentId,
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

  const baseDate =
    user?.subscriptionEnd && new Date(user.subscriptionEnd) > new Date()
      ? new Date(user.subscriptionEnd)
      : new Date();
  const now = new Date().toISOString();
  const subscriptionEnd = computeSubscriptionEnd(planId, baseDate);

  await updateSubscription(phone, {
    subscriptionStart: user?.subscriptionStart || now,
    subscriptionEnd,
    planType: planId,
    razorpayOrderId,
    razorpayPaymentId,
  });

  const referralResult = await processReferralOnPayment(phone, planId);

  return { alreadyProcessed: false, subscriptionEnd, referralResult };
}

/**
 * After a successful payment, credit the referrer if this is the user's first
 * qualifying (≥1 month) payment.
 */
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

  await db.send(new UpdateCommand({
    TableName: TABLE,
    Key: { phone },
    UpdateExpression: 'SET referralPaymentCounted = :true, updatedAt = :ua',
    ExpressionAttributeValues: { ':true': true, ':ua': new Date().toISOString() },
  }));

  let rewardEnd = null;
  if (grantReward) {
    const days = REWARD_DAYS * rewardMonths;
    rewardEnd = extendSubscriptionEnd(referrer.subscriptionEnd, days);
    await db.send(new UpdateCommand({
      TableName: TABLE,
      Key: { phone: referrerPhone },
      UpdateExpression:
        'SET paidReferrals = :np, referralRewardsEarned = if_not_exists(referralRewardsEarned, :zero) + :rm, ' +
        'subscriptionEnd = :se, updatedAt = :ua',
      ExpressionAttributeValues: {
        ':np': newPaid,
        ':zero': 0,
        ':rm': rewardMonths,
        ':se': rewardEnd,
        ':ua': new Date().toISOString(),
      },
    }));
  } else {
    await db.send(new UpdateCommand({
      TableName: TABLE,
      Key: { phone: referrerPhone },
      UpdateExpression: 'SET paidReferrals = :np, updatedAt = :ua',
      ExpressionAttributeValues: {
        ':np': newPaid,
        ':ua': new Date().toISOString(),
      },
    }));
  }

  return { referrerPhone, newPaid, grantReward, rewardEnd };
}

function computeSubscriptionEnd(planType, fromDate = new Date()) {
  const days = { monthly: 30, quarterly: 90 };
  const d = new Date(fromDate);
  d.setDate(d.getDate() + (days[planType] || 30));
  return d.toISOString();
}

module.exports = {
  getUserByPhone,
  getUserByReferralCode,
  putUser,
  ensureUser,
  applyReferralCode,
  updateSubscription,
  activateSubscription,
  processReferralOnPayment,
  computeSubscriptionEnd,
};
