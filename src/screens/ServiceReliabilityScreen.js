import React, { useCallback, useState } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity, RefreshControl, Platform,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import {
  openAccessibilitySettings,
  openAppInfoSettings,
  openNotificationListenerSettings,
  requestBatteryOptimizationExemption,
} from '../services/permissions';
import { getServiceHealth, isNativeAvailable } from '../services/autoclicker';
import {
  LANG_STORAGE_KEY,
  RELIABILITY_LANGS,
  formatAge,
  getDiagnoseCopy,
  getOemGuide,
  t,
} from '../i18n/reliability';

const AUTOSTART_VERIFIED_KEY = '@superridex/autostart_verified';

function StatusRow({ label, value, ok, warn }) {
  const color = ok ? colors.onGreen : warn ? colors.warning : colors.offRed;
  return (
    <View style={styles.statusRow}>
      <Text style={styles.statusLabel}>{label}</Text>
      <Text style={[styles.statusValue, { color }]}>{value}</Text>
    </View>
  );
}

export default function ServiceReliabilityScreen({ navigation }) {
  const [health, setHealth] = useState(null);
  const [showOem, setShowOem] = useState(false);
  const [showDiag, setShowDiag] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [lang, setLang] = useState('en');
  /** Android cannot read OEM Auto-start — user confirms after checking phone settings. */
  const [autostartVerified, setAutostartVerified] = useState(false);

  const load = useCallback(async () => {
    try {
      const savedLang = await AsyncStorage.getItem(LANG_STORAGE_KEY);
      if (savedLang === 'en' || savedLang === 'hi' || savedLang === 'te') {
        setLang(savedLang);
      }
    } catch {
      /* keep default */
    }
    try {
      const v = await AsyncStorage.getItem(AUTOSTART_VERIFIED_KEY);
      setAutostartVerified(v === '1');
    } catch {
      setAutostartVerified(false);
    }
    if (!isNativeAvailable()) {
      setHealth({
        accessibilityEnabled: false,
        serviceConnected: false,
        masterEnabled: false,
        batteryOptimizationOk: false,
        oemId: 'generic',
        oemLabel: Platform.OS === 'android' ? 'Android' : 'Not Android',
        diagnoseCode: 'A',
        diagnoseMessage: 'Native engine only runs on the built Android app.',
        lastA11yEventAgeMs: -1,
        lastRideAgeMs: -1,
        lastAcceptAgeMs: -1,
        lastClickSuccessAgeMs: -1,
        phase: '—',
      });
      return;
    }
    try {
      const h = await getServiceHealth();
      setHealth(h);
    } catch {
      setHealth(null);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const changeLang = async (id) => {
    setLang(id);
    try {
      await AsyncStorage.setItem(LANG_STORAGE_KEY, id);
    } catch {
      /* ignore */
    }
  };

  const markAutostartVerified = async () => {
    await AsyncStorage.setItem(AUTOSTART_VERIFIED_KEY, '1');
    setAutostartVerified(true);
  };

  const clearAutostartVerified = async () => {
    await AsyncStorage.removeItem(AUTOSTART_VERIFIED_KEY);
    setAutostartVerified(false);
  };

  const oem = getOemGuide(lang, health?.oemId);
  const a11yOk = !!health?.accessibilityEnabled && !!health?.serviceConnected;
  const batteryOk = !!health?.batteryOptimizationOk;
  const masterOk = !!health?.masterEnabled;
  const nlsOk = !!health?.notificationListenerEnabled;
  const diag = getDiagnoseCopy(lang, health?.diagnoseCode);
  const diagDetail = health?.diagnoseMessage || diag.detail;
  const allGood = a11yOk && batteryOk && masterOk && nlsOk && autostartVerified
    && health?.diagnoseCode === 'OK';

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} hitSlop={12}>
          <Ionicons name="arrow-back" size={22} color={colors.icy} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t(lang, 'title')}</Text>
        <View style={{ width: 22 }} />
      </View>

      <ScrollView
        contentContainerStyle={styles.scroll}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
      >
        <Text style={styles.section}>{t(lang, 'language')}</Text>
        <View style={styles.langRow}>
          {RELIABILITY_LANGS.map((item) => {
            const active = lang === item.id;
            return (
              <TouchableOpacity
                key={item.id}
                style={[styles.langChip, active && styles.langChipActive]}
                onPress={() => changeLang(item.id)}
              >
                <Text style={[styles.langChipText, active && styles.langChipTextActive]}>
                  {item.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>

        <View style={[styles.banner, allGood ? styles.bannerOk : styles.bannerWarn]}>
          <Text style={styles.bannerTitle}>
            {allGood ? `✓ ${t(lang, 'bannerOk')}` : `⚠ ${t(lang, 'bannerWarn')}`}
          </Text>
          <Text style={styles.bannerSub}>
            {allGood ? t(lang, 'bannerOkSub') : t(lang, 'bannerWarnSub')}
          </Text>
        </View>

        <Text style={styles.section}>{t(lang, 'status')}</Text>
        <View style={styles.card}>
          <StatusRow
            label={t(lang, 'accessibility')}
            value={a11yOk ? `✓ ${t(lang, 'enabled')}` : `✗ ${t(lang, 'disabled')}`}
            ok={a11yOk}
          />
          <StatusRow
            label={t(lang, 'master')}
            value={masterOk ? `✓ ${t(lang, 'on')}` : `✗ ${t(lang, 'off')}`}
            ok={masterOk}
          />
          <StatusRow
            label={t(lang, 'battery')}
            value={batteryOk ? `✓ ${t(lang, 'batteryOk')}` : `✗ ${t(lang, 'batteryRestricted')}`}
            ok={batteryOk}
          />
          <StatusRow
            label={t(lang, 'nls')}
            value={nlsOk ? `✓ ${t(lang, 'enabled')}` : `✗ ${t(lang, 'disabled')}`}
            ok={nlsOk}
          />
          <StatusRow
            label={t(lang, 'autostart')}
            value={autostartVerified ? `✓ ${t(lang, 'autostartVerified')}` : `⚠ ${t(lang, 'autostartCheck')}`}
            ok={autostartVerified}
            warn={!autostartVerified}
          />
          <StatusRow
            label={t(lang, 'oem')}
            value={health?.oemLabel || oem.title}
            ok
          />
          <Text style={styles.manualNote}>{t(lang, 'autostartHint')}</Text>
          {!autostartVerified ? (
            <TouchableOpacity style={styles.btn} onPress={markAutostartVerified}>
              <Text style={styles.btnText}>{t(lang, 'markVerified')}</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity style={styles.btn} onPress={clearAutostartVerified}>
              <Text style={styles.btnText}>{t(lang, 'clearVerified')}</Text>
            </TouchableOpacity>
          )}
        </View>

        <Text style={styles.section}>{t(lang, 'actions')}</Text>
        <View style={styles.card}>
          <TouchableOpacity style={styles.btn} onPress={() => setShowOem((v) => !v)}>
            <Text style={styles.btnText}>{showOem ? t(lang, 'hideOem') : t(lang, 'viewOem')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.btn} onPress={() => { setShowDiag(true); load(); }}>
            <Text style={styles.btnText}>{t(lang, 'diagnose')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.btnPrimary} onPress={openAccessibilitySettings}>
            <Text style={styles.btnPrimaryText}>{t(lang, 'openA11y')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.btn} onPress={openNotificationListenerSettings}>
            <Text style={styles.btnText}>{t(lang, 'openNls')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.btn} onPress={requestBatteryOptimizationExemption}>
            <Text style={styles.btnText}>{t(lang, 'openBattery')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.btn} onPress={openAppInfoSettings}>
            <Text style={styles.btnText}>{t(lang, 'openAppInfo')}</Text>
          </TouchableOpacity>
        </View>

        {showOem && (
          <>
            <Text style={styles.section}>{t(lang, 'improve')} — {oem.title}</Text>
            <View style={styles.card}>
              {oem.steps.map((step, i) => (
                <Text key={i} style={styles.step}>
                  {i + 1}. {step}
                </Text>
              ))}
              <Text style={styles.manualNote}>{t(lang, 'oemMenuNote')}</Text>
            </View>
          </>
        )}

        {showDiag && (
          <>
            <Text style={styles.section}>{t(lang, 'diagnoseSection')}</Text>
            <View style={styles.card}>
              <Text style={[styles.diagTitle, { color: health?.diagnoseCode === 'OK' ? colors.onGreen : colors.warning }]}>
                {diag.title}
              </Text>
              <Text style={styles.diagDetail}>{diagDetail}</Text>
              {(health?.diagnoseCode === 'C' || !nlsOk) && (
                <Text style={styles.manualNote}>{t(lang, 'rideFixHint')}</Text>
              )}
              <View style={styles.sep} />
              <StatusRow label={t(lang, 'phase')} value={health?.phase || '—'} ok />
              <StatusRow
                label={t(lang, 'lastA11y')}
                value={formatAge(health?.lastA11yEventAgeMs, lang)}
                ok={health?.lastA11yEventAgeMs >= 0}
              />
              <StatusRow
                label={t(lang, 'lastRide')}
                value={formatAge(health?.lastRideAgeMs, lang)}
                ok={health?.lastRideAgeMs >= 0}
              />
              <StatusRow
                label={t(lang, 'lastAccept')}
                value={formatAge(health?.lastAcceptAgeMs, lang)}
                ok={health?.lastAcceptAgeMs >= 0}
              />
              <StatusRow
                label={t(lang, 'lastClick')}
                value={
                  health?.lastClickSuccessAgeMs >= 0
                    ? `${t(lang, 'success')} · ${formatAge(health.lastClickSuccessAgeMs, lang)}`
                    : '—'
                }
                ok={health?.lastClickSuccessAgeMs >= 0}
              />
              <Text style={styles.manualNote}>{t(lang, 'diagnoseHint')}</Text>
            </View>
          </>
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  headerTitle: { fontSize: 17, fontWeight: '800', color: colors.icy },
  scroll: { padding: 16, gap: 12, paddingBottom: 40 },
  langRow: { flexDirection: 'row', gap: 8 },
  langChip: {
    flex: 1,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
    backgroundColor: colors.surfaceLight,
  },
  langChipActive: {
    borderColor: colors.purple,
    backgroundColor: colors.purple + '22',
  },
  langChipText: { fontSize: 13, fontWeight: '700', color: colors.icyDim },
  langChipTextActive: { color: colors.purpleBright || colors.purple },
  banner: { borderRadius: 12, padding: 14, borderWidth: 1, gap: 6 },
  bannerOk: { backgroundColor: colors.onGreenDim, borderColor: colors.onGreen + '55' },
  bannerWarn: { backgroundColor: colors.warningDim || '#F59E0B18', borderColor: (colors.warning || '#F59E0B') + '66' },
  bannerTitle: { fontSize: 14, fontWeight: '800', color: colors.icy },
  bannerSub: { fontSize: 12, color: colors.icyDim, lineHeight: 18 },
  section: { fontSize: 12, fontWeight: '800', color: colors.icyMuted, letterSpacing: 0.4, marginTop: 4 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: colors.border,
    gap: 10,
  },
  statusRow: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  statusLabel: { fontSize: 13, color: colors.icyDim, flex: 1 },
  statusValue: { fontSize: 13, fontWeight: '700', maxWidth: '55%', textAlign: 'right' },
  manualNote: { fontSize: 11, color: colors.icyMuted, lineHeight: 16 },
  btn: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    paddingVertical: 12,
    alignItems: 'center',
    backgroundColor: colors.surfaceLight,
  },
  btnText: { fontSize: 14, fontWeight: '700', color: colors.icy },
  btnPrimary: {
    borderRadius: 10,
    paddingVertical: 13,
    alignItems: 'center',
    backgroundColor: colors.purple,
  },
  btnPrimaryText: { fontSize: 14, fontWeight: '700', color: colors.white },
  step: { fontSize: 13, color: colors.icy, lineHeight: 20 },
  diagTitle: { fontSize: 15, fontWeight: '800' },
  diagDetail: { fontSize: 13, color: colors.icyDim, lineHeight: 19 },
  sep: { height: 1, backgroundColor: colors.border },
});
