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
  await AsyncStorage.setItem(KEYS.USER, JSON.stringify(user));
}

/**
 * Retrieve persisted user object. Returns null if none exists.
 */
export async function getStoredUser() {
  const raw = await AsyncStorage.getItem(KEYS.USER);
  return raw ? JSON.parse(raw) : null;
}

/**
 * Clear user data (on logout).
 */
export async function clearUser() {
  await AsyncStorage.removeItem(KEYS.USER);
}

/**
 * Quick helper — check if a user session exists locally.
 */
export async function hasSession() {
  const user = await getStoredUser();
  return !!user?.phone;
}
