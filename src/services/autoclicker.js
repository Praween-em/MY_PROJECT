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

/** Authoritative native lifecycle state; the UI must not infer this itself. */
export function getAutomationStatus() {
  return AutoClickerModule?.getAutomationStatus?.() ?? getServiceStatus();
}

/** Atomically persist and apply a complete engine configuration revision. */
export function applyAutomationConfig(config) {
  if (!AutoClickerModule?.applyAutomationConfig) return unavailable();
  const payload = {
    enabled: !!config?.enabled,
    minPrice: Math.max(0, Number(config?.minPrice) || 0),
    maxPrice: Math.max(0, Number(config?.maxPrice) || 0),
    minPickup: Math.max(0, Number(config?.minPickup) || 0),
    maxPickup: Math.max(0, Number(config?.maxPickup) || 0),
    maxDrop: Math.max(0, Number(config?.maxDrop) || 0),
    monitoredPackages: Array.isArray(config?.monitoredPackages)
      ? config.monitoredPackages
      : [],
  };
  if (config?.entitlement) payload.entitlement = config.entitlement;
  return AutoClickerModule.applyAutomationConfig(payload);
}

export function updateEntitlement(entitlement) {
  if (!AutoClickerModule?.updateEntitlement) return unavailable();
  return AutoClickerModule.updateEntitlement({
    active: !!entitlement?.active,
    deviceAllowed: entitlement?.deviceAllowed !== false,
    subscriptionEndMs: Math.max(0, Number(entitlement?.subscriptionEndMs) || 0),
  });
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
  return applyAutomationConfig({
    enabled,
    monitoredPackages,
    minPrice,
    maxPrice: 0,
    minPickup: 0,
    maxPickup,
    maxDrop: 0,
  });
}

export function onRideAccepted(callback) {
  if (!emitter) return () => {};
  const sub = emitter.addListener('onRideAccepted', callback);
  return () => sub.remove();
}

export function onAutomationStatusChanged(callback) {
  if (!emitter) return () => {};
  const sub = emitter.addListener('onAutomationStatusChanged', callback);
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
