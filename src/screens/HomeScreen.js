import React, { useState, useRef, useEffect } from 'react';
import {
  View, Text, TouchableOpacity, TextInput,
  StyleSheet, StatusBar, ScrollView,
  Animated, Alert,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { usePermissions } from '../hooks/usePermissions';
import { useSubscription } from '../hooks/useSubscription';
import {
  openAccessibilitySettings,
  openAppInfoSettings,
  openNotificationListenerSettings,
  needsRestrictedSettingsUnlock,
} from '../services/permissions';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { clearUser } from '../utils/storage';
import { getSettings } from '../utils/settingsStorage';
import { addRideAccepted } from '../utils/rideHistory';
import { formatExpiryDate, formatPlanLabel } from '../utils/formatDate';
import AppSwitch from '../components/AppSwitch';
import {
  getServiceStatus,
  onRideAccepted,
  saveSettings,
  setServiceEnabled,
  setNuclearMode,
  isNativeAvailable,
} from '../services/autoclicker';

const MIN_PRICE = 0;
const MAX_PRICE = 2500;

const DEFAULT_PACKAGES = [
  'com.rapido.rider',        // Rapido driver/captain app (confirmed package name)
  'com.rideandhra.driverapp',// RideAndhra driver app
  'com.olacabs.driver',
  'com.ubercab.driver',
];

function clamp(val, min, max) { return Math.max(min, Math.min(max, val)); }

export default function HomeScreen({ navigation }) {
  const { status: permStatus } = usePermissions();
  const {
    isActive,
    daysRemaining,
    planType,
    subscriptionEnd,
    subscriptionStart,
    loading: subLoading,
    refresh: refreshSub,
  } = useSubscription();

  const [enabled, setEnabled]       = useState(false);
  const [nuclearMode, setNuclear]   = useState(true);
  const [minPrice, setMinPrice]     = useState(0);
  const [minPriceText, setMinPriceText] = useState('0');
  const [elapsed, setElapsed]       = useState(0);
  const [saving, setSaving]         = useState(false);
  const [ridesAccepted, setRidesAccepted] = useState(0);
  const [lastLatency, setLastLatency] = useState(null);

  const accessOn = permStatus.accessibility;
  const nlsOn = !!permStatus.notificationListener;
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const timerRef  = useRef(null);

  const handleLogout = async () => {
    await clearUser();
    replaceRoot(navigation, 'Login');
  };

  const handleRefresh = async () => {
    try {
      await refreshSub();
      if (isNativeAvailable()) {
        const status = await getServiceStatus();
        setEnabled(!!status.enabled);
        setNuclear(status.nuclearMode !== false);
        const p = status.minPrice ?? 0;
        setMinPrice(p);
        setMinPriceText(String(p));
      }
    } catch (err) {
      console.warn('Refresh error:', err);
    }
  };

  useEffect(() => {
    if (!isNativeAvailable()) return;
    getServiceStatus()
      .then(status => {
        setEnabled(!!status.enabled);
        setNuclear(status.nuclearMode !== false);
        const p = status.minPrice ?? 0;
        setMinPrice(p);
        setMinPriceText(String(p));
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    const unsub = onRideAccepted((event) => {
      setRidesAccepted(n => n + 1);
      if (typeof event?.latencyMs === 'number' && event.latencyMs >= 0) {
        setLastLatency(event.latencyMs);
      }
      addRideAccepted(event).catch(() => {});
    });
    return unsub;
  }, []);

  const handleModeChange = async (nuclear) => {
    setNuclear(nuclear);
    if (isNativeAvailable()) {
      try {
        await setNuclearMode(nuclear);
      } catch {
        setNuclear(!nuclear);
      }
    }
  };

  const handleToggleEnabled = async (value) => {
    if (value && !accessOn) {
      const buttons = needsRestrictedSettingsUnlock()
        ? [
            { text: 'Allow Restricted Settings', onPress: openAppInfoSettings },
            { text: 'Open Accessibility', onPress: openAccessibilitySettings },
            { text: 'Cancel', style: 'cancel' },
          ]
        : [
            { text: 'Open Settings', onPress: openAccessibilitySettings },
            { text: 'Cancel', style: 'cancel' },
          ];
      Alert.alert(
        'Accessibility Required',
        needsRestrictedSettingsUnlock()
          ? 'If you see "Restricted setting", first open App Settings → ⋮ → Allow restricted settings. Then enable Playnix in Accessibility.'
          : 'Enable Playnix in Settings → Accessibility before starting auto-accept.',
        buttons
      );
      return;
    }
    if (value && accessOn && !nlsOn) {
      Alert.alert(
        'Enable Notification Access',
        'For max speed, turn ON Playnix / Ride Alerts in Notification access. This dual-channel often beats other clickers.',
        [
          { text: 'Open Notification Access', onPress: openNotificationListenerSettings },
          { text: 'Continue anyway', style: 'cancel' },
        ]
      );
    }
    setEnabled(value);
    if (isNativeAvailable()) {
      try {
        await setServiceEnabled(value);
      } catch {
        setEnabled(!value);
      }
    }
  };

  const commitMinPrice = (text) => {
    const n = parseInt(text, 10);
    if (text === '' || isNaN(n)) {
      setMinPriceText('0');
      setMinPrice(0);
      return;
    }
    const clamped = clamp(n, MIN_PRICE, MAX_PRICE);
    setMinPrice(clamped);
    setMinPriceText(String(clamped));
  };

  const handleSave = async () => {
    if (!isNativeAvailable()) {
      Alert.alert('Android Only', 'Native auto-clicker runs on a built Android app.');
      return;
    }
    setSaving(true);
    try {
      const settings = await getSettings();
      await saveSettings({
        enabled,
        minPrice,
        nuclearMode,
        delayMs: nuclearMode ? 0 : (settings.delayMs ?? 0),
        monitoredPackages: DEFAULT_PACKAGES,
      });
      Alert.alert(
        'Saved',
        nuclearMode
          ? 'Nuclear: micro-burst Accept (immediate dual/triple + ~5ms hammer, soft retry ~250ms). NLS PendingIntent still fires. No continuous spray.'
          : 'Standard: filtered Accept detect/click only (price/distance). No continuous FG spray.'
      );
    } catch (err) {
      Alert.alert('Error', err.message || 'Could not save settings.');
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    if (enabled) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.2, duration: 900, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 900, useNativeDriver: true }),
        ])
      ).start();
      timerRef.current = setInterval(() => setElapsed(e => e + 1), 1000);
    } else {
      pulseAnim.stopAnimation();
      pulseAnim.setValue(1);
      if (timerRef.current) clearInterval(timerRef.current);
      setElapsed(0);
    }
    return () => timerRef.current && clearInterval(timerRef.current);
  }, [enabled]);

  const fmt = s => {
    const h  = String(Math.floor(s / 3600)).padStart(2, '0');
    const m  = String(Math.floor((s % 3600) / 60)).padStart(2, '0');
    const sc = String(s % 60).padStart(2, '0');
    return `${h}:${m}:${sc}`;
  };

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <View style={styles.nav}>
        <TouchableOpacity style={styles.navBtn} onPress={handleRefresh} disabled={subLoading}>
          {subLoading ? (
            <Ionicons name="hourglass-outline" size={18} color={colors.blue} />
          ) : (
            <Ionicons name="refresh-outline" size={18} color={colors.blue} />
          )}
          <Text style={[styles.navLabel, { color: colors.blue }]}>Refresh</Text>
        </TouchableOpacity>

        <View style={styles.navCenter}>
          <Text style={styles.navTitle}>PLAY</Text>
          <Text style={[styles.navTitle, styles.navTitleAccent]}>NIX</Text>
        </View>

        <TouchableOpacity style={styles.navBtn} onPress={handleLogout}>
          <Ionicons name="log-out-outline" size={18} color={colors.offRed} />
          <Text style={[styles.navLabel, { color: colors.offRed }]}>Logout</Text>
        </TouchableOpacity>
      </View>

      {!subLoading && !isActive && (
        <TouchableOpacity
          style={styles.paywallBanner}
          onPress={() => navigateRoot(navigation, 'Plans')}
          activeOpacity={0.85}
        >
          <Text style={styles.paywallText}>⚠️  No active plan — tap to subscribe</Text>
        </TouchableOpacity>
      )}

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        {/* Subscription expiry card */}
        <View style={[styles.expiryCard, isActive ? styles.expiryActive : styles.expiryExpired]}>
          <View style={styles.expiryHeader}>
            <Text style={styles.expiryLabel}>SUBSCRIPTION</Text>
            <View style={[styles.statusPill, isActive ? styles.pillOn : styles.pillOff]}>
              <Text style={[styles.statusPillText, isActive ? styles.textOn : styles.textOff]}>
                {isActive ? 'ACTIVE' : 'EXPIRED'}
              </Text>
            </View>
          </View>
          <Text style={styles.expiryPlan}>{formatPlanLabel(planType)} Plan</Text>
          <View style={styles.expiryRow}>
            <View style={styles.expiryCol}>
              <Text style={styles.expiryMetaLabel}>Expires</Text>
              <Text style={styles.expiryMetaValue}>{formatExpiryDate(subscriptionEnd)}</Text>
            </View>
            <View style={styles.expiryCol}>
              <Text style={styles.expiryMetaLabel}>Days Left</Text>
              <Text style={[styles.expiryMetaValue, isActive && { color: colors.onGreen }]}>
                {isActive ? daysRemaining : 0}
              </Text>
            </View>
            <View style={styles.expiryCol}>
              <Text style={styles.expiryMetaLabel}>Started</Text>
              <Text style={styles.expiryMetaValue}>{formatExpiryDate(subscriptionStart)}</Text>
            </View>
          </View>
          <TouchableOpacity
            style={styles.renewBtn}
            onPress={() => navigateRoot(navigation, 'Plans')}
            activeOpacity={0.85}
          >
            <Text style={styles.renewBtnText}>Renew Plan →</Text>
          </TouchableOpacity>
        </View>

        {/* Master toggle */}
        <View style={[styles.masterCard, enabled ? styles.masterCardOn : styles.masterCardOff]}>
          <Animated.View style={[styles.orb, enabled ? styles.orbOn : styles.orbOff, { transform: [{ scale: pulseAnim }] }]}>
            <Ionicons
              name={enabled ? 'flash' : 'power-outline'}
              size={22}
              color={enabled ? colors.onGreen : colors.offRed}
            />
          </Animated.View>
          <View style={styles.masterText}>
            <Text style={[styles.masterStatus, enabled ? styles.textOn : styles.textOff]}>
              {enabled ? 'AUTO-ACCEPT ON' : 'AUTO-ACCEPT OFF'}
            </Text>
            <Text style={styles.masterSub}>
              {enabled
                ? `Session ${fmt(elapsed)} · ${ridesAccepted} accepted${lastLatency != null ? ` · last ${lastLatency}ms` : ''}`
                : 'Toggle to start monitoring'}
            </Text>
          </View>
          <AppSwitch value={enabled} onValueChange={handleToggleEnabled} />
        </View>

        {/* Mode row — Nuclear vs Standard */}
        <View style={styles.modeRow}>
          <TouchableOpacity
            style={[styles.modePill, nuclearMode ? styles.modePillNuclear : styles.modePillIdle]}
            onPress={() => handleModeChange(true)}
            activeOpacity={0.85}
          >
            <Ionicons name="flash" size={16} color={nuclearMode ? colors.white : colors.icyMuted} />
            <Text style={[styles.modePillText, nuclearMode && styles.modePillTextOn]}>Nuclear</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.modePill, !nuclearMode ? styles.modePillStandard : styles.modePillIdle]}
            onPress={() => handleModeChange(false)}
            activeOpacity={0.85}
          >
            <Ionicons name="shield-checkmark-outline" size={16} color={!nuclearMode ? colors.white : colors.icyMuted} />
            <Text style={[styles.modePillText, !nuclearMode && styles.modePillTextOn]}>Standard</Text>
          </TouchableOpacity>
        </View>
        <Text style={styles.modeHint}>
          {nuclearMode
          ? 'Nuclear: Accept micro-burst (immediate click+gesture, then ~5ms dual strikes; soft retry, no 3s dead time). PendingIntent on notification. Never taps the floating Rapido bubble or other apps.'
          : 'Standard: filtered Accept detect/click only (no continuous FG spray).'}
        </Text>

        <View style={styles.modeRow}>
          <TouchableOpacity
            style={[styles.accessPill, accessOn ? styles.accessOn : styles.accessOff]}
            onPress={openAccessibilitySettings}
            activeOpacity={0.8}
          >
            <Ionicons
              name="accessibility-outline"
              size={16}
              color={accessOn ? colors.onGreen : colors.offRed}
            />
            <Text style={[styles.accessPillText, accessOn ? styles.textOn : styles.textOff]}>
              A11y {accessOn ? 'ON' : 'OFF'}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.accessPill, nlsOn ? styles.accessOn : styles.accessOff]}
            onPress={openNotificationListenerSettings}
            activeOpacity={0.8}
          >
            <Ionicons
              name="notifications-outline"
              size={16}
              color={nlsOn ? colors.onGreen : colors.offRed}
            />
            <Text style={[styles.accessPillText, nlsOn ? styles.textOn : styles.textOff]}>
              Notif {nlsOn ? 'ON' : 'OFF'}
            </Text>
          </TouchableOpacity>
        </View>

        {/* Min price — editable text block */}
        <View style={styles.priceCard}>
          <Text style={styles.priceCardLabel}>Min Ride Price</Text>
          <Text style={styles.priceRangeHint}>Enter amount between ₹{MIN_PRICE} and ₹{MAX_PRICE}</Text>
          <View style={styles.priceEditBlock}>
            <Text style={styles.rupeeSign}>₹</Text>
            <TextInput
              style={styles.priceInput}
              value={minPriceText}
              onChangeText={setMinPriceText}
              onBlur={() => commitMinPrice(minPriceText)}
              keyboardType="number-pad"
              maxLength={4}
              placeholder="0"
              placeholderTextColor={colors.icyMuted}
              selectTextOnFocus
            />
          </View>
          <Text style={styles.priceHint}>
            Rides at ₹{minPrice} or above will be auto-accepted
          </Text>
        </View>

        <TouchableOpacity
          style={[styles.saveBtn, saving && { opacity: 0.6 }]}
          activeOpacity={0.85}
          onPress={handleSave}
          disabled={saving}
        >
          <Text style={styles.saveBtnText}>{saving ? 'SAVING…' : 'SAVE SETTINGS'}</Text>
        </TouchableOpacity>
      </ScrollView>

      <View style={styles.bottomBar}>
        <TouchableOpacity style={styles.helpBtn}>
          <Ionicons name="headset-outline" size={14} color={colors.purpleBright} />
          <Text style={styles.helpText}>NEED HELP?</Text>
        </TouchableOpacity>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  nav: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginHorizontal: 16, marginTop: 8, marginBottom: 4,
    paddingHorizontal: 16, paddingVertical: 14,
    backgroundColor: colors.surface, borderRadius: 16,
    borderWidth: 1, borderColor: colors.border,
  },
  navCenter: { flex: 1, flexDirection: 'row', justifyContent: 'center', alignItems: 'center' },
  navTitle: { fontSize: 28, fontWeight: '900', color: colors.icy, letterSpacing: 3 },
  navTitleAccent: { color: colors.blue },
  navBtn: { alignItems: 'center', minWidth: 52 },
  navIcon: { fontSize: 18 },
  navLabel: { fontSize: 10, marginTop: 2, fontWeight: '700' },

  paywallBanner: {
    marginHorizontal: 16, marginBottom: 8, padding: 12,
    backgroundColor: colors.warningDim, borderRadius: 12,
    borderWidth: 1, borderColor: colors.warning + '55',
  },
  paywallText: { color: colors.warning, fontSize: 13, fontWeight: '700', textAlign: 'center' },

  scroll: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 16, gap: 14 },

  expiryCard: {
    borderRadius: 18, padding: 18, gap: 12,
    borderWidth: 1.5,
  },
  expiryActive: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen + '66' },
  expiryExpired: { backgroundColor: colors.offRedDim, borderColor: colors.offRed + '66' },
  expiryHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  expiryLabel: { fontSize: 11, fontWeight: '800', color: colors.icyMuted, letterSpacing: 1.5 },
  statusPill: { borderRadius: 8, paddingHorizontal: 10, paddingVertical: 4 },
  pillOn: { backgroundColor: colors.onGreen + '33' },
  pillOff: { backgroundColor: colors.offRed + '33' },
  statusPillText: { fontSize: 11, fontWeight: '900', letterSpacing: 1 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },
  expiryPlan: { fontSize: 20, fontWeight: '900', color: colors.icy },
  expiryRow: { flexDirection: 'row', gap: 8 },
  expiryCol: { flex: 1, backgroundColor: colors.surfaceLight, borderRadius: 10, padding: 10 },
  expiryMetaLabel: { fontSize: 10, color: colors.icyMuted, fontWeight: '700', letterSpacing: 0.5 },
  expiryMetaValue: { fontSize: 14, fontWeight: '800', color: colors.icy, marginTop: 4 },
  renewBtn: {
    backgroundColor: colors.purple, borderRadius: 12,
    paddingVertical: 14, alignItems: 'center',
  },
  renewBtnText: { color: colors.white, fontSize: 14, fontWeight: '900' },

  masterCard: {
    flexDirection: 'row', alignItems: 'center', gap: 14,
    borderRadius: 20, padding: 18, borderWidth: 1.5,
  },
  masterCardOn: { backgroundColor: '#0A2E18', borderColor: colors.onGreen },
  masterCardOff: { backgroundColor: '#2E0A14', borderColor: colors.offRed },
  orb: {
    width: 50, height: 50, borderRadius: 25,
    alignItems: 'center', justifyContent: 'center', borderWidth: 2,
  },
  orbOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen },
  orbOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed },
  orbIcon: { fontSize: 22 },
  masterText: { flex: 1 },
  masterStatus: { fontSize: 15, fontWeight: '900', letterSpacing: 0.5 },
  masterSub: { fontSize: 12, color: colors.icyMuted, marginTop: 3 },

  modeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  modePill: {
    flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 8, paddingVertical: 14, borderRadius: 50, borderWidth: 1.5,
    minWidth: '30%',
  },
  modePillNuclear: { backgroundColor: colors.purple, borderColor: colors.purple },
  modePillStandard: { backgroundColor: colors.blue, borderColor: colors.blue },
  modePillIdle: { backgroundColor: colors.surface, borderColor: colors.border },
  modePillText: { fontSize: 14, fontWeight: '800', color: colors.icyMuted },
  modePillTextOn: { color: colors.white },
  modeHint: { fontSize: 12, color: colors.icyMuted, marginTop: -4, marginBottom: 4, paddingHorizontal: 4 },
  accessPill: {
    flexGrow: 1, flexBasis: '30%', flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 6, paddingVertical: 12, paddingHorizontal: 8, borderRadius: 50, borderWidth: 1.5,
  },
  accessOn: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen },
  accessOff: { backgroundColor: colors.offRedDim, borderColor: colors.offRed },
  accessPillIcon: { fontSize: 16 },
  accessPillText: { fontSize: 12, fontWeight: '800' },

  priceCard: {
    backgroundColor: colors.surface, borderRadius: 18, padding: 18,
    borderWidth: 1.5, borderColor: colors.borderBlue, gap: 10,
  },
  priceCardLabel: { fontSize: 12, color: colors.icyMuted, fontWeight: '800', letterSpacing: 1, textTransform: 'uppercase' },
  priceRangeHint: { fontSize: 12, color: colors.icyDim },
  priceEditBlock: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: colors.surfaceLight, borderRadius: 14,
    borderWidth: 2, borderColor: colors.borderBlue,
    paddingHorizontal: 20, paddingVertical: 16, gap: 8,
  },
  rupeeSign: { fontSize: 36, color: colors.icy, fontWeight: '800' },
  priceInput: { flex: 1, fontSize: 42, fontWeight: '900', color: colors.icy },
  priceHint: { fontSize: 13, color: colors.blue, fontStyle: 'italic' },

  saveBtn: {
    backgroundColor: colors.blue, borderRadius: 16, paddingVertical: 20,
    alignItems: 'center', shadowColor: colors.blue, shadowOpacity: 0.45, shadowRadius: 14, elevation: 8,
  },
  saveBtnText: { color: colors.white, fontSize: 16, fontWeight: '900', letterSpacing: 1.5 },

  bottomBar: {
    marginHorizontal: 16, marginBottom: 8, padding: 12,
    backgroundColor: colors.surface, borderRadius: 16, borderWidth: 1, borderColor: colors.border,
  },
  helpBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8 },
  helpIcon: { fontSize: 14 },
  helpText: { color: colors.purpleBright, fontSize: 12, fontWeight: '700' },
});
