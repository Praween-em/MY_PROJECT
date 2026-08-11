import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  StatusBar, ScrollView, ActivityIndicator, Alert,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { getStoredUser, saveUser } from '../utils/storage';
import { openCheckout, isPaymentCancelled } from '../services/payment';
import { getSubscriptionStatus, getSubscriptionPlans } from '../services/api';
import { replaceRoot } from '../navigation/rootNavigation';

const PLAN_STYLE = {
  trial: {
    popular: true,
    badge: 'TRY FIRST',
    accent: colors.onGreen,
    accentGlow: 'rgba(34, 197, 94, 0.12)',
    features: ['Full auto-accept for 3 days', 'Try before you commit', 'Same features as paid plans', 'Upgrade anytime'],
  },
  monthly: {
    popular: false,
    badge: 'MOST POPULAR',
    accent: colors.purple,
    accentGlow: colors.purpleGlow,
    features: ['Auto-accept rides', 'Nuclear mode (0ms)', 'All supported apps', 'Priority support'],
  },
  quarterly: {
    popular: false,
    accent: colors.blue,
    accentGlow: colors.blueGlow,
    features: ['Auto-accept rides', 'All supported apps', 'Priority support', 'Best value'],
  },
};

const DEFAULT_FEATURES = ['Auto-accept rides', 'All supported apps', 'Priority support'];

function mergePlan(apiPlan) {
  const style = PLAN_STYLE[apiPlan.id] || {};
  return {
    id: apiPlan.id,
    label: apiPlan.label,
    price: apiPlan.priceDisplay,
    duration: apiPlan.duration,
    perDay: apiPlan.perDay,
    description: apiPlan.description,
    amount: apiPlan.amount,
    popular: style.popular ?? false,
    badge: style.badge,
    accent: style.accent ?? colors.purple,
    accentGlow: style.accentGlow ?? colors.purpleGlow,
    features: style.features ?? DEFAULT_FEATURES,
  };
}

