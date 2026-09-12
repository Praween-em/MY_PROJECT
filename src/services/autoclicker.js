/**
 * autoclicker.js
 * JavaScript bridge to the native AutoClickerModule (Android only).
 */

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { AutoClickerModule } = NativeModules;

const emitter =
  Platform.OS === 'android' && AutoClickerModule
    ? new NativeEventEmitter(AutoClickerModule)
    : null;

function unavailable() {
  return Promise.reject(new Error('AutoClickerModule is only available on Android'));
}

export function isNativeAvailable() {
  return Platform.OS === 'android' && !!AutoClickerModule;
}

export function setServiceEnabled(enabled) {
  return AutoClickerModule?.setServiceEnabled(enabled) ?? unavailable();
}

export function getServiceStatus() {
  return AutoClickerModule?.getServiceStatus() ?? unavailable();
}

export function setMinPrice(price) {
  return AutoClickerModule?.setMinPrice(price) ?? unavailable();
}

export function setMaxPickup(km) {
  return AutoClickerModule?.setMaxPickup(km) ?? unavailable();
}

export function setDelayMs(delay) {
  return AutoClickerModule?.setDelayMs(delay) ?? unavailable();
}

export function setNuclearMode(enabled) {
  return AutoClickerModule?.setNuclearMode(enabled) ?? unavailable();
}

export function setContinuousForegroundTap(enabled) {
  return AutoClickerModule?.setContinuousForegroundTap(enabled) ?? unavailable();
}

export function setMonitoredPackages(packages) {
  return AutoClickerModule?.setMonitoredPackages(packages) ?? unavailable();
}

export function saveSettings({
  enabled,
  monitoredPackages,
  minPrice,
  maxPickup,
}) {
  if (!AutoClickerModule) return unavailable();
  const fare = Math.max(0, Number(minPrice) || 0);
  const pickup = Math.max(0, Number(maxPickup) || 0);
  return Promise.all([
    AutoClickerModule.setServiceEnabled(!!enabled),
    AutoClickerModule.setNuclearMode(true),
    AutoClickerModule.setDelayMs(0),
    AutoClickerModule.setMinPrice(fare),
    AutoClickerModule.setMaxPickup?.(pickup) ?? Promise.resolve(pickup),
    AutoClickerModule.setMonitoredPackages(monitoredPackages),
  ]);
}

export function onRideAccepted(callback) {
  if (!emitter) return () => {};
  const sub = emitter.addListener('onRideAccepted', callback);
  return () => sub.remove();
}

export function getShizukuStatus() {
  return AutoClickerModule?.getShizukuStatus?.() ?? Promise.resolve({
    installed: false, running: false, permission: false, ready: false, state: 'missing',
  });
}

export function requestShizukuPermission() {
  return AutoClickerModule?.requestShizukuPermission?.() ?? Promise.resolve(false);
}

export function openShizukuApp() {
  if (AutoClickerModule?.openShizukuApp) AutoClickerModule.openShizukuApp();
}

export function openShizukuPlayStore() {
  if (AutoClickerModule?.openShizukuPlayStore) AutoClickerModule.openShizukuPlayStore();
}

/** Monitored driver-app package ids (internal — do not show names in UI). */
export const APP_PACKAGES = {
  ola: 'com.olacabs.oladriver',
  rapido: 'com.rapido.rider',
};

/** Generic label for history — never expose partner brand names in UI. */
export function packageLabel(packageName) {
  if (!packageName) return 'Ride';
  return 'Driver app';
}
