/**
 * mapUser.js — DB row → API camelCase shape (app-compatible)
 */

function mapUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    phone: row.phone,
    referralCode: row.referral_code,
    referredByPhone: row.referred_by_phone || null,
    referralPaymentCounted: !!row.referral_payment_counted,
    totalReferrals: row.total_referrals || 0,
    paidReferrals: row.paid_referrals || 0,
    referralRewardsEarned: row.referral_rewards_earned || 0,
    subscriptionStart: row.subscription_start ? new Date(row.subscription_start).toISOString() : null,
    subscriptionEnd: row.subscription_end ? new Date(row.subscription_end).toISOString() : null,
    planType: row.plan_type || null,
    razorpayOrderId: row.razorpay_order_id || null,
    razorpayPaymentId: row.razorpay_payment_id || null,
    maxDevices: row.max_devices ?? 1,
    status: row.status || 'active',
    createdAt: row.created_at ? new Date(row.created_at).toISOString() : null,
    updatedAt: row.updated_at ? new Date(row.updated_at).toISOString() : null,
  };
}

function isSubscriptionActive(user) {
  if (!user || user.status === 'blocked') return false;
  if (!user.subscriptionEnd) return false;
  return new Date(user.subscriptionEnd) > new Date();
}

module.exports = { mapUser, isSubscriptionActive };
