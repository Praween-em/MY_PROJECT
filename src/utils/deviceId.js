/**
 * deviceId.js — stable per-install identity for device lock.
 *
 * Important: never mint a new id on every call (AsyncStorage races / catch paths
 * were creating ghost devices and triggering false "another device" errors).
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';

const KEY = '@superridex/device_id';

let memoryId = null;
let inflight = null;

function createId() {
  const rand = Math.random().toString(36).slice(2, 12);
  const t = Date.now().toString(36);
  return `sr_${Platform.OS || 'app'}_${t}_${rand}`;
}

/**
 * Single-flight + memory cache so parallel API calls share one id.
 */
export async function getDeviceId() {
  if (memoryId && memoryId.length >= 8) return memoryId;
  if (inflight) return inflight;

  inflight = (async () => {
    try {
      let id = await AsyncStorage.getItem(KEY);
      if (id) id = String(id).trim();
      if (!id || id.length < 8) {
        id = createId();
        await AsyncStorage.setItem(KEY, id);
      }
      memoryId = id;
      return id;
    } catch {
      // Persist best-effort; keep process-stable id so we don't flood the backend
      if (!memoryId) memoryId = createId();
      try {
        await AsyncStorage.setItem(KEY, memoryId);
      } catch {
        // ignore
      }
      return memoryId;
    } finally {
      inflight = null;
    }
  })();

  return inflight;
}

export async function getDeviceLabel() {
  return `${Platform.OS || 'app'}-superridex`;
}
