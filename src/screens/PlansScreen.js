import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  StatusBar, ScrollView, ActivityIndicator, Alert, Linking, Image,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import AppHeader, { SectionTitle } from '../components/AppHeader';
import { getStoredUser } from '../utils/storage';
import { getPaymentContact, getSubscriptionPlans } from '../services/api';
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
  const [openingTelegram, setOpeningTelegram] = useState(false);
  const [paymentContact, setPaymentContact] = useState(null);
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
    getPaymentContact()
      .then(setPaymentContact)
      .catch(() => setPaymentContact(null));
  }, [loadPlans]);

  const handlePay = async () => {
    if (!plan) return;
    const user = await getStoredUser();
    if (!user?.phone) {
      Alert.alert('Login Required', 'Please enter your phone number first.');
      navigation.replace('Login');
      return;
    }

    const telegramUrl = paymentContact?.telegramUrl;
    if (!telegramUrl) {
      Alert.alert(
        'Payment contact unavailable',
        'The Telegram payment link has not been configured yet. Please try again later.'
      );
      return;
    }

    setOpeningTelegram(true);
    try {
      const supported = await Linking.canOpenURL(telegramUrl);
      if (!supported) throw new Error('Telegram link cannot be opened on this device.');
      await Linking.openURL(telegramUrl);
    } catch (err) {
      Alert.alert('Unable to open Telegram', err.message || 'Please try again later.');
    } finally {
      setOpeningTelegram(false);
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
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <AppHeader
        eyebrow="MEMBERSHIP"
        title="Pick a plan"
        subtitle="Pay on Telegram. Your access starts after confirmation."
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <SectionTitle label="PLANS" title="Select a duration" />
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

        <SectionTitle label="YOUR SELECTION" title="Plan summary" />
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
            { backgroundColor: plan?.accent || colors.purple },
            openingTelegram && { opacity: 0.7 },
          ]}
          onPress={handlePay}
          disabled={openingTelegram || !plan}
          activeOpacity={0.85}
        >
          {openingTelegram ? (
            <ActivityIndicator color={colors.background} size="large" />
          ) : (
            <Text style={styles.payBtnText}>Continue on Telegram — {plan?.price}</Text>
          )}
        </TouchableOpacity>

        <View style={styles.telegramNote}>
          <Text style={styles.telegramNoteTitle}>Payment assistance on Telegram</Text>
          <Text style={styles.telegramNoteText}>
            Send your mobile number and selected plan to the payment contact. Your plan will be activated after confirmation.
          </Text>
        </View>

        <TouchableOpacity onPress={() => replaceRoot(navigation, 'Main')} style={styles.skipBtn}>
          <Text style={styles.skipText}>Skip for now →</Text>
        </TouchableOpacity>

        {!!paymentContact?.imageUrl && (
          <View style={styles.paymentImageCard}>
            <Image
              source={{ uri: paymentContact.imageUrl }}
              style={styles.paymentImage}
              resizeMode="contain"
              accessibilityLabel="Payment information"
            />
          </View>
        )}
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
  telegramNote: {
    backgroundColor: colors.surfaceCard, borderRadius: 22, padding: 16, gap: 5,
  },
  telegramNoteTitle: { color: colors.purpleBright, fontSize: 14, fontWeight: '800' },
  telegramNoteText: { color: colors.icyDim, fontSize: 12, lineHeight: 18 },
  skipBtn: { alignItems: 'center', paddingVertical: 10 },
  skipText: { color: colors.icyDim, fontSize: 14, fontWeight: '600' },
  paymentImageCard: {
    backgroundColor: colors.surfaceCard, borderRadius: 22, padding: 10, overflow: 'hidden',
  },
  paymentImage: { width: '100%', height: 280, borderRadius: 16 },
});
