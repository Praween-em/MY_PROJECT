/**
 * deviceId.js — stable per-install device identity for single-device lock
 */

import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = '@playnix/device_id';

function createId() {
  const rand = Math.random().toString(36).slice(2, 10);
  const t = Date.now().toString(36);
  return `sr_${t}_${rand}`;
}

export async function getDeviceId() {
  try {
    let id = await AsyncStorage.getItem(KEY);
    if (!id || id.length < 8) {
      id = createId();
      await AsyncStorage.setItem(KEY, id);
    }
    return id;
  } catch {
    return createId();
  }
}

export async function getDeviceLabel() {
  try {
    // Lightweight label without extra native deps
    return 'android-app';
  } catch {
    return null;
  }
}
