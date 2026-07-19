/**
 * Validates .env has real Razorpay test keys (not placeholders).
 * Run: npm run env:check
 */
require('dotenv').config();

const placeholders = /XXXX|paste|your_/i;
const keyId = process.env.EXPO_PUBLIC_RAZORPAY_KEY_ID || '';
const apiUrl = process.env.EXPO_PUBLIC_API_URL || '';
const mode = process.env.EXPO_PUBLIC_RAZORPAY_MODE || 'test';

let ok = true;

function check(label, value, test) {
  if (!test(value)) {
    console.error(`  ✗ ${label}`);
    ok = false;
  } else {
    console.log(`  ✓ ${label}`);
  }
}

console.log('App environment check\n');

check('EXPO_PUBLIC_API_URL set', apiUrl, (v) => v.length > 0 && !placeholders.test(v));
check('EXPO_PUBLIC_RAZORPAY_MODE', mode, (v) => v === 'test' || v === 'live');
check(
  'EXPO_PUBLIC_RAZORPAY_KEY_ID format',
  keyId,
  (v) => !placeholders.test(v) && (v.startsWith('rzp_test_') || v.startsWith('rzp_live_'))
);

if (mode === 'test' && keyId.startsWith('rzp_live_')) {
  console.warn('  ⚠ Mode is test but key looks live — double-check .env');
}
if (mode === 'live' && keyId.startsWith('rzp_test_')) {
  console.warn('  ⚠ Mode is live but key is test — payments will stay in test mode');
}

console.log(ok ? '\nReady.' : '\nEdit .env with your Razorpay test keys, then rebuild the app.');
process.exit(ok ? 0 : 1);
