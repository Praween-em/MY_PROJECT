import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = '@superridex/settings';

const DEFAULTS = { delayMs: 0, nuclearMode: true };

export async function getSettings() {
  const raw = await AsyncStorage.getItem(KEY);
  return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : { ...DEFAULTS };
}

export async function saveSettings(partial) {
  const current = await getSettings();
  await AsyncStorage.setItem(KEY, JSON.stringify({ ...current, ...partial }));
}
