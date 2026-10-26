import { Linking, Platform } from 'react-native';

const UPI_PACKAGES = {
  phonepe: 'com.phonepe.app',
  paytm: 'net.one97.paytm',
  gpay: 'com.google.android.apps.nbu.paisa.user',
};

export function formatUpiAmount(paise) {
  const n = Number(paise);
  if (!Number.isFinite(n) || n <= 0) return '';
  return (n / 100).toFixed(2);
}

export function buildUpiQuery({ pa, pn, am, tn }) {
  return [
    ['pa', pa],
    ['pn', pn || 'AG Rider'],
    ['am', am],
    ['cu', 'INR'],
    ['tn', tn],
  ]
    .filter(([, value]) => value)
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&');
}

export async function openUpiApp(app, params) {
  const query = buildUpiQuery(params);
  const generic = `upi://pay?${query}`;
  const packageName = UPI_PACKAGES[app];
  if (Platform.OS === 'android' && packageName) {
    const intent = `intent://pay?${query}#Intent;scheme=upi;package=${packageName};end`;
    try {
      await Linking.openURL(intent);
      return;
    } catch {
      // That app is missing. The generic UPI link opens the chooser.
    }
  }
  await Linking.openURL(generic);
}

export function paymentMessage({ planLabel, price, phone }) {
  return `I have paid for ${planLabel} (${price}). My number is ${phone}. Please verify and activate my subscription.`;
}

export function whatsAppPayUrl(groupUrl, text) {
  const encoded = encodeURIComponent(text);
  const raw = String(groupUrl || '').trim();
  const fromLink = raw.match(/wa\.me\/(\d{10,15})/i);
  const digits = raw.replace(/\D/g, '');
  const phone = fromLink?.[1]
    || (/^\+?\d[\d\s-]{8,}$/.test(raw) && digits.length >= 10 && digits.length <= 15 ? digits : '');
  if (phone) return `https://wa.me/${phone}?text=${encoded}`;
  return `https://wa.me/?text=${encoded}`;
}

export function isWhatsAppGroupInvite(url) {
  return /chat\.whatsapp\.com\//i.test(String(url || ''));
}
