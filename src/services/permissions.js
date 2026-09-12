/**
 * permissions.js
 * Helpers for checking and requesting critical Android permissions:
 *   1. Accessibility Service  — finds Accept in Rapido and Ola; Rapido taps with ACTION_CLICK
 *   2. Shizuku                — Play Store app; Ola Accept taps only
 *   3. Notification Listener  — dual-channel race (often earlier than a11y notifs)
 *   4. Battery Optimization Exemption
 *
 * Display-over-other-apps is optional leftover API (not required for Accept).
 * Auto-accept does not need it (Accessibility / Shizuku).
 * All functions are no-ops on non-Android platforms.
 */

import { Platform, Linking, NativeModules } from 'react-native';

// Registered as 'AutoClickerModule' in AutoClickerPackage.java
const AutoClickerModule = NativeModules.AutoClickerModule || {};

// ─── Shizuku (Play Store) ─────────────────────────────────────────────────────

export function getShizukuStatus() {
  if (Platform.OS !== 'android') {
    return Promise.resolve({
      installed: false, running: false, permission: false, ready: false, state: 'missing',
    });
  }
  if (!AutoClickerModule.getShizukuStatus) {
    return Promise.resolve({
      installed: false, running: false, permission: false, ready: false, state: 'missing',
    });
  }
  return AutoClickerModule.getShizukuStatus();
}

export function requestShizukuPermission() {
  if (Platform.OS !== 'android' || !AutoClickerModule.requestShizukuPermission) {
    return Promise.resolve(false);
  }
  return AutoClickerModule.requestShizukuPermission();
}

export function openShizukuApp() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openShizukuApp) {
    AutoClickerModule.openShizukuApp();
  }
}

export function openShizukuPlayStore() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openShizukuPlayStore) {
    AutoClickerModule.openShizukuPlayStore();
  } else {
    Linking.openURL('https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api').catch(() => {});
  }
}

// ─── Accessibility Service ────────────────────────────────────────────────────

export function isAccessibilityEnabled() {
  if (Platform.OS !== 'android') return Promise.resolve(false);
  if (!AutoClickerModule.isAccessibilityEnabled) return Promise.resolve(false);
  return AutoClickerModule.isAccessibilityEnabled();
}

export function openAccessibilitySettings() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openAccessibilitySettings) {
    AutoClickerModule.openAccessibilitySettings();
  } else {
    Linking.openSettings();
  }
}

/** Opens App Info — required on Android 13+ to "Allow restricted settings" for sideloaded APKs */
export function openAppInfoSettings() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openAppInfoSettings) {
    AutoClickerModule.openAppInfoSettings();
  } else {
    Linking.openSettings();
  }
}

export function needsRestrictedSettingsUnlock() {
  return Platform.OS === 'android' && Platform.Version >= 33;
}

// ─── Display over other apps ────────────────────────────────────────────────

export function isOverlayPermissionGranted() {
  if (Platform.OS !== 'android') return Promise.resolve(false);
  if (!AutoClickerModule.isOverlayPermissionGranted) return Promise.resolve(false);
  return AutoClickerModule.isOverlayPermissionGranted();
}

export function openOverlaySettings() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openOverlaySettings) {
    AutoClickerModule.openOverlaySettings();
  } else {
    Linking.openSettings();
  }
}

// ─── Notification Listener (dual-channel race) ────────────────────────────────

export function isNotificationListenerEnabled() {
  if (Platform.OS !== 'android') return Promise.resolve(false);
  if (!AutoClickerModule.isNotificationListenerEnabled) return Promise.resolve(false);
  return AutoClickerModule.isNotificationListenerEnabled();
}

export function openNotificationListenerSettings() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openNotificationListenerSettings) {
    AutoClickerModule.openNotificationListenerSettings();
  } else {
    Linking.openSettings();
  }
}

// ─── Battery Optimization ─────────────────────────────────────────────────────

export function isBatteryOptimizationIgnored() {
  if (Platform.OS !== 'android') return Promise.resolve(false);
  if (!AutoClickerModule.isBatteryOptimizationIgnored) return Promise.resolve(false);
  return AutoClickerModule.isBatteryOptimizationIgnored();
}

export function requestBatteryOptimizationExemption() {
  if (Platform.OS !== 'android') return;
  if (AutoClickerModule.openBatteryOptimizationSettings) {
    AutoClickerModule.openBatteryOptimizationSettings();
  }
}

// ─── Notification Permission (Android 13+) ────────────────────────────────────

export async function requestNotificationPermission() {
  if (Platform.OS !== 'android') return true;
  try {
    const { PermissionsAndroid } = await import('react-native');
    if (Platform.Version < 33) return true;
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
    return result === PermissionsAndroid.RESULTS.GRANTED;
  } catch {
    return false;
  }
}

// ─── All-in-one status check ─────────────────────────────────────────────────

export async function getAllPermissionsStatus() {
  const fallbackShizuku = {
    installed: false, running: false, permission: false, ready: false, state: 'missing',
  };
  try {
    const [accessibility, notificationListener, battery, overlay, shizuku] = await Promise.all([
      isAccessibilityEnabled().catch(() => false),
      isNotificationListenerEnabled().catch(() => false),
      isBatteryOptimizationIgnored().catch(() => false),
      isOverlayPermissionGranted().catch(() => false),
      getShizukuStatus().catch(() => fallbackShizuku),
    ]);
    return {
      accessibility: !!accessibility,
      notificationListener: !!notificationListener,
      battery: !!battery,
      overlay: !!overlay,
      shizuku: !!shizuku?.ready,
      shizukuInstalled: !!shizuku?.installed,
      shizukuRunning: !!shizuku?.running,
      shizukuPermission: !!shizuku?.permission,
      shizukuState: shizuku?.state || 'missing',
    };
  } catch {
    return {
      accessibility: false,
      notificationListener: false,
      battery: false,
      overlay: false,
      shizuku: false,
      shizukuInstalled: false,
      shizukuRunning: false,
      shizukuPermission: false,
      shizukuState: 'missing',
    };
  }
}
