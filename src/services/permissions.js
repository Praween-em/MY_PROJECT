/**
 * permissions.js
 * Helpers for checking and requesting critical Android permissions:
 *   1. Accessibility Service  — injects Accept taps
 *   2. Notification Listener  — dual-channel race (often earlier than a11y notifs)
 *   3. Display Over Other Apps (SYSTEM_ALERT_WINDOW)
 *   4. Battery Optimization Exemption
 *
 * All functions are no-ops on non-Android platforms.
 */

import { Platform, Linking, NativeModules } from 'react-native';

// Registered as 'AutoClickerModule' in AutoClickerPackage.java
const AutoClickerModule = NativeModules.AutoClickerModule || {};

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

// ─── Display Over Other Apps ──────────────────────────────────────────────────

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
  const { PermissionsAndroid } = await import('react-native');
  if (Platform.Version < 33) return true;
  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
  );
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

// ─── All-in-one status check ─────────────────────────────────────────────────

export async function getAllPermissionsStatus() {
  const [accessibility, notificationListener, overlay, battery] = await Promise.all([
    isAccessibilityEnabled(),
    isNotificationListenerEnabled(),
    isOverlayPermissionGranted(),
    isBatteryOptimizationIgnored(),
  ]);
  return { accessibility, notificationListener, overlay, battery };
}
