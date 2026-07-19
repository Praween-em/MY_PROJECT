/**
 * referral.js — Referral program logic
 *
 * Rules:
 * 1. Each user gets a unique referralCode.
 * 2. New users can apply a referral code once at signup.
 * 3. When a referred user pays monthly OR quarterly (≥1 month), they count as
 *    one "paid referral" for their referrer — counted only once per user.
 * 4. When a referrer reaches 10 paid referrals, they receive 1 free month (30 days).
 *    Reward is granted once per 10 paid referrals milestone (10, 20, 30…).
 */

const REFERRAL_GOAL = 10;
const REWARD_DAYS = 30;
const QUALIFYING_PLANS = new Set(['monthly', 'quarterly']);

function generateReferralCode(phone) {
  const suffix = phone.slice(-4);
  const rand = Math.random().toString(36).slice(2, 5).toUpperCase();
  return `PC${suffix}${rand}`;
}

function isQualifyingPlan(planType) {
  return QUALIFYING_PLANS.has(planType);
}

/**
 * Extend subscriptionEnd by `days` from the later of now or current end.
 */
function extendSubscriptionEnd(currentEnd, days) {
  const base = currentEnd && new Date(currentEnd) > new Date()
    ? new Date(currentEnd)
    : new Date();
  base.setDate(base.getDate() + days);
  return base.toISOString();
}

/**
 * Pure function: compute reward after incrementing paidReferrals.
 * @returns {{ grantReward: boolean, milestone: number }}
 */
function checkRewardMilestone(previousPaid, newPaid) {
  const prevMilestone = Math.floor(previousPaid / REFERRAL_GOAL);
  const newMilestone = Math.floor(newPaid / REFERRAL_GOAL);
  return {
    grantReward: newMilestone > prevMilestone,
    milestone: newMilestone,
    rewardMonths: newMilestone - prevMilestone,
  };
}

module.exports = {
  REFERRAL_GOAL,
  REWARD_DAYS,
  QUALIFYING_PLANS,
  generateReferralCode,
  isQualifyingPlan,
  extendSubscriptionEnd,
  checkRewardMilestone,
};
