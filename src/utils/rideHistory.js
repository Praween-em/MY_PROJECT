import AsyncStorage from '@react-native-async-storage/async-storage';
import { packageLabel } from '../services/autoclicker';

const KEY = '@playnix/ride_history';
const MAX = 100;

function formatTime(ts) {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

export async function getRideHistory() {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

export async function addRideAccepted(event) {
  const ts = event?.timestamp || Date.now();
  const ride = {
    id: `${ts}-${Math.random().toString(36).slice(2, 7)}`,
    amount: event?.price > 0 ? event.price : 0,
    app: packageLabel(event?.packageName),
    packageName: event?.packageName || '',
    tag: event?.mode === 'Nuclear' ? 'Nuclear' : 'Standard',
    price: event?.price > 0 ? event.price : 0,
    target: 0,
    ms: typeof event?.latencyMs === 'number' && event.latencyMs >= 0 ? event.latencyMs : null,
    label: event?.label || '',
    time: formatTime(ts),
    timestamp: ts,
  };

  const prev = await getRideHistory();
  const next = [ride, ...prev].slice(0, MAX);
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return ride;
}

export async function clearRideHistory() {
  await AsyncStorage.removeItem(KEY);
}
