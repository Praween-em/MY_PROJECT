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

test('allows clearing the Telegram URL', () => {
  if (normalizeTelegramUrl('') !== '') throw new Error('URL was not cleared');
});

test('rejects non-Telegram host', () => {
  expectThrow(() => normalizeTelegramUrl('https://example.com/pay'));
});

test('rejects insecure Telegram URL', () => {
  expectThrow(() => normalizeTelegramUrl('http://t.me/ag_rider_payments'));
});

test('supports the documented image formats', () => {
  for (const mime of ['image/png', 'image/jpeg', 'image/webp']) {
    if (!ALLOWED_IMAGE_TYPES.has(mime)) throw new Error(`Missing ${mime}`);
  }
});

test('limits images to 2 MB', () => {
  if (MAX_IMAGE_BYTES !== 2 * 1024 * 1024) throw new Error(String(MAX_IMAGE_BYTES));
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
