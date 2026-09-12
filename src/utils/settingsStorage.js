import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = '@superridex/settings';

const DEFAULTS = {
  delayMs: 0,
  nuclearMode: true,
  minPrice: 0,
  maxPickup: 0,
};

export async function getSettings() {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    if (!raw) return { ...DEFAULTS };
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return { ...DEFAULTS };
    return { ...DEFAULTS, ...parsed };
  } catch {
    return { ...DEFAULTS };
  }
}

export async function saveSettings(partial) {
  try {
    const current = await getSettings();
    await AsyncStorage.setItem(KEY, JSON.stringify({ ...current, ...partial }));
  } catch (err) {
    console.warn('saveSettings failed:', err?.message || err);
  }
}
