import AsyncStorage from '@react-native-async-storage/async-storage';
import { packageLabel } from '../services/autoclicker';

const KEY = '@superridex/ride_history';
const MAX = 200;

function formatTime(ts) {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function formatDate(ts) {
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);

  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();

  if (sameDay(d, today)) return 'Today';
  if (sameDay(d, yesterday)) return 'Yesterday';

  const dd = String(d.getDate()).padStart(2, '0');
  const mo = String(d.getMonth() + 1).padStart(2, '0');
  const yyyy = d.getFullYear();
  return `${dd}/${mo}/${yyyy}`;
}

function normalizeRide(ride) {
  const ts = ride?.timestamp || Date.now();
  return {
    ...ride,
    time: ride?.time || formatTime(ts),
    date: ride?.date || formatDate(ts),
  };
}

export async function getRideHistory() {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    const list = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(list)) return [];
    return list
      .map(normalizeRide)
      .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  } catch {
    return [];
  }
}

export async function addRideAccepted(event) {
  const ts = Number(event?.timestamp) || Date.now();
  const packageName = event?.packageName || '';
  const mode = event?.mode === 'Nuclear' ? 'Nuclear' : 'Standard';
  const latencyMs =
    typeof event?.latencyMs === 'number' && event.latencyMs >= 0
      ? event.latencyMs
      : null;
  const price =
    typeof event?.price === 'number' && event.price > 0
      ? event.price
      : null;

  const ride = {
    id: `${ts}-${packageName || 'app'}-${Math.random().toString(36).slice(2, 7)}`,
    app: packageLabel(packageName) || 'App',
    packageName,
    tag: mode,
    ms: latencyMs,
    price,
    label: event?.label || '',
    time: formatTime(ts),
    date: formatDate(ts),
    timestamp: ts,
    status: 'Accepted',
  };

  const prev = await getRideHistory();

  // Drop near-duplicate emits (same package within 2s)
  const isDupe = prev.some(
    (r) =>
      r.packageName === packageName &&
      Math.abs((r.timestamp || 0) - ts) < 2000
  );
  if (isDupe) return prev[0];

  const next = [ride, ...prev].slice(0, MAX);
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return ride;
}

export async function clearRideHistory() {
  await AsyncStorage.removeItem(KEY);
}
