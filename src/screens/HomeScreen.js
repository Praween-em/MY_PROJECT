import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, TextInput,
  StyleSheet, StatusBar, ScrollView,
  Animated, Alert, Linking,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
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
  requestNotificationPermission,
  openShizukuApp,
  openShizukuPlayStore,
  requestShizukuPermission,
} from '../services/permissions';
import { navigateRoot, replaceRoot } from '../navigation/rootNavigation';
import { clearUser, getStoredUser } from '../utils/storage';
import { getSettings, saveSettings as persistLocalSettings } from '../utils/settingsStorage';
import { formatExpiryDate } from '../utils/formatDate';
import AppSwitch from '../components/AppSwitch';
import { checkEntitlement, ApiError, getSocialLinks } from '../services/api';
import AppHeader, { SectionTitle } from '../components/AppHeader';
import {
  getServiceStatus,
  onRideAccepted,
  saveSettings,
  setServiceEnabled,
  setNuclearMode,
  setDelayMs,
  setMonitoredPackages,
  setMinPrice,
  setMaxPickup,
  isNativeAvailable,
} from '../services/autoclicker';

/** Offline fallback only — live URLs come from GET /socials (admin panel). */
const DEFAULT_SOCIAL_LINKS = {
  whatsapp: '',
  youtube: '',
  telegram: '',
};

const openSocialLink = async (url) => {
  if (!url) {
    Alert.alert('Link unavailable', 'This social link is not configured yet.');
    return;
  }
  try {
    await Linking.openURL(url);
  } catch {
    Alert.alert('Unable to open link', 'Please try again later.');
  }
};

const DEFAULT_PACKAGES = [
  'com.olacabs.oladriver',
  'com.rapido.rider',
  'com.rapido.captain',
];

