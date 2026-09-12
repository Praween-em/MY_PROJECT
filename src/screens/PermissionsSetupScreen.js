import React from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import AppHeader, { SectionTitle } from '../components/AppHeader';
import { usePermissions } from '../hooks/usePermissions';
import { replaceRoot } from '../navigation/rootNavigation';
import {
  openAccessibilitySettings,
  openAppInfoSettings,
  requestBatteryOptimizationExemption,
  needsRestrictedSettingsUnlock,
  openShizukuApp,
  openShizukuPlayStore,
  requestShizukuPermission,
} from '../services/permissions';

const STEPS = [
  {
    id: 'accessibility',
    num: '01',
    title: 'Accessibility Service',
    description: 'Required for Rapido and Ola. Finds Accept on screen. Rapido is tapped here instantly. Ola waits 5s, then Shizuku taps.',
    instruction: null,
    action: openAccessibilitySettings,
    actionLabel: 'Open Accessibility Settings',
    accentColor: colors.purple,
    accentBorder: colors.borderPurple,
    accentGlow: colors.purpleGlow,
  },
  {
    id: 'battery',
    num: '02',
    title: 'Battery Optimization Exemption',
    description: 'Prevents Android from killing the service. Without this, auto-accept stops when the screen turns off.',
    instruction: 'Tap the button below — select "Don\'t optimize" in the system dialog.',
    action: requestBatteryOptimizationExemption,
    actionLabel: 'Request Battery Exemption',
    accentColor: colors.purple,
    accentBorder: colors.borderPurple,
    accentGlow: colors.purpleGlow,
  },
  {
    id: 'shizuku',
    num: '03',
    title: 'Shizuku (Ola only)',
    description: 'Optional for Rapido. Required for Ola — Accessibility finds Accept, Shizuku injects the tap after the 5s unlock.',
    instruction: 'Install Shizuku → open it → Start (Wireless debugging / pairing) → return here and grant permission.',
    action: openShizukuPlayStore,
    actionLabel: 'Install Shizuku from Play Store',
    optional: true,
    accentColor: colors.purple,
    accentBorder: colors.borderPurple,
    accentGlow: colors.purpleGlow,
  },
];

