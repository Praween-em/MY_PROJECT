import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity, Alert, Share,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import AppHeader, { SectionTitle } from '../components/AppHeader';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { getStoredUser, clearUser } from '../utils/storage';
import { useSubscription } from '../hooks/useSubscription';
import { useReferral } from '../hooks/useReferral';
import { formatExpiryDate } from '../utils/formatDate';

const REFERRAL_GOAL = 10;

export default function ProfileScreen({ navigation }) {
  const { subscriptionEnd, isActive } = useSubscription();
  const {
    referralCode,
    totalReferrals,
    paidReferrals,
    referralRewardsEarned,
    progress,
    remaining,
    loading: refLoading,
    refresh,
  } = useReferral();

  const [phone, setPhone] = useState('');

  useEffect(() => {
    getStoredUser().then(u => setPhone(u?.phone || ''));
  }, []);

  const handleCopyCode = async () => {
    const message = `Join AG rider! Use my referral code ${referralCode} when you sign up. Auto-accept rides faster!`;
    try {
      await Share.share({ message });
    } catch {
      Alert.alert('Your Referral Code', referralCode);
    }
  };

  const handleLogout = async () => {
    try {
      await clearUser();
    } catch {
      /* still leave */
    }
    replaceRoot(navigation, 'Login');
  };

  const progressPct = Math.min(100, (progress / REFERRAL_GOAL) * 100);

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <AppHeader
        eyebrow="ACCOUNT"
        title="Your profile"
        subtitle="Membership, shortcuts and referral rewards."
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.userCard}>
          <View style={styles.avatarRow}>
            <View style={styles.avatar}>
              <Text style={styles.avatarLetter}>{phone ? phone.slice(-2) : 'AG'}</Text>
            </View>
            <View style={styles.userMeta}>
              <Text style={styles.userName}>Driver</Text>
              <Text style={styles.userPhone}>{phone ? `+91 ${phone}` : '—'}</Text>
            </View>
            <View style={[styles.statusBadge, isActive ? styles.badgeOn : styles.badgeOff]}>
              <Text style={[styles.statusBadgeText, isActive ? styles.textOn : styles.textOff]}>
                {isActive ? 'ACTIVE' : 'EXPIRED'}
              </Text>
            </View>
          </View>

          <View style={styles.detailGrid}>
            <View style={styles.detailTile}>
              <Text style={styles.detailLabel}>Phone</Text>
              <Text style={styles.detailValue}>{phone || '—'}</Text>
            </View>
            <View style={styles.detailTile}>
              <Text style={styles.detailLabel}>Plan expiry</Text>
              <Text style={styles.detailValue}>{formatExpiryDate(subscriptionEnd)}</Text>
            </View>
          </View>
        </View>

        <SectionTitle label="SHORTCUTS" title="Quick actions" />
        <View style={styles.quickRow}>
          <TouchableOpacity style={styles.quickBtn} onPress={() => navigation.navigate('SettingsTab')}>
            <View style={styles.quickIcon}>
              <Ionicons name="settings-outline" size={20} color={colors.purpleBright} />
            </View>
            <Text style={styles.quickBtnText}>Settings</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.quickBtn} onPress={() => navigateRoot(navigation, 'Plans')}>
            <View style={styles.quickIcon}>
              <Ionicons name="card-outline" size={20} color={colors.purpleBright} />
            </View>
            <Text style={styles.quickBtnText}>Renew</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.quickBtn} onPress={() => navigateRoot(navigation, 'PermissionsSetup')}>
            <View style={styles.quickIcon}>
              <Ionicons name="shield-checkmark-outline" size={20} color={colors.purpleBright} />
            </View>
            <Text style={styles.quickBtnText}>Access</Text>
          </TouchableOpacity>
        </View>

        {/* Referral Program */}
        <SectionTitle label="REWARDS" title="Referral program" />

        <View style={styles.referralCard}>
          <View style={styles.referralHead}>
            <View style={styles.giftDot}>
              <Ionicons name="gift-outline" size={18} color={colors.onGreen} />
            </View>
            <Text style={styles.referralReward}>
              Refer {REFERRAL_GOAL} people who pay for at least 1 month → get <Text style={styles.rewardHighlight}>1 month FREE</Text>
            </Text>
          </View>

          <View style={styles.ticketCut} />

          <View style={styles.inviteRow}>
            <View>
              <Text style={styles.inviteLabel}>Invite code</Text>
              <Text style={styles.inviteCodePlain}>{refLoading ? '…' : referralCode}</Text>
            </View>
            <TouchableOpacity style={styles.inviteCodeBtn} onPress={handleCopyCode} activeOpacity={0.85}>
              <Text style={styles.copyIcon}>Share</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.progressWrap}>
            <View style={styles.progressBarBg}>
              <View style={[styles.progressBarFill, { width: `${progressPct}%` }]} />
            </View>
            <Text style={styles.progressText}>
              {progress} / {REFERRAL_GOAL} paid referrals toward next free month
              {remaining > 0 ? ` · ${remaining} more needed` : ' · Reward unlocked!'}
            </Text>
          </View>

          <View style={styles.divider} />

          <View style={styles.refStats}>
            <View style={styles.refStat}>
              <Text style={styles.refStatValue}>{totalReferrals}</Text>
              <Text style={styles.refStatLabel}>Total Invited</Text>
            </View>
            <View style={styles.refDivider} />
            <View style={styles.refStat}>
              <Text style={[styles.refStatValue, { color: colors.onGreen }]}>{paidReferrals}</Text>
              <Text style={styles.refStatLabel}>Paid (≥1 mo)</Text>
            </View>
            <View style={styles.refDivider} />
            <View style={styles.refStat}>
              <Text style={[styles.refStatValue, { color: colors.purpleBright }]}>{referralRewardsEarned}</Text>
              <Text style={styles.refStatLabel}>Free Months Won</Text>
            </View>
          </View>

          <Text style={styles.refRules}>
            • Only counts when referred user pays 1-month or 3-month plan{'\n'}
            • Each referred user counts once{'\n'}
            • Free month added automatically when you hit {REFERRAL_GOAL} paid referrals
          </Text>

          <TouchableOpacity style={[styles.refreshBtn, { flexDirection: 'row', alignItems: 'center', gap: 6 }]} onPress={refresh}>
            <Ionicons name="refresh-outline" size={14} color={colors.blue} />
            <Text style={styles.refreshText}>Refresh referral stats</Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout} activeOpacity={0.85}>
          <Text style={styles.logoutText}>Log out securely</Text>
        </TouchableOpacity>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: { paddingHorizontal: 20, paddingTop: 8, gap: 14, paddingBottom: 40 },

  userCard: {
    backgroundColor: colors.surfaceCard, borderRadius: 28, padding: 18, gap: 16,
  },
  avatarRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: {
    width: 64, height: 64, borderRadius: 32, backgroundColor: colors.purple,
    alignItems: 'center', justifyContent: 'center',
  },
  avatarLetter: { fontSize: 26, fontWeight: '800', color: colors.background },
  userMeta: { flex: 1 },
  userName: { fontSize: 22, fontWeight: '800', color: colors.icy, letterSpacing: -0.4 },
  userPhone: { fontSize: 13, color: colors.icyDim, marginTop: 3 },
  statusBadge: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 6 },
  badgeOn: { backgroundColor: colors.onGreenDim },
  badgeOff: { backgroundColor: colors.offRedDim },
  statusBadgeText: { fontSize: 10, fontWeight: '800', letterSpacing: 1 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },
  detailGrid: { flexDirection: 'row', gap: 10 },
  detailTile: {
    flex: 1, backgroundColor: colors.surfaceLight, borderRadius: 16, padding: 12, gap: 4,
  },
  detailLabel: { fontSize: 11, color: colors.icyMuted, fontWeight: '700' },
  detailValue: { fontSize: 14, fontWeight: '800', color: colors.icy },
  divider: { height: 1, backgroundColor: colors.border },

  quickRow: { flexDirection: 'row', gap: 10 },
  quickBtn: {
    flex: 1, alignItems: 'center', gap: 8, paddingVertical: 14,
    backgroundColor: colors.surfaceCard, borderRadius: 22,
  },
  quickIcon: {
    width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center',
    backgroundColor: colors.surfaceLight,
  },
  quickBtnText: { fontSize: 12, fontWeight: '700', color: colors.icyDim },

  referralCard: {
    backgroundColor: colors.surfaceCard, borderRadius: 28, padding: 18, gap: 14,
  },
  referralHead: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  giftDot: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center',
    backgroundColor: colors.onGreenDim,
  },
  referralReward: { flex: 1, fontSize: 14, color: colors.icyDim, lineHeight: 21 },
  rewardHighlight: { color: colors.onGreen, fontWeight: '800' },
  ticketCut: {
    height: 1, backgroundColor: colors.border,
  },
  inviteRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  inviteLabel: { fontSize: 12, color: colors.icyMuted, fontWeight: '700' },
  inviteCodePlain: { fontSize: 20, fontWeight: '800', color: colors.icy, letterSpacing: 1.4, marginTop: 3 },
  inviteCodeBtn: {
    backgroundColor: colors.purple, paddingHorizontal: 16, paddingVertical: 12, borderRadius: 999,
  },
  copyIcon: { fontSize: 13, color: colors.background, fontWeight: '800' },

  progressWrap: { gap: 8 },
  progressBarBg: { height: 8, backgroundColor: colors.surfaceLight, borderRadius: 4, overflow: 'hidden' },
  progressBarFill: { height: 8, backgroundColor: colors.onGreen, borderRadius: 4 },
  progressText: { fontSize: 12, color: colors.icyDim },

  refStats: { flexDirection: 'row', alignItems: 'center' },
  refStat: { flex: 1, alignItems: 'center', gap: 4 },
  refStatValue: { fontSize: 26, fontWeight: '800', color: colors.icy },
  refStatLabel: { fontSize: 11, color: colors.icyDim, textAlign: 'center' },
  refDivider: { width: 1, height: 36, backgroundColor: colors.border },
  refRules: { fontSize: 12, color: colors.icyMuted, lineHeight: 20 },
  refreshBtn: { alignItems: 'center', paddingVertical: 8 },
  refreshText: { color: colors.blue, fontSize: 13, fontWeight: '600' },

  logoutBtn: {
    borderRadius: 999, paddingVertical: 16, alignItems: 'center', backgroundColor: colors.offRedDim,
  },
  logoutText: { color: colors.offRed, fontSize: 14, fontWeight: '800' },
});