export default function HomeScreen({ navigation }) {
  const { status: permStatus } = usePermissions();
  const {
    isActive,
    subscriptionEnd,
    subscription,
    loading: subLoading,
    refresh: refreshSub,
    deviceAllowed,
  } = useSubscription();

  const [enabled, setEnabled]       = useState(false);
  const [minPrice, setMinPriceState] = useState('0');
  const [maxPickup, setMaxPickupState] = useState('0');
  const [saving, setSaving]         = useState(false);
  const [socialLinks, setSocialLinks] = useState(DEFAULT_SOCIAL_LINKS);
  const [ridesAccepted, setRidesAccepted] = useState(0);
  const [lastLatency, setLastLatency] = useState(null);

  const accessOn = permStatus.accessibility;
  const nlsOn = !!permStatus.notificationListener;
  const shizukuOn = !!permStatus.shizuku;
  const pulseAnim = useRef(new Animated.Value(1)).current;

  const handleLogout = async () => {
    try {
      await clearUser();
    } catch {
      /* still leave the session screen */
    }
    replaceRoot(navigation, 'Login');
  };

  const featuresAllowed = isActive && deviceAllowed;

  const parseFilters = () => ({
    fare: Math.max(0, parseInt(minPrice || '0', 10) || 0),
    pickup: Math.max(0, parseFloat(maxPickup || '0') || 0),
  });

  const pushFiltersToNative = async (fare, pickup) => {
    if (!isNativeAvailable()) return;
    try {
      await setMinPrice(fare);
      await setMaxPickup(pickup);
    } catch {
      /* keep local filters even if native is down */
    }
  };

  const shutOffAutoAccept = async () => {
    setEnabled(false);
    if (isNativeAvailable()) {
      try {
        await setServiceEnabled(false);
      } catch {
        // ignore — UI already off
      }
    }
  };

  const handleRefresh = async () => {
    try {
      await refreshSub();
      // Force-off runs via featuresAllowed effect after refresh updates isActive
      if (isNativeAvailable()) {
        await setNuclearMode(true).catch(() => {});
        await setDelayMs(0).catch(() => {});
      }
    } catch (err) {
      console.warn('Refresh error:', err);
    }
  };

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const local = await getSettings();
        if (!cancelled) {
          setMinPriceState(String(local.minPrice ?? 0));
          setMaxPickupState(String(local.maxPickup ?? 0));
        }
      } catch {
        // ignore
      }
      if (!isNativeAvailable()) return;
      try {
        const status = await getServiceStatus();
        if (cancelled) return;
        await setNuclearMode(true).catch(() => {});
        await setDelayMs(0).catch(() => {});
        if (typeof status.minPrice === 'number') {
          setMinPriceState(String(status.minPrice));
        }
        if (typeof status.maxPickup === 'number') {
          setMaxPickupState(String(status.maxPickup));
        }
        if (subLoading) return;
        const wantOn = !!status.enabled && featuresAllowed;
        setEnabled(wantOn);
        if (wantOn) {
          try {
            await setServiceEnabled(true);
          } catch {
            // ignore
          }
        }
      } catch {
        // ignore
      }
    })();
    return () => { cancelled = true; };
  }, [subLoading, featuresAllowed]);

  // Force native OFF only when plan end is past or device is blocked.
  // Never treat a bare active===false (API glitch / loading) as shutoff —
  // that was wiping Auto-accept and making zero rides click.
  useEffect(() => {
    if (subLoading) return;
    if (!subscription) return;
    const endPast = !!subscription.subscriptionEnd
      && new Date(subscription.subscriptionEnd) <= new Date();
    if (endPast || deviceAllowed === false) {
      shutOffAutoAccept();
    }
  }, [subLoading, subscription, deviceAllowed]);

  useEffect(() => {
    const unsub = onRideAccepted((event) => {
      try {
        setRidesAccepted((n) => n + 1);
        if (typeof event?.latencyMs === 'number' && event.latencyMs >= 0) {
          setLastLatency(event.latencyMs);
        }
      } catch {
        /* ignore malformed native event */
      }
    });
    return unsub;
  }, []);

  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      getSocialLinks()
        .then((data) => {
          if (cancelled || !data) return;
          setSocialLinks({
            whatsapp: data.whatsapp || '',
            youtube: data.youtube || '',
            telegram: data.telegram || '',
            support: data.support || '',
          });
        })
        .catch(() => {
          // Keep last known / empty offline
        });
      return () => { cancelled = true; };
    }, [])
  );

  const handleToggleEnabled = async (value) => {
    if (value) {
      if (!deviceAllowed) {
        Alert.alert(
          'Device Not Allowed',
          'Log out and sign in again with OTP — that moves your plan to this phone. If it still fails, use Reset devices in the admin panel.'
        );
        return;
      }
      if (!isActive) {
        Alert.alert(
          'Subscription Required',
          'You need an active plan to turn on auto-accept.',
          [
            { text: 'View Plans', onPress: () => navigateRoot(navigation, 'Plans') },
            { text: 'Cancel', style: 'cancel' },
          ]
        );
        return;
      }

      try {
        const user = await getStoredUser();
        if (!user?.phone) {
          Alert.alert('Login Required', 'Please log in again.');
          return;
        }
        const ent = await checkEntitlement(user.phone);
        if (!ent.active) {
          Alert.alert(
            'Subscription Required',
            'Your plan is not active. Please renew to use auto-accept.',
            [
              { text: 'View Plans', onPress: () => navigateRoot(navigation, 'Plans') },
              { text: 'Cancel', style: 'cancel' },
            ]
          );
          await refreshSub();
          return;
        }
        if (ent.deviceAllowed === false) {
          Alert.alert(
            'Device Not Allowed',
            'Log out and sign in again with OTP — that moves your plan to this phone. If it still fails, use Reset devices in the admin panel.'
          );
          return;
        }
      } catch (err) {
        if (err instanceof ApiError && err.code === 'DEVICE_LIMIT') {
          Alert.alert('Device Not Allowed', err.message);
          return;
        }
        if (err instanceof ApiError && err.code === 'BLOCKED') {
          Alert.alert('Account Blocked', err.message);
          return;
        }
        // Offline grace: local isActive already verified above
      }
    }

    // Rapido starts with this toggle + Accessibility. Shizuku is Ola only.
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
          ? 'If you see "Restricted setting", first open App Settings → ⋮ → Allow restricted settings. Then enable AG rider in Accessibility.'
          : 'Enable AG rider in Settings → Accessibility before starting auto-accept.',
        buttons
      );
      return;
    }
    if (value) {
      try {
        await requestNotificationPermission();
      } catch {
        // ignore — a11y race still works without posting notifications
      }
    }
    if (value && accessOn && !nlsOn) {
      Alert.alert(
        'Enable Notification Access',
        'Rapido taps with Accessibility alone. Notification access arms Rapido and Ola sooner. Recommended.',
        [
          { text: 'Open Notification Access', onPress: openNotificationListenerSettings },
          { text: 'Continue anyway', style: 'cancel' },
        ]
      );
    }
    const { fare, pickup } = parseFilters();
    setEnabled(value);
    if (isNativeAvailable()) {
      try {
        await persistLocalSettings({ minPrice: fare, maxPickup: pickup });
        await pushFiltersToNative(fare, pickup);
        await setMonitoredPackages(DEFAULT_PACKAGES);
        await setServiceEnabled(value);
      } catch {
        setEnabled(!value);
      }
    }
  };

  const handleSave = async () => {
    if (!isNativeAvailable()) {
      Alert.alert('Android Only', 'Native auto-clicker runs on a built Android app.');
      return;
    }
    if (subLoading) {
      Alert.alert('Please wait', 'Subscription is still loading — try Save again in a moment.');
      return;
    }
    const { fare, pickup } = parseFilters();
    setSaving(true);
    try {
      const nextEnabled = featuresAllowed ? enabled : false;
      await persistLocalSettings({ minPrice: fare, maxPickup: pickup });
      await saveSettings({
        enabled: nextEnabled,
        minPrice: fare,
        maxPickup: pickup,
        monitoredPackages: DEFAULT_PACKAGES,
      });
      setMinPriceState(String(fare));
      setMaxPickupState(String(pickup));
      Alert.alert('Settings Saved', 'Filters and auto-accept settings are saved.');
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
    } else {
      pulseAnim.stopAnimation();
      pulseAnim.setValue(1);
    }
  }, [enabled]);

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <AppHeader
        title="Ride deck"
        subtitle="Arm the assistant, then set your fare and pickup filters."
        actionIcon={subLoading ? 'hourglass-outline' : 'refresh-outline'}
        actionLabel="Refresh"
        onAction={handleRefresh}
      />

      {!subLoading && !isActive && (
        <TouchableOpacity
          style={styles.paywallBanner}
          onPress={() => navigateRoot(navigation, 'Plans')}
          activeOpacity={0.85}
        >
          <Ionicons name="sparkles-outline" size={16} color={colors.warning} />
          <Text style={styles.paywallText}>No active plan — tap to subscribe</Text>
        </TouchableOpacity>
      )}

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>

        <View style={[styles.powerDeck, enabled ? styles.powerOn : styles.powerOff]}>
          <View style={styles.powerRow}>
            <Animated.View style={[styles.ring, enabled ? styles.ringOn : styles.ringOff, { transform: [{ scale: pulseAnim }] }]}>
              <Ionicons
                name={enabled ? 'flash' : 'power'}
                size={28}
                color={enabled ? colors.onGreen : colors.offRed}
              />
            </Animated.View>
            <View style={styles.powerCopy}>
              <Text style={styles.powerEyebrow}>AUTO PILOT</Text>
              <Text style={styles.powerTitle}>{enabled ? 'Ready for rides' : 'Assistant is off'}</Text>
              <Text style={styles.powerSub}>
                {enabled
                  ? 'Monitoring Rapido and Ola ride signals'
                  : 'Turn on when you are ready to receive rides'}
              </Text>
            </View>
            <AppSwitch
              value={enabled}
              onValueChange={handleToggleEnabled}
              disabled={!featuresAllowed && !enabled}
            />
          </View>
          <View style={[styles.liveChip, enabled ? styles.pillOn : styles.pillOff]}>
            <View style={[styles.liveDot, enabled ? styles.liveDotOn : styles.liveDotOff]} />
            <Text style={[styles.statusPillText, enabled ? styles.textOn : styles.textOff]}>
              {enabled ? 'LIVE' : 'PAUSED'}
            </Text>
          </View>
        </View>

        <View style={styles.tileRow}>
          <View style={styles.tile}>
            <Text style={styles.tileValue}>{ridesAccepted}</Text>
            <Text style={styles.tileLabel}>Accepted</Text>
          </View>
          <View style={styles.tile}>
            <Text style={styles.tileValue}>{lastLatency != null ? `${lastLatency}ms` : '—'}</Text>
            <Text style={styles.tileLabel}>Last tap</Text>
          </View>
          <View style={styles.tile}>
            <Text style={[styles.tileValue, { color: isActive ? colors.onGreen : colors.offRed }]}>
              {isActive ? 'ON' : 'OFF'}
            </Text>
            <Text style={styles.tileLabel}>Plan</Text>
          </View>
        </View>

        <View style={styles.planTicket}>
          <View style={styles.planStub}>
            <Ionicons name="calendar-clear-outline" size={18} color={colors.purpleBright} />
          </View>
          <View style={styles.planCopy}>
            <Text style={styles.planLabel}>Valid until</Text>
            <Text style={styles.planDate}>{formatExpiryDate(subscriptionEnd)}</Text>
          </View>
          <TouchableOpacity
            style={styles.renewBtn}
            onPress={() => navigateRoot(navigation, 'Plans')}
            activeOpacity={0.85}
          >
            <Text style={styles.renewBtnText}>Renew</Text>
          </TouchableOpacity>
        </View>

        <SectionTitle label="ACCESS" title="Required services" />
        <View style={styles.modeRow}>
          <TouchableOpacity
            style={[styles.accessTile, accessOn ? styles.accessOn : styles.accessOff]}
            onPress={openAccessibilitySettings}
            activeOpacity={0.8}
          >
            <View style={[styles.accessIcon, accessOn ? styles.accessIconOn : styles.accessIconOff]}>
              <Ionicons
                name="accessibility-outline"
                size={20}
                color={accessOn ? colors.onGreen : colors.offRed}
              />
            </View>
            <Text style={styles.accessTitle}>Accessibility</Text>
            <Text style={[styles.accessState, accessOn ? styles.textOn : styles.textOff]}>
              {accessOn ? 'Connected' : 'Needs setup'}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.accessTile, nlsOn ? styles.accessOn : styles.accessOff]}
            onPress={openNotificationListenerSettings}
            activeOpacity={0.8}
          >
            <View style={[styles.accessIcon, nlsOn ? styles.accessIconOn : styles.accessIconOff]}>
              <Ionicons
                name="notifications-outline"
                size={20}
                color={nlsOn ? colors.onGreen : colors.offRed}
              />
            </View>
            <Text style={styles.accessTitle}>Ride alerts</Text>
            <Text style={[styles.accessState, nlsOn ? styles.textOn : styles.textOff]}>
              {nlsOn ? 'Connected' : 'Needs setup'}
            </Text>
          </TouchableOpacity>
        </View>

        <SectionTitle label="FILTERS" title="Fare and pickup" />
        <Text style={styles.priceRangeHint}>
          Applies to Rapido and Ola. Set 0 to allow any fare or any pickup distance.
        </Text>
        <View style={styles.filterGrid}>
          <View style={styles.filterPad}>
            <Text style={styles.filterFieldLabel}>Min fare</Text>
            <View style={styles.priceEditBlock}>
              <Text style={styles.rupeeSign}>₹</Text>
              <TextInput
                style={styles.priceInput}
                value={minPrice}
                onChangeText={(t) => setMinPriceState(t.replace(/[^0-9]/g, ''))}
                keyboardType="number-pad"
                placeholder="0"
                placeholderTextColor={colors.icyDim}
                maxLength={6}
                onEndEditing={() => {
                  const { fare, pickup } = parseFilters();
                  persistLocalSettings({ minPrice: fare, maxPickup: pickup }).catch(() => {});
                  pushFiltersToNative(fare, pickup).catch(() => {});
                }}
              />
            </View>
          </View>
          <View style={styles.filterPad}>
            <Text style={styles.filterFieldLabel}>Max pickup</Text>
            <View style={styles.priceEditBlock}>
              <TextInput
                style={styles.priceInput}
                value={maxPickup}
                onChangeText={(t) => setMaxPickupState(t.replace(/[^0-9.]/g, '').replace(/(\..*)\./g, '$1'))}
                keyboardType="decimal-pad"
                placeholder="0"
                placeholderTextColor={colors.icyDim}
                maxLength={5}
                onEndEditing={() => {
                  const { fare, pickup } = parseFilters();
                  persistLocalSettings({ minPrice: fare, maxPickup: pickup }).catch(() => {});
                  pushFiltersToNative(fare, pickup).catch(() => {});
                }}
              />
              <Text style={styles.unitSuffix}>km</Text>
            </View>
          </View>
        </View>
        <Text style={styles.priceHint}>
          Skip offers below the fare or farther than the pickup limit.
        </Text>

        <TouchableOpacity
          style={[styles.saveBtn, saving && { opacity: 0.6 }]}
          activeOpacity={0.85}
          onPress={handleSave}
          disabled={saving}
        >
          <Ionicons name="checkmark" size={18} color={colors.background} />
          <Text style={styles.saveBtnText}>{saving ? 'SAVING…' : 'Save filters'}</Text>
        </TouchableOpacity>

        <SectionTitle label="OLA" title="Privileged connection" />
        <View style={styles.shizukuCard}>
          <View style={styles.shizukuHeader}>
            <View style={styles.shizukuIcon}>
              <Ionicons
                name="flash-outline"
                size={18}
                color={shizukuOn ? colors.onGreen : colors.gold}
              />
            </View>
            <View style={styles.shizukuText}>
              <Text style={styles.shizukuTitle}>Ola connection</Text>
              <Text style={styles.shizukuSub}>
                {!permStatus.shizukuInstalled
                  ? 'Install and start Shizuku to enable Ola Accept.'
                  : shizukuOn
                  ? 'Shizuku is ready for Ola taps.'
                  : !permStatus.shizukuRunning
                  ? 'Open Shizuku and start Wireless debugging.'
                  : 'Grant AG rider permission in Shizuku.'}
              </Text>
            </View>
            <View style={[styles.statusPill, shizukuOn ? styles.pillOn : styles.pillOff]}>
              <Text style={[styles.statusPillText, shizukuOn ? styles.textOn : styles.textOff]}>
                {shizukuOn ? 'READY' : 'SETUP'}
              </Text>
            </View>
          </View>
          <View style={styles.shizukuBtns}>
            {!permStatus.shizukuInstalled && (
              <TouchableOpacity
                style={styles.shizukuBtnPrimary}
                onPress={openShizukuPlayStore}
                activeOpacity={0.85}
              >
                <Text style={styles.shizukuBtnPrimaryText}>Install Shizuku</Text>
              </TouchableOpacity>
            )}
            <TouchableOpacity
              style={styles.shizukuBtn}
              onPress={() => {
                if (!permStatus.shizukuInstalled) openShizukuPlayStore();
                else if (!permStatus.shizukuPermission && permStatus.shizukuRunning) {
                  requestShizukuPermission();
                } else {
                  openShizukuApp();
                }
              }}
              activeOpacity={0.85}
            >
              <Text style={styles.shizukuBtnText}>
                {!permStatus.shizukuInstalled
                  ? 'Open Play Store'
                  : permStatus.shizukuRunning && !permStatus.shizukuPermission
                  ? 'Grant access'
                  : 'Open Shizuku'}
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.helpSocialCard}>
          {!!socialLinks.whatsapp && (
            <TouchableOpacity
              style={styles.helpBtn}
              onPress={() => openSocialLink(socialLinks.whatsapp)}
              activeOpacity={0.75}
            >
              <Ionicons name="logo-whatsapp" size={18} color="#25D366" />
              <Text style={styles.helpText}>Need help?</Text>
            </TouchableOpacity>
          )}

          <View style={styles.socialsRow}>
            {!!socialLinks.youtube && (
              <TouchableOpacity
                style={styles.socialBtn}
                onPress={() => openSocialLink(socialLinks.youtube)}
                activeOpacity={0.75}
              >
                <Ionicons name="logo-youtube" size={20} color="#FF0000" />
                <Text style={styles.socialBtnText}>YouTube</Text>
              </TouchableOpacity>
            )}
            {!!socialLinks.telegram && (
              <TouchableOpacity
                style={styles.socialBtn}
                onPress={() => openSocialLink(socialLinks.telegram)}
                activeOpacity={0.75}
              >
                <Ionicons name="paper-plane" size={18} color="#229ED9" />
                <Text style={styles.socialBtnText}>Telegram</Text>
              </TouchableOpacity>
            )}
          </View>
          <TouchableOpacity style={styles.logoutRow} onPress={handleLogout} activeOpacity={0.75}>
            <Ionicons name="log-out-outline" size={16} color={colors.offRed} />
            <Text style={styles.logoutRowText}>Log out</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  paywallBanner: {
    marginHorizontal: 20, marginBottom: 2, paddingVertical: 10, paddingHorizontal: 14,
    backgroundColor: colors.warningDim, borderRadius: 999,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
  },
  paywallText: { color: colors.warning, fontSize: 13, fontWeight: '700' },

  scroll: { paddingHorizontal: 20, paddingTop: 10, paddingBottom: 40, gap: 14 },

  powerDeck: {
    borderRadius: 28, padding: 18, gap: 14,
  },
  powerOn: { backgroundColor: colors.onGreenDim },
  powerOff: { backgroundColor: colors.offRedDim },
  powerRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  ring: {
    width: 68, height: 68, borderRadius: 34,
    alignItems: 'center', justifyContent: 'center', borderWidth: 2,
  },
  ringOn: { backgroundColor: colors.background, borderColor: colors.onGreen },
  ringOff: { backgroundColor: colors.background, borderColor: colors.offRed },
  powerCopy: { flex: 1 },
  powerEyebrow: { color: colors.icyMuted, fontSize: 10, fontWeight: '800', letterSpacing: 1.4 },
  powerTitle: { fontSize: 20, fontWeight: '800', color: colors.icy, marginTop: 3, letterSpacing: -0.4 },
  powerSub: { fontSize: 12, lineHeight: 17, color: colors.icyMuted, marginTop: 4 },
  liveChip: {
    alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: 12, paddingVertical: 6,
    flexDirection: 'row', alignItems: 'center', gap: 7,
  },
  liveDot: { width: 7, height: 7, borderRadius: 4 },
  liveDotOn: { backgroundColor: colors.onGreen },
  liveDotOff: { backgroundColor: colors.offRed },
  statusPill: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 5 },
  pillOn: { backgroundColor: colors.onGreenDim },
  pillOff: { backgroundColor: colors.offRedDim },
  statusPillText: { fontSize: 11, fontWeight: '800', letterSpacing: 1 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },

  tileRow: { flexDirection: 'row', gap: 10 },
  tile: {
    flex: 1, backgroundColor: colors.surfaceCard, borderRadius: 20,
    paddingVertical: 16, alignItems: 'center', gap: 4,
  },
  tileValue: { color: colors.icy, fontSize: 18, fontWeight: '800' },
  tileLabel: { color: colors.icyMuted, fontSize: 11, fontWeight: '700' },

  planTicket: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    backgroundColor: colors.surfaceCard, borderRadius: 22, padding: 12, paddingRight: 12,
  },
  planStub: {
    width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center',
    backgroundColor: colors.purpleGlow,
  },
  planCopy: { flex: 1 },
  planLabel: { color: colors.icyMuted, fontSize: 11, fontWeight: '700' },
  planDate: { color: colors.icy, fontSize: 16, fontWeight: '800', marginTop: 2 },
  renewBtn: {
    backgroundColor: colors.purple, borderRadius: 999,
    paddingHorizontal: 16, paddingVertical: 10,
  },
  renewBtnText: { color: colors.background, fontSize: 13, fontWeight: '800' },

  modeRow: { flexDirection: 'row', gap: 10 },
  accessTile: {
    flex: 1, borderRadius: 22, padding: 16, gap: 10,
  },
  accessOn: { backgroundColor: colors.onGreenDim },
  accessOff: { backgroundColor: colors.offRedDim },
  accessIcon: {
    width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center',
  },
  accessIconOn: { backgroundColor: colors.background },
  accessIconOff: { backgroundColor: colors.background },
  accessTitle: { color: colors.icy, fontSize: 14, fontWeight: '800' },
  accessState: { fontSize: 12, fontWeight: '700' },

  filterGrid: { flexDirection: 'row', gap: 10 },
  filterPad: {
    flex: 1, backgroundColor: colors.surfaceCard, borderRadius: 22, padding: 14, gap: 10,
  },
  filterFieldLabel: { fontSize: 12, color: colors.icyMuted, fontWeight: '700' },
  priceRangeHint: { fontSize: 12, color: colors.icyDim, lineHeight: 17, marginTop: -4 },
  priceEditBlock: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: colors.surfaceLight, borderRadius: 16,
    paddingHorizontal: 12, paddingVertical: 10, gap: 6,
  },
  rupeeSign: { fontSize: 22, color: colors.icy, fontWeight: '800' },
  unitSuffix: { fontSize: 14, color: colors.icyMuted, fontWeight: '800' },
  priceInput: { flex: 1, fontSize: 22, fontWeight: '800', color: colors.icy, padding: 0 },
  priceHint: { fontSize: 12, color: colors.icyMuted, marginTop: -4 },

  saveBtn: {
    backgroundColor: colors.purple,
    borderRadius: 999, paddingVertical: 16,
    flexDirection: 'row', justifyContent: 'center', alignItems: 'center', gap: 8,
  },
  saveBtnText: { color: colors.background, fontSize: 15, fontWeight: '800' },

  shizukuCard: {
    backgroundColor: colors.surfaceCard, borderRadius: 24, padding: 16, gap: 14,
  },
  shizukuHeader: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  shizukuIcon: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center',
    backgroundColor: colors.surfaceLight,
  },
  shizukuText: { flex: 1, gap: 4 },
  shizukuTitle: { fontSize: 15, fontWeight: '800', color: colors.icy },
  shizukuSub: { fontSize: 12, color: colors.icyDim, lineHeight: 17 },
  shizukuBtns: { flexDirection: 'row', gap: 10 },
  shizukuBtnPrimary: {
    flex: 1, backgroundColor: colors.purple, borderRadius: 999,
    paddingVertical: 12, alignItems: 'center',
  },
  shizukuBtnPrimaryText: { fontSize: 13, fontWeight: '800', color: colors.background },
  shizukuBtn: {
    flex: 1, borderRadius: 999, paddingVertical: 12, alignItems: 'center',
    backgroundColor: colors.surfaceLight,
  },
  shizukuBtnText: { fontSize: 13, fontWeight: '800', color: colors.purpleBright },

  helpSocialCard: { gap: 12, paddingBottom: 8 },
  helpBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    backgroundColor: colors.surfaceCard, borderRadius: 999, paddingVertical: 12,
  },
  helpText: { color: colors.icy, fontSize: 13, fontWeight: '700' },
  socialsRow: { flexDirection: 'row', gap: 10 },
  socialBtn: {
    flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    gap: 8, paddingVertical: 12, borderRadius: 999,
    backgroundColor: colors.surfaceCard,
  },
  socialBtnText: { fontSize: 13, fontWeight: '700', color: colors.icy },
  logoutRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 7,
    paddingVertical: 8,
  },
  logoutRowText: { color: colors.offRed, fontSize: 13, fontWeight: '800' },
});
