import React, { useState } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet,
  StatusBar, ScrollView, ActivityIndicator, Alert,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { getStoredUser, saveUser } from '../utils/storage';
import { openCheckout, isPaymentCancelled } from '../services/payment';
import { getSubscriptionStatus } from '../services/api';
import { replaceRoot } from '../navigation/rootNavigation';

const PLANS = [
  {
    id: 'monthly',
    label: '1 Month',
    price: '₹299',
    duration: '30 days',
    perDay: '₹9.9 / day',
    popular: true,
    accent: colors.purple,
    accentGlow: colors.purpleGlow,
    accentBorder: colors.borderPurple,
    features: ['Auto-accept rides', 'Nuclear mode (0ms)', 'All supported apps', 'Priority support'],
  },
  {
    id: 'quarterly',
    label: '3 Months',
    price: '₹675',
    duration: '90 days',
    perDay: '₹7.5 / day',
    popular: false,
    accent: colors.blue,
    accentGlow: colors.blueGlow,
    accentBorder: colors.borderBlue,
    features: ['Auto-accept rides', 'All supported apps', 'Priority support', 'Best value'],
  },
];

export default function PlansScreen({ navigation }) {
  const [selected, setSelected] = useState('monthly');
  const [paying, setPaying] = useState(false);
  const plan = PLANS.find(p => p.id === selected);

  const handlePay = async () => {
    const user = await getStoredUser();
    if (!user?.phone) {
      Alert.alert('Login Required', 'Please enter your phone number first.');
      navigation.replace('Login');
      return;
    }

    setPaying(true);
    try {
      const result = await openCheckout(selected, user.phone);
      if (!result?.subscriptionEnd && !result?.success) {
        throw new Error('Payment verified but subscription was not activated. Contact support.');
      }
      // Re-read from Railway so Home matches DB (not only local cache)
      let remote = null;
      try {
        remote = await getSubscriptionStatus(user.phone);
      } catch {
        // verify already succeeded — use result below
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
        planType: remote?.planType || selected,
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

  return (
    <Screen>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <View style={styles.header}>
        <Text style={styles.headerTitle}>Renewal Plans</Text>
        <Text style={styles.headerSub}>Choose a plan to keep auto-accept running</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        {PLANS.map(p => {
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
                  <Text style={styles.popularText}>MOST POPULAR</Text>
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
          disabled={paying}
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
