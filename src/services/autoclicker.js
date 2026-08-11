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

export function getServiceHealth() {
  return AutoClickerModule?.getServiceHealth() ?? unavailable();
}

export function setMinPrice(price) {
  return AutoClickerModule?.setMinPrice(price) ?? unavailable();
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

export function saveSettings({ enabled, delayMs, monitoredPackages, nuclearMode }) {
  if (!AutoClickerModule) return unavailable();
  const nuclear = nuclearMode !== false;
  // minPrice omitted — UI removed; native getMinPrice() always returns 0
  return Promise.all([
    AutoClickerModule.setServiceEnabled(!!enabled),
    AutoClickerModule.setNuclearMode(nuclear),
    // Nuclear always 0; Standard can use a small delay if set
    AutoClickerModule.setDelayMs(nuclear ? 0 : (delayMs ?? 0)),
    AutoClickerModule.setMonitoredPackages(monitoredPackages),
  ]);
}

export function onRideAccepted(callback) {
  if (!emitter) return () => {};
  const sub = emitter.addListener('onRideAccepted', callback);
  return () => sub.remove();
}

/** Rapido Captain / driver app package ids. */
export const APP_PACKAGES = {
  rapido: 'com.rapido.rider',
  captain: 'com.rapido.captain',
  driver: 'com.rapido.driver',
};

export function packageLabel(packageName) {
  switch (packageName) {
    case APP_PACKAGES.rapido:
    case APP_PACKAGES.captain:
    case APP_PACKAGES.driver:
      return 'Rapido';
    default:
      return packageName?.split('.').pop() || 'App';
  }
}
