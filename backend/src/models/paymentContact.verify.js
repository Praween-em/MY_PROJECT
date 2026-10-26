const {
  normalizeTelegramUrl,
  ALLOWED_IMAGE_TYPES,
  MAX_IMAGE_BYTES,
} = require('./paymentContact');

let passed = 0;
let failed = 0;

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`  ✓ ${name}`);
  } catch (err) {
    failed++;
    console.error(`  ✗ ${name}: ${err.message}`);
  }
}

function expectThrow(fn) {
  let threw = false;
  try {
    fn();
  } catch {
    threw = true;
  }
  if (!threw) throw new Error('Expected function to throw');
}

console.log('Payment contact verification\n');

test('accepts t.me HTTPS URL', () => {
  const value = normalizeTelegramUrl('https://t.me/ag_rider_payments');
  if (value !== 'https://t.me/ag_rider_payments') throw new Error(value);
});

test('accepts telegram.me HTTPS URL', () => {
  normalizeTelegramUrl('https://telegram.me/ag_rider_payments');
});

test('accepts a bare t.me link', () => {
  const value = normalizeTelegramUrl('t.me/ag_rider_payments');
  if (value !== 'https://t.me/ag_rider_payments') throw new Error(value);
});

test('accepts an @username', () => {
  const value = normalizeTelegramUrl('@ag_rider_payments');
  if (value !== 'https://t.me/ag_rider_payments') throw new Error(value);
});

test('allows clearing the Telegram URL', () => {
  if (normalizeTelegramUrl('') !== '') throw new Error('URL was not cleared');
});

test('rejects non-Telegram host', () => {
  expectThrow(() => normalizeTelegramUrl('https://example.com/pay'));
});

test('upgrades an http Telegram URL to https', () => {
  const value = normalizeTelegramUrl('http://t.me/ag_rider_payments');
  if (value !== 'https://t.me/ag_rider_payments') throw new Error(value);
});

test('supports the documented image formats', () => {
  for (const mime of ['image/png', 'image/jpeg', 'image/webp']) {
    if (!ALLOWED_IMAGE_TYPES.has(mime)) throw new Error(`Missing ${mime}`);
  }
});

test('limits images to 2 MB', () => {
  if (MAX_IMAGE_BYTES !== 2 * 1024 * 1024) throw new Error(String(MAX_IMAGE_BYTES));
});

test('accepts a UPI id', () => {
  const { normalizeUpiId } = require('./paymentContact');
  if (normalizeUpiId('Rider.Pay@oksbi') !== 'rider.pay@oksbi') {
    throw new Error('UPI was not normalized');
  }
});

test('rejects a UPI id without a bank handle', () => {
  const { normalizeUpiId } = require('./paymentContact');
  expectThrow(() => normalizeUpiId('not-a-upi'));
});

test('accepts a WhatsApp group invite', () => {
  const { normalizeWhatsAppUrl } = require('./paymentContact');
  const value = normalizeWhatsAppUrl('https://chat.whatsapp.com/AbCdEf123');
  if (!value.startsWith('https://chat.whatsapp.com/')) throw new Error(value);
});

test('turns a 10-digit phone into wa.me', () => {
  const { normalizeWhatsAppUrl } = require('./paymentContact');
  const value = normalizeWhatsAppUrl('9876543210');
  if (value !== 'https://wa.me/919876543210') throw new Error(value);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
