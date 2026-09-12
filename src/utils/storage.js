/**
 * storage.js
 * AsyncStorage wrappers for persisting user + subscription data locally.
 * Install: npx expo install @react-native-async-storage/async-storage
 */

import AsyncStorage from '@react-native-async-storage/async-storage';

const KEYS = {
  USER: '@superridex/user',
};

/**
 * Save user object to local storage.
 * Shape: { phone, subscriptionStart, subscriptionEnd, planType, active }
 */
export async function saveUser(user) {
  try {
    await AsyncStorage.setItem(KEYS.USER, JSON.stringify(user));
  } catch (err) {
    console.warn('saveUser failed:', err?.message || err);
  }
}

/**
 * Retrieve persisted user object. Returns null if none exists.
 */
export async function getStoredUser() {
  try {
    const raw = await AsyncStorage.getItem(KEYS.USER);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Clear user data (on logout).
 */
export async function clearUser() {
  try {
    await AsyncStorage.removeItem(KEYS.USER);
  } catch {
    /* ignore */
  }
}

/**
 * Quick helper — check if a user session exists locally.
 */
export async function hasSession() {
  const user = await getStoredUser();
  return !!user?.phone;
}