export default function PlansScreen({ navigation }) {
  const [plans, setPlans] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [paying, setPaying] = useState(false);
  const plan = plans.find(p => p.id === selected);

  const loadPlans = useCallback(async () => {
    setLoading(true);
    try {
      const { plans: remote } = await getSubscriptionPlans();
      const merged = (remote || []).map(mergePlan);
      setPlans(merged);
      setSelected((prev) => {
        if (prev && merged.some((p) => p.id === prev)) return prev;
        return merged[0]?.id ?? null;
      });
    } catch (err) {
      Alert.alert('Could not load plans', err.message || 'Check your connection and try again.');
      setPlans([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadPlans();
  }, [loadPlans]);

  const handlePay = async () => {
    if (!plan) return;
    const user = await getStoredUser();
    if (!user?.phone) {
      Alert.alert('Login Required', 'Please enter your phone number first.');
      navigation.replace('Login');
      return;
    }

    setPaying(true);
    try {
      const result = await openCheckout(plan.id, user.phone, {
        description: plan.description,
        label: plan.label,
      });
      if (!result?.subscriptionEnd && !result?.success) {
        throw new Error('Payment verified but subscription was not activated. Contact support.');
      }
      let remote = null;
      try {
        remote = await getSubscriptionStatus(user.phone);
      } catch {
        // verify already succeeded
      }
      const subscriptionEnd = remote?.subscriptionEnd || result.subscriptionEnd;
      const isActive = remote?.active ?? (subscriptionEnd && new Date(subscriptionEnd) > new Date());
      if (!isActive && !subscriptionEnd) {
        throw new Error(
          'Payment received but subscription not active yet. Pull to refresh or contact support.'
        );
      }
      await saveUser({
        ...user,
        active: isActive,
        planType: remote?.planType || plan.id,
        subscriptionEnd,
        subscriptionStart: remote?.subscriptionStart || new Date().toISOString(),
      });
      navigation.replace('PermissionsSetup');
    } catch (err) {
      if (isPaymentCancelled(err)) return;
      const msg = err.message || 'Could not complete payment.';
      Alert.alert(
        'Payment Issue',
        msg + (msg.includes('signature') ? '\n\nCheck Railway: RAZORPAY_KEY_SECRET must match your live Key ID.' : '')
      );
    } finally {
      setPaying(false);
    }
  };

  if (loading) {
    return (
      <Screen>
        <View style={styles.center}>
          <ActivityIndicator size="large" color={colors.purple} />
          <Text style={styles.loadingText}>Loading plans…</Text>
        </View>
      </Screen>
    );
  }

  if (!plans.length) {
    return (
      <Screen>
        <View style={styles.center}>
          <Text style={styles.loadingText}>No plans available right now.</Text>
          <TouchableOpacity onPress={loadPlans} style={styles.retryBtn}>
            <Text style={styles.retryText}>Retry</Text>
          </TouchableOpacity>
        </View>
      </Screen>
    );
  }

  return (
    <Screen>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <View style={styles.header}>
        <Text style={styles.headerTitle}>Choose a Plan</Text>
        <Text style={styles.headerSub}>Start with a 3-day trial or pick a longer plan</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        {plans.map(p => {
          const isSelected = selected === p.id;
          return (
            <TouchableOpacity
              key={p.id}
              style={[
                styles.card,
                isSelected && { borderColor: p.accent, backgroundColor: p.accentGlow, borderWidth: 2.5 },
              ]}
              onPress={() => setSelected(p.id)}
              activeOpacity={0.85}
            >
              {p.popular && (
                <View style={[styles.popularBadge, { backgroundColor: p.accent }]}>
                  <Text style={styles.popularText}>{p.badge || 'MOST POPULAR'}</Text>
                </View>
              )}

              <View style={styles.cardHeader}>
                <View>
                  <Text style={styles.planLabel}>{p.label}</Text>
                  <Text style={styles.planDuration}>{p.duration}</Text>
                </View>
                <View style={styles.priceBlock}>
                  <Text style={[styles.planPrice, { color: p.accent }]}>{p.price}</Text>
                  <Text style={styles.planPerDay}>{p.perDay}</Text>
                </View>
              </View>

              <View style={styles.divider} />

              {p.features.map((f, i) => (
                <View key={i} style={styles.featureRow}>
                  <Text style={[styles.featureTick, { color: p.accent }]}>✓</Text>
                  <Text style={styles.featureText}>{f}</Text>
                </View>
              ))}

              <View style={styles.radioRow}>
                <View style={[styles.radio, isSelected && { borderColor: p.accent, width: 28, height: 28, borderRadius: 14 }]}>
                  {isSelected && <View style={[styles.radioDot, { backgroundColor: p.accent, width: 14, height: 14, borderRadius: 7 }]} />}
                </View>
                <Text style={[styles.radioLabel, isSelected && { color: p.accent, fontSize: 15 }]}>
                  {isSelected ? 'Selected' : 'Select plan'}
                </Text>
              </View>
            </TouchableOpacity>
          );
        })}

        <View style={styles.summary}>
          <Text style={styles.summaryTitle}>Order Summary</Text>
          <View style={styles.summaryRow}>
            <Text style={styles.summaryLabel}>{plan?.label}</Text>
            <Text style={styles.summaryValue}>{plan?.price}</Text>
          </View>
          <View style={[styles.summaryRow, styles.summaryTotalRow]}>
            <Text style={styles.summaryTotalLabel}>Total</Text>
            <Text style={[styles.summaryTotalValue, { color: plan?.accent }]}>{plan?.price}</Text>
          </View>
        </View>

        <TouchableOpacity
          style={[
            styles.payBtn,
            { backgroundColor: plan?.accent },
            paying && { opacity: 0.7 },
          ]}
          onPress={handlePay}
          disabled={paying || !plan}
          activeOpacity={0.85}
        >
          {paying ? (
            <ActivityIndicator color={colors.white} size="large" />
          ) : (
            <Text style={styles.payBtnText}>Pay {plan?.price} — Activate Now</Text>
          )}
        </TouchableOpacity>

        <TouchableOpacity onPress={() => replaceRoot(navigation, 'Main')} style={styles.skipBtn}>
          <Text style={styles.skipText}>Skip for now →</Text>
        </TouchableOpacity>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  loadingText: { color: colors.icyDim, fontSize: 15, fontWeight: '600' },
  retryBtn: { paddingHorizontal: 20, paddingVertical: 10, backgroundColor: colors.purple, borderRadius: 8 },
  retryText: { color: colors.white, fontWeight: '700' },
  header: {
    paddingHorizontal: 24, paddingTop: 24, paddingBottom: 16,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  headerTitle: { fontSize: 26, fontWeight: '900', color: colors.icy },
  headerSub: { fontSize: 13, color: colors.icyDim, marginTop: 4 },
  scroll: { padding: 20, gap: 16, paddingBottom: 32 },

  card: {
    backgroundColor: colors.surface, borderRadius: 14, padding: 22,
    borderWidth: 1, borderColor: colors.border, gap: 12,
  },
  popularBadge: { alignSelf: 'flex-start', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 4 },
  popularText: { color: colors.white, fontSize: 10, fontWeight: '900', letterSpacing: 1 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  planLabel: { fontSize: 22, fontWeight: '900', color: colors.icy },
  planDuration: { fontSize: 13, color: colors.icyDim, marginTop: 4 },
  priceBlock: { alignItems: 'flex-end' },
  planPrice: { fontSize: 30, fontWeight: '900' },
  planPerDay: { fontSize: 12, color: colors.icyMuted, marginTop: 2 },
  divider: { height: 1, backgroundColor: colors.border },
  featureRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  featureTick: { fontSize: 15, fontWeight: '900' },
  featureText: { fontSize: 14, color: colors.icyDim },
  radioRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 6 },
  radio: {
    width: 24, height: 24, borderRadius: 12,
    borderWidth: 2, borderColor: colors.icyMuted,
    alignItems: 'center', justifyContent: 'center',
  },
  radioDot: { width: 12, height: 12, borderRadius: 6 },
  radioLabel: { fontSize: 14, color: colors.icyMuted, fontWeight: '700' },

  summary: {
    backgroundColor: colors.surface, borderRadius: 18, padding: 20,
    borderWidth: 1, borderColor: colors.border, gap: 10,
  },
  summaryTitle: { fontSize: 15, fontWeight: '800', color: colors.icy },
  summaryRow: { flexDirection: 'row', justifyContent: 'space-between' },
  summaryLabel: { color: colors.icyDim, fontSize: 15 },
  summaryValue: { color: colors.icy, fontSize: 15, fontWeight: '700' },
  summaryTotalRow: { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 12, marginTop: 4 },
  summaryTotalLabel: { color: colors.icy, fontSize: 17, fontWeight: '800' },
  summaryTotalValue: { fontSize: 24, fontWeight: '900' },

  payBtn: {
    borderRadius: 12, paddingVertical: 20, alignItems: 'center',
    minHeight: 64,
  },
  payBtnText: { color: colors.white, fontSize: 17, fontWeight: '700', letterSpacing: 0.3 },
  skipBtn: { alignItems: 'center', paddingVertical: 10 },
  skipText: { color: colors.icyDim, fontSize: 14, fontWeight: '600' },
});
