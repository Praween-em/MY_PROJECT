/**
 * useSubscription.js
 * Manages subscription state: load from storage, refresh from backend,
 * expose isActive flag and expiry details.
 */

import { useState, useEffect, useCallback } from 'react';
import { getStoredUser, saveUser } from '../utils/storage';
import { getSubscriptionStatus } from '../services/api';

export function useSubscription() {
  const [subscription, setSubscription] = useState(null); // null = loading
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const user = await getStoredUser();
      if (!user?.phone) {
        setSubscription({ active: false });
        return;
      }

      // Check local expiry first for instant response
      if (user.subscriptionEnd) {
        const isLocallyActive = new Date(user.subscriptionEnd) > new Date();
        setSubscription({
          active: isLocallyActive,
          subscriptionStart: user.subscriptionStart,
          subscriptionEnd: user.subscriptionEnd,
          planType: user.planType,
          phone: user.phone,
        });
      }

      // Then verify with backend (keep local state if network fails)
      try {
        const remote = await getSubscriptionStatus(user.phone);
        const updated = {
          active: remote.active,
          subscriptionStart: remote.subscriptionStart,
          subscriptionEnd: remote.subscriptionEnd,
          planType: remote.planType,
          phone: user.phone,
        };
        setSubscription(updated);
        await saveUser({ ...user, ...updated });
      } catch {
        if (!user.subscriptionEnd) {
          setSubscription({ active: false, phone: user.phone });
        }
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

  const daysRemaining = subscription?.subscriptionEnd
    ? Math.max(
        0,
        Math.ceil(
          (new Date(subscription.subscriptionEnd) - new Date()) / (1000 * 60 * 60 * 24)
        )
      )
    : 0;

  return {
    subscription,
    loading,
    error,
    refresh,
    isActive: subscription?.active ?? false,
    daysRemaining,
    subscriptionEnd: subscription?.subscriptionEnd ?? null,
    planType: subscription?.planType ?? null,
    subscriptionStart: subscription?.subscriptionStart ?? null,
  };
}
