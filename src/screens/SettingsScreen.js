import React, { useState, useEffect } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import AppHeader from '../components/AppHeader';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { getStoredUser, clearUser } from '../utils/storage';
import { usePermissions } from '../hooks/usePermissions';
import { getApiBaseUrl } from '../services/api';

function SectionLabel({ children }) {
  return (
    <View style={styles.sectionLabelRow}>
      <View style={styles.sectionBar} />
      <Text style={styles.sectionLabel}>{children}</Text>
    </View>
  );
}

function SettingRow({ icon, title, sub, subColor, onPress, right, separator = true }) {
  const Wrap = onPress ? TouchableOpacity : View;
  return (
    <>
      <Wrap style={styles.row} onPress={onPress} activeOpacity={0.7}>
        <View style={styles.rowIconBox}>{icon}</View>
        <View style={styles.rowContent}>
          <Text style={styles.rowTitle}>{title}</Text>
          {sub ? <Text style={[styles.rowSub, subColor && { color: subColor }]}>{sub}</Text> : null}
        </View>
        {right}
        {onPress && <Text style={styles.chevron}>›</Text>}
      </Wrap>
      {separator && <View style={styles.separator} />}
    </>
  );
}

export default function SettingsScreen({ navigation }) {
  const { status: permStatus } = usePermissions();
  const [phone, setPhone] = useState('');

  useEffect(() => {
    getStoredUser().then(u => setPhone(u?.phone ? `+91 ${u.phone}` : ''));
  }, []);

  const handleLogout = async () => {
    try {
      await clearUser();
    } catch {
      /* still leave */
    }
    replaceRoot(navigation, 'Login');
  };

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <AppHeader
        eyebrow="CONTROL"
        title="Settings"
        subtitle="Access, account and legal in one place."
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <SectionLabel>Service access</SectionLabel>
        <View style={styles.group}>
          <SettingRow
            icon={<Ionicons name="accessibility-outline" size={18} color={colors.purpleBright} />} title="Accessibility Service"
            sub={permStatus.accessibility ? 'Enabled' : 'Not enabled — Tap to fix'}
            subColor={permStatus.accessibility ? colors.onGreen : colors.offRed}
            onPress={() => navigateRoot(navigation, 'PermissionsSetup')}
          />
          <SettingRow
            icon={<Ionicons name="battery-charging-outline" size={18} color={colors.purpleBright} />} title="Battery Optimization"
            sub={permStatus.battery ? 'Exemption granted' : 'Not granted'}
            subColor={permStatus.battery ? colors.onGreen : colors.offRed}
            onPress={() => navigateRoot(navigation, 'PermissionsSetup')}
            separator={false}
          />
        </View>

        <SectionLabel>Account</SectionLabel>
        <View style={styles.group}>
          <SettingRow icon={<Ionicons name="phone-portrait-outline" size={18} color={colors.purpleBright} />} title="Mobile Number" sub={phone || '—'} />
          <SettingRow
            icon={<Ionicons name="card-outline" size={18} color={colors.purpleBright} />} title="Subscription / Renewal"
            sub="View plans"
            subColor={colors.purpleBright}
            onPress={() => navigateRoot(navigation, 'Plans')}
          />
          <SettingRow
            icon={<Ionicons name="cloud-outline" size={18} color={colors.purpleBright} />}
            title="Backend"
            sub={getApiBaseUrl()}
            separator={false}
          />
        </View>

        <SectionLabel>Legal</SectionLabel>
        <View style={styles.group}>
          <SettingRow
            icon={<Ionicons name="document-text-outline" size={18} color={colors.purpleBright} />}
            title="Privacy Policy"
            sub="How we handle your data"
            onPress={() => navigateRoot(navigation, 'PrivacyPolicy')}
          />
          <SettingRow
            icon={<Ionicons name="shield-checkmark-outline" size={18} color={colors.purpleBright} />}
            title="Terms & Conditions"
            sub="Accessibility assistive use terms"
            onPress={() => navigateRoot(navigation, 'Terms')}
            separator={false}
          />
        </View>

        <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout} activeOpacity={0.85}>
          <Text style={styles.logoutText}>Log out</Text>
        </TouchableOpacity>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: { paddingHorizontal: 20, paddingTop: 8, paddingBottom: 48, gap: 6 },
  sectionLabelRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 14, marginBottom: 8, marginLeft: 4 },
  sectionBar: { width: 12, height: 2, borderRadius: 1, backgroundColor: colors.purple },
  sectionLabel: { fontSize: 11, fontWeight: '800', color: colors.icyMuted, letterSpacing: 1.4, textTransform: 'uppercase' },
  group: { backgroundColor: colors.surfaceCard, borderRadius: 24, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 15, gap: 14 },
  rowIconBox: { width: 40, height: 40, borderRadius: 20, backgroundColor: colors.surfaceLight, alignItems: 'center', justifyContent: 'center' },
  rowContent: { flex: 1 },
  rowTitle: { fontSize: 15, fontWeight: '700', color: colors.icy },
  rowSub: { fontSize: 12, color: colors.icyDim, marginTop: 3 },
  chevron: { fontSize: 22, color: colors.icyMuted },
  separator: { height: 1, backgroundColor: colors.border, marginLeft: 70 },
  logoutBtn: {
    marginTop: 18, borderRadius: 999, paddingVertical: 16, alignItems: 'center',
    backgroundColor: colors.offRedDim,
  },
  logoutText: { color: colors.offRed, fontSize: 14, fontWeight: '800' },
});