export default function PermissionsSetupScreen({ navigation }) {
  const { status, check } = usePermissions();
  const allDone = status.accessibility && status.battery;

  const shizukuAction = async () => {
    if (!status.shizukuInstalled) {
      openShizukuPlayStore();
      return;
    }
    if (!status.shizukuRunning) {
      openShizukuApp();
      return;
    }
    if (!status.shizukuPermission) {
      await requestShizukuPermission();
      check();
    }
  };

  const shizukuLabel = !status.shizukuInstalled
    ? 'Install Shizuku from Play Store →'
    : !status.shizukuRunning
      ? 'Open Shizuku and start it →'
      : !status.shizukuPermission
        ? 'Grant Shizuku permission →'
        : 'Connected';

  return (
    <Screen>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <AppHeader
        eyebrow="SETUP"
        title="Get connected"
        subtitle="Turn on the services AG rider needs to assist you."
        actionIcon="refresh-outline"
        actionLabel="Check"
        onAction={check}
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={[styles.progressCard, allDone && styles.progressCardDone]}>
          <View style={{ flex: 1 }}>
            <Text style={styles.progressEyebrow}>SETUP STATUS</Text>
            <Text style={styles.progressTitle}>
              {allDone ? 'Core access is ready' : 'Complete the required steps'}
            </Text>
            <View style={styles.track}>
              <View style={[styles.trackFill, { width: `${([status.accessibility, status.battery].filter(Boolean).length / 2) * 100}%` }]} />
            </View>
          </View>
          <Text style={[styles.progressCount, allDone && styles.textOn]}>
            {[status.accessibility, status.battery].filter(Boolean).length}/2
          </Text>
        </View>

        <SectionTitle label="CHECKLIST" title="Connect your services" />
        {STEPS.map((step) => {
          const granted = status[step.id];
          const optional = !!step.optional;
          return (
            <View
              key={step.id}
              style={[
                styles.card,
                granted && { backgroundColor: step.accentGlow },
              ]}
            >
              <View style={styles.cardHead}>
                <View style={[
                  styles.badge,
                  granted
                    ? { backgroundColor: step.accentColor }
                    : { backgroundColor: colors.surfaceLight },
                ]}>
                  <Text style={[styles.badgeText, granted && { color: colors.background }]}>
                    {granted ? '✓' : step.num}
                  </Text>
                </View>

                <View style={styles.cardHeadText}>
                  <Text style={styles.stepTitle}>{step.title}</Text>
                    <View style={[
                      styles.chip,
                      granted ? styles.chipOn : optional ? styles.chipOptional : styles.chipOff,
                    ]}>
                      <Text style={[
                        styles.chipText,
                        granted ? styles.textOn : optional ? styles.textOptional : styles.textOff,
                      ]}>
                        {granted ? 'GRANTED' : optional ? 'OPTIONAL' : 'REQUIRED'}
                      </Text>
                    </View>
                </View>
              </View>

              <Text style={styles.stepDesc}>{step.description}</Text>

              {step.id === 'shizuku' && !granted && (
                <View style={styles.restrictedBox}>
                  <Text style={styles.restrictedTitle}>How to connect Shizuku</Text>
                  <Text style={styles.restrictedStep}>1. Install Shizuku from the Play Store</Text>
                  <Text style={styles.restrictedStep}>2. Open Shizuku → Start via Wireless debugging (or root)</Text>
                  <Text style={styles.restrictedStep}>3. Pair once if Android asks for a pairing code</Text>
                  <Text style={styles.restrictedStep}>4. Return here and tap Grant Shizuku permission</Text>
                  <Text style={styles.restrictedNote}>
                    Keep Shizuku running while you take rides. After reboot, start Shizuku again.
                  </Text>
                  {!status.shizukuInstalled && (
                    <TouchableOpacity
                      style={[styles.restrictedBtn, { borderColor: colors.purple, backgroundColor: colors.purpleGlow }]}
                      onPress={openShizukuPlayStore}
                      activeOpacity={0.85}
                    >
                      <Text style={[styles.restrictedBtnText, { color: colors.purpleBright }]}>
                        Open Play Store — Shizuku
                      </Text>
                    </TouchableOpacity>
                  )}
                  {status.shizukuInstalled && !status.shizukuRunning && (
                    <TouchableOpacity
                      style={[styles.restrictedBtn, { borderColor: colors.purple, backgroundColor: colors.purpleGlow }]}
                      onPress={openShizukuApp}
                      activeOpacity={0.85}
                    >
                      <Text style={[styles.restrictedBtnText, { color: colors.purpleBright }]}>
                        Open Shizuku app
                      </Text>
                    </TouchableOpacity>
                  )}
                </View>
              )}

              {step.id === 'accessibility' && needsRestrictedSettingsUnlock() && !granted && (
                <View style={styles.restrictedBox}>
                  <Text style={styles.restrictedTitle}>Don't see "Allow restricted settings"?</Text>
                  <Text style={styles.restrictedText}>
                    Android hides that option until you try to enable accessibility first. Follow this exact order:
                  </Text>
                  <Text style={styles.restrictedStep}>1. Tap "Try Enable Accessibility" below — toggle AG rider ON</Text>
                  <Text style={styles.restrictedStep}>2. You'll see "Restricted setting" — that's normal, go back</Text>
                  <Text style={styles.restrictedStep}>3. Tap "Open App Settings" → menu → Allow restricted settings</Text>
                  <Text style={styles.restrictedStep}>4. Confirm PIN/fingerprint → return to Accessibility → turn ON again</Text>
                  <Text style={styles.restrictedNote}>
                    Still blocked? Common fixes:{'\n'}
                    • Rebuilding/reinstalling the app RESETS this — you must unlock again{'\n'}
                    • Uninstall AG rider fully → reinstall APK by tapping the file{'\n'}
                    • Long-press app icon → App info → look for menu or "Allow restricted settings"{'\n'}
                    • Samsung: option may be on App Info page directly (scroll down){'\n'}
                    • After allowing, come back here and tap Re-check Permissions
                  </Text>
                  <TouchableOpacity
                    style={[styles.restrictedBtn, { borderColor: colors.purple, backgroundColor: colors.purpleGlow }]}
                    onPress={openAccessibilitySettings}
                    activeOpacity={0.85}
                  >
                    <Text style={[styles.restrictedBtnText, { color: colors.purpleBright }]}>
                      Try Enable Accessibility
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.restrictedBtn, { borderColor: colors.warning }]}
                    onPress={openAppInfoSettings}
                    activeOpacity={0.85}
                  >
                    <Text style={[styles.restrictedBtnText, { color: colors.warning }]}>
                      Open App Settings
                    </Text>
                  </TouchableOpacity>
                </View>
              )}

              {/* Instruction */}
              <View style={[styles.instructionBox, { borderLeftColor: step.accentColor }]}>
                <Text style={[styles.instructionLabel, { color: step.accentColor }]}>HOW TO ENABLE</Text>
                <Text style={styles.instructionText}>
                  {step.id === 'accessibility'
                    ? 'Settings → Accessibility → Downloaded / Installed apps → AG rider → ON'
                    : step.id === 'shizuku'
                    ? 'Only for Ola. Play Store → install Shizuku → Start → grant AG rider'
                    : step.instruction}
                </Text>
              </View>

              {/* Action button */}
              {!granted && (
                <TouchableOpacity
                  style={[styles.actionBtn, { backgroundColor: step.accentColor }]}
                  onPress={step.id === 'shizuku' ? shizukuAction : step.action}
                  activeOpacity={0.85}
                >
                  <Text style={styles.actionBtnText}>
                    {step.id === 'shizuku'
                      ? shizukuLabel
                      : step.id === 'accessibility' && needsRestrictedSettingsUnlock()
                      ? 'Step 2 — Open Accessibility →'
                      : `${step.actionLabel} →`}
                  </Text>
                </TouchableOpacity>
              )}
            </View>
          );
        })}

        {/* Continue */}
        <TouchableOpacity
          style={[
            styles.continueBtn,
            allDone
              ? { backgroundColor: colors.purple }
              : { backgroundColor: colors.surfaceLight },
          ]}
          onPress={() => replaceRoot(navigation, 'Main')}
          activeOpacity={0.85}
        >
          <Text style={[styles.continueBtnText, !allDone && { color: colors.icyMuted }]}>
            {allDone ? 'All set — open Home' : 'Continue to Home'}
          </Text>
        </TouchableOpacity>

      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: { paddingHorizontal: 20, paddingTop: 8, gap: 12, paddingBottom: 40 },

  progressCard: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    backgroundColor: colors.surfaceCard, borderRadius: 26, padding: 18, gap: 14,
  },
  progressCardDone: { backgroundColor: colors.onGreenDim },
  progressEyebrow: { fontSize: 10, color: colors.icyMuted, fontWeight: '800', letterSpacing: 1.3 },
  progressTitle: { fontSize: 16, color: colors.icy, fontWeight: '800', marginTop: 4 },
  progressCount: { fontSize: 28, color: colors.goldBright, fontWeight: '800' },
  track: {
    height: 6, backgroundColor: colors.surfaceLight, borderRadius: 3, overflow: 'hidden', marginTop: 12,
  },
  trackFill: { height: 6, backgroundColor: colors.purple, borderRadius: 3 },

  card: {
    backgroundColor: colors.surfaceCard,
    borderRadius: 24, padding: 16, gap: 12,
  },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  badge: {
    width: 44, height: 44, borderRadius: 22,
    alignItems: 'center', justifyContent: 'center',
  },
  badgeText: { fontSize: 13, fontWeight: '800', color: colors.icyMuted },

  cardHeadText: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 10, flexWrap: 'wrap' },
  stepTitle:    { fontSize: 16, fontWeight: '800', color: colors.icy },

  chip: {
    borderRadius: 999,
    paddingHorizontal: 8, paddingVertical: 4,
  },
  chipOn: { backgroundColor: colors.onGreenDim },
  chipOff: { backgroundColor: colors.offRedDim },
  chipOptional: { backgroundColor: colors.warningDim },
  chipText: { fontSize: 9, fontWeight: '800', letterSpacing: 0.8 },
  textOn: { color: colors.onGreen },
  textOff: { color: colors.offRed },
  textOptional: { color: colors.warning },

  stepDesc: { fontSize: 13, color: colors.icyDim, lineHeight: 20 },

  restrictedBox: {
    backgroundColor: colors.warningDim,
    borderRadius: 18, padding: 14, gap: 8,
  },
  restrictedTitle: { fontSize: 14, fontWeight: '800', color: colors.warning },
  restrictedText: { fontSize: 12, color: colors.icyDim, lineHeight: 18 },
  restrictedStep: { fontSize: 12, color: colors.icy, lineHeight: 18, paddingLeft: 4 },
  restrictedNote: { fontSize: 11, color: colors.icyMuted, lineHeight: 17, marginTop: 4, fontStyle: 'italic' },
  restrictedBtn: {
    marginTop: 6, borderWidth: 1.5, borderRadius: 999,
    paddingVertical: 12, alignItems: 'center',
  },
  restrictedBtnText: { fontSize: 13, fontWeight: '800' },

  instructionBox: {
    backgroundColor: colors.surfaceLight,
    borderRadius: 16, padding: 12,
    borderLeftWidth: 3, gap: 4,
  },
  instructionLabel: { fontSize: 9, fontWeight: '800', letterSpacing: 1 },
  instructionText:  { fontSize: 12, color: colors.icyDim, lineHeight: 18 },

  actionBtn: {
    borderRadius: 999, paddingVertical: 14,
    alignItems: 'center',
  },
  actionBtnText: { color: colors.background, fontSize: 14, fontWeight: '800' },

  continueBtn: {
    borderRadius: 999, paddingVertical: 18,
    alignItems: 'center',
  },
  continueBtnText: { color: colors.background, fontSize: 15, fontWeight: '800' },
});
