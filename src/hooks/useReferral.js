/**
 * useReferral.js — loads referral stats from backend with local fallback
 */

import { useState, useEffect, useCallback } from 'react';
import { getStoredUser } from '../utils/storage';
import { getReferralStatus } from '../services/api';

const GOAL = 10;

function localFallback(phone) {
  const code = `PC${phone.slice(-4)}`;
  return {
    referralCode: code,
    totalReferrals: 0,
    paidReferrals: 0,
    referralRewardsEarned: 0,
    goal: GOAL,
    progressTowardNext: 0,
    rewardDescription: '1 month free when 10 referrals pay for at least 1 month',
    referredByPhone: null,
    isLocal: true,
  };
}

export function useReferral() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const user = await getStoredUser();
      if (!user?.phone) {
        setData(null);
        return;
      }
      try {
        const remote = await getReferralStatus(user.phone);
        setData({ ...remote, isLocal: false });
      } catch {
        setData(localFallback(user.phone));
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const paid = data?.paidReferrals ?? 0;
  const goal = data?.goal ?? GOAL;
  const progress = data?.progressTowardNext ?? paid % goal;
  const remaining = Math.max(0, goal - progress);

  return {
    data,
    loading,
    error,
    refresh,
    referralCode: data?.referralCode ?? '',
    totalReferrals: data?.totalReferrals ?? 0,
    paidReferrals: paid,
    referralRewardsEarned: data?.referralRewardsEarned ?? 0,
    goal,
    progress,
    remaining,
    rewardEarned: (data?.referralRewardsEarned ?? 0) > 0,
  };
}
