import React, { useState, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  StatusBar, ScrollView, ActivityIndicator, Alert, Linking, Image,
  RefreshControl,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import AppHeader, { SectionTitle } from '../components/AppHeader';
import { getStoredUser } from '../utils/storage';
import {
  getPaymentContact,
  getSubscriptionPlans,
  getPendingSubscriptionOrder,
  placeSubscriptionOrder,
} from '../services/api';
import { replaceRoot } from '../navigation/rootNavigation';
import {
  formatUpiAmount,
  isWhatsAppGroupInvite,
  openUpiApp,
  paymentMessage,
  whatsAppPayUrl,
} from '../services/upiPay';

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
    features: ['Auto-accept rides', 'Fastest 0ms tap', 'All supported apps', 'Priority support'],
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
  const [paymentContact, setPaymentContact] = useState(null);
  const [pendingOrder, setPendingOrder] = useState(null);
  const plan = plans.find(p => p.id === selected);

  const loadPlans = useCallback(async ({ silent = false } = {}) => {
    if (!silent) setLoading(true);
    try {
      const [{ plans: remote }, contact] = await Promise.all([
        getSubscriptionPlans(),
        getPaymentContact().catch(() => null),
      ]);
      const merged = (remote || []).map(mergePlan);
      setPlans(merged);
      setSelected((prev) => {
        if (prev && merged.some((p) => p.id === prev)) return prev;
        return merged[0]?.id ?? null;
      });
      setPaymentContact(contact);
      const user = await getStoredUser();
      if (user?.phone) {
        const pending = await getPendingSubscriptionOrder(user.phone).catch(() => null);
        setPendingOrder(pending?.order || null);
      }
    } catch (err) {
      if (!silent) {
        Alert.alert('Could not load plans', err.message || 'Check your connection and try again.');
      }
      setPlans((current) => (silent ? current : []));
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadPlans({ silent: true });
    }, [loadPlans])
  );

  const ensureOrder = async (user) => {
    if (pendingOrder?.planId === plan.id) return pendingOrder;
    const placed = await placeSubscriptionOrder(user.phone, plan.id);
    setPendingOrder(placed.order);
    return placed.order;
  };

  const payWithUpi = async () => {
    if (!plan || paying) return;
    const user = await getStoredUser();
    if (!user?.phone) {
      Alert.alert('Login Required', 'Please enter your phone number first.');
      navigation.replace('Login');
      return;
    }
    setPaying(true);
    try {
      await ensureOrder(user);
      if (!paymentContact?.upiId) {
        Alert.alert(
          'Scan the QR',
          'Open PhonePe, Paytm, or any UPI app and scan the scanner at the top.'
        );
        return;
      }
      await openUpiApp('any', {
        pa: paymentContact.upiId,
        pn: paymentContact.payeeName || 'AG Rider',
        am: formatUpiAmount(plan.amount),
        tn: `AG Rider ${plan.label} ${String(user.phone).slice(-4)}`.slice(0, 50),
      });
    } catch (err) {
      Alert.alert(
        'UPI app did not open',
        err?.message || 'Scan the QR at the top with PhonePe, Paytm, or any UPI app.'
      );
    } finally {
      setPaying(false);
    }
  };

  const handlePaidWhatsApp = async () => {
    const user = await getStoredUser();
    if (!user?.phone) {
      Alert.alert('Login Required', 'Please enter your phone number first.');
      navigation.replace('Login');
      return;
    }
    try {
      if (plan) await ensureOrder(user);
    } catch (err) {
      Alert.alert('Could not place the order', err.message || 'Please try again.');
      return;
    }
    const text = paymentMessage({
      planLabel: plan?.label || pendingOrder?.planLabel || 'plan',
      price: plan?.price || pendingOrder?.priceDisplay || '',
      phone: user.phone,
    });
    const groupUrl = paymentContact?.whatsappGroupUrl || '';
    try {
      await Linking.openURL(whatsAppPayUrl(groupUrl, text));
      if (isWhatsAppGroupInvite(groupUrl)) {
        Alert.alert(
          'Send this in the group',
          'WhatsApp opened with “I have paid”. Send that message in the payment group. Access starts after we verify it.'
        );
      }
    } catch (err) {
      if (isWhatsAppGroupInvite(groupUrl)) {
        try {
          await Linking.openURL(groupUrl);
          return;
        } catch {
          // Fall through to the alert below.
        }
      }
      Alert.alert(
        'WhatsApp did not open',
        `${text}\n\nSend that message in the payment WhatsApp group.`
      );
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
          <TouchableOpacity onPress={() => loadPlans()} style={styles.retryBtn}>
            <Text style={styles.retryText}>Retry</Text>
          </TouchableOpacity>
        </View>
      </Screen>
    );
  }

  return (
    <Screen>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <AppHeader
        eyebrow="MEMBERSHIP"
        title="Renew"
        subtitle="Scan, pay in your UPI app, then message WhatsApp."
      />

      <ScrollView
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            refreshing={false}
            onRefresh={() => loadPlans({ silent: true })}
            tintColor={colors.purpleBright}
            colors={[colors.purple]}
          />
        }
      >
        <View style={styles.checkout}>
          <Text style={styles.checkoutTitle}>1. Scan to pay {plan?.price}</Text>
          {paymentContact?.imageUrl ? (
            <Image
              key={paymentContact.imageUrl}
              source={{ uri: paymentContact.imageUrl, cache: 'reload' }}
              style={styles.paymentImage}
              resizeMode="contain"
              accessibilityLabel="Payment scanner"
            />
          ) : (
            <Text style={styles.checkoutText}>
              The payment scanner is not uploaded yet. Use Pay with UPI after a plan is selected.
            </Text>
          )}
          {!!paymentContact?.upiId && (
            <Text style={styles.upiId}>UPI ID · {paymentContact.upiId}</Text>
          )}
        </View>

        <SectionTitle label="PLANS" title="2. Choose a plan" />
        {plans.map(p => {
          const isSelected = selected === p.id;
          return (
            <TouchableOpacity
              key={p.id}
              style={[
                styles.card,
                isSelected && { backgroundColor: p.accentGlow },
              ]}
              onPress={() => setSelected(p.id)}
              activeOpacity={0.85}
            >
              <View style={styles.priceRail}>
                <Text style={[styles.planPrice, { color: isSelected ? p.accent : colors.icy }]}>{p.price}</Text>
                <Text style={styles.planPerDay}>{p.perDay}</Text>
              </View>
              <View style={styles.cardBody}>
                <View style={styles.cardHeader}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.planLabel}>{p.label}</Text>
                    <Text style={styles.planDuration}>{p.duration}</Text>
                  </View>
                  {p.popular ? (
                    <View style={[styles.popularBadge, { backgroundColor: p.accent }]}>
                      <Text style={styles.popularText}>{p.badge || 'POPULAR'}</Text>
                    </View>
                  ) : (
                    <View style={[styles.radio, isSelected && { backgroundColor: p.accent, borderColor: p.accent }]}>
                      {isSelected && <View style={styles.radioDot} />}
                    </View>
                  )}
                </View>
                <View style={styles.featureWrap}>
                  {p.features.map((f, i) => (
                    <View key={i} style={styles.featureChip}>
                      <Text style={styles.featureText}>{f}</Text>
                    </View>
                  ))}
                </View>
              </View>
            </TouchableOpacity>
          );
        })}

        {pendingOrder ? (
          <View style={styles.pendingCard}>
            <Text style={styles.pendingTitle}>Order placed. Awaiting confirmation.</Text>
            <Text style={styles.pendingText}>
              {pendingOrder.planLabel} · {pendingOrder.priceDisplay}. Auto-accept stays off until an admin confirms this order.
            </Text>
          </View>
        ) : null}

        <TouchableOpacity
          style={[
            styles.payBtn,
            { backgroundColor: plan?.accent || colors.purple },
            paying && { opacity: 0.7 },
          ]}
          onPress={payWithUpi}
          disabled={paying || !plan}
          activeOpacity={0.85}
        >
          {paying ? (
            <ActivityIndicator color={colors.background} size="large" />
          ) : (
            <Text style={styles.payBtnText}>3. Pay {plan?.price} with your UPI app</Text>
          )}
        </TouchableOpacity>
        <Text style={styles.checkoutHint}>
          Android asks which app to use — PhonePe, Paytm, Google Pay, or any other UPI app. The amount is filled in.
        </Text>

        <TouchableOpacity style={styles.whatsAppBtn} onPress={handlePaidWhatsApp}>
          <Text style={styles.whatsAppBtnText}>4. I have paid — message WhatsApp</Text>
        </TouchableOpacity>
        <Text style={styles.checkoutHint}>
          WhatsApp opens with “I have paid for {plan?.label}”. Send it in the payment group. The plan starts after that payment is verified.
        </Text>

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
  retryBtn: { paddingHorizontal: 22, paddingVertical: 12, backgroundColor: colors.purple, borderRadius: 999 },
  retryText: { color: colors.background, fontWeight: '800' },
  scroll: { paddingHorizontal: 20, paddingTop: 8, gap: 12, paddingBottom: 32 },

  card: {
    backgroundColor: colors.surfaceCard, borderRadius: 26,
    flexDirection: 'row', overflow: 'hidden',
  },
  priceRail: {
    width: 104, paddingVertical: 18, paddingHorizontal: 10,
    alignItems: 'center', justifyContent: 'center',
    backgroundColor: colors.surfaceLight,
  },
  planPrice: { fontSize: 20, fontWeight: '800', textAlign: 'center' },
  planPerDay: { fontSize: 11, color: colors.icyMuted, marginTop: 4, textAlign: 'center' },
  cardBody: { flex: 1, padding: 16, gap: 12 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 },
  planLabel: { fontSize: 18, fontWeight: '800', color: colors.icy },
  planDuration: { fontSize: 12, color: colors.icyDim, marginTop: 3 },
  popularBadge: { borderRadius: 999, paddingHorizontal: 8, paddingVertical: 4 },
  popularText: { color: colors.background, fontSize: 9, fontWeight: '800', letterSpacing: 0.6 },
  featureWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  featureChip: {
    backgroundColor: colors.surfaceLight, borderRadius: 999, paddingHorizontal: 8, paddingVertical: 5,
  },
  featureText: { fontSize: 11, color: colors.icyDim, fontWeight: '600' },
  radio: {
    width: 22, height: 22, borderRadius: 11,
    borderWidth: 2, borderColor: colors.icyMuted,
    alignItems: 'center', justifyContent: 'center',
  },
  radioDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: colors.background },

  summary: {
    backgroundColor: colors.surfaceCard, borderRadius: 24, padding: 18, gap: 10,
  },
  summaryTitle: { fontSize: 15, fontWeight: '800', color: colors.icy },
  summaryRow: { flexDirection: 'row', justifyContent: 'space-between' },
  summaryLabel: { color: colors.icyDim, fontSize: 15 },
  summaryValue: { color: colors.icy, fontSize: 15, fontWeight: '700' },
  summaryTotalRow: { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: 12, marginTop: 4 },
  summaryTotalLabel: { color: colors.icy, fontSize: 16, fontWeight: '800' },
  summaryTotalValue: { fontSize: 22, fontWeight: '800' },

  payBtn: {
    borderRadius: 999, paddingVertical: 18, alignItems: 'center',
    minHeight: 58,
  },
  payBtnText: { color: colors.background, fontSize: 16, fontWeight: '800' },
  pendingCard: {
    backgroundColor: 'rgba(34, 197, 94, 0.12)',
    borderRadius: 22,
    padding: 16,
    gap: 6,
  },
  pendingTitle: { color: colors.onGreen, fontSize: 15, fontWeight: '800' },
  pendingText: { color: colors.icyDim, fontSize: 13, lineHeight: 18 },
  telegramNote: {
    backgroundColor: colors.surfaceCard, borderRadius: 22, padding: 16, gap: 5,
  },
  telegramNoteTitle: { color: colors.purpleBright, fontSize: 14, fontWeight: '800' },
  telegramNoteText: { color: colors.icyDim, fontSize: 12, lineHeight: 18 },
  checkout: {
    backgroundColor: colors.surfaceCard, borderRadius: 24, padding: 16, gap: 12,
  },
  checkoutTitle: { color: colors.icy, fontSize: 18, fontWeight: '800' },
  checkoutText: { color: colors.icyDim, fontSize: 13, lineHeight: 18 },
  checkoutHint: { color: colors.icyMuted, fontSize: 12, lineHeight: 18 },
  upiId: { color: colors.icy, fontSize: 13, fontWeight: '700' },
  upiRow: { flexDirection: 'row', gap: 8 },
  upiBtn: {
    flex: 1, backgroundColor: colors.surfaceLight, borderRadius: 999,
    paddingVertical: 14, alignItems: 'center',
  },
  upiBtnText: { color: colors.icy, fontSize: 14, fontWeight: '800' },
  whatsAppBtn: {
    backgroundColor: '#25D366', borderRadius: 999, paddingVertical: 16, alignItems: 'center',
  },
  whatsAppBtnText: { color: '#052e16', fontSize: 15, fontWeight: '800' },
  skipBtn: { alignItems: 'center', paddingVertical: 10 },
  skipText: { color: colors.icyDim, fontSize: 14, fontWeight: '600' },
  paymentImage: { width: '100%', height: 280, borderRadius: 16, backgroundColor: '#fff' },
});
