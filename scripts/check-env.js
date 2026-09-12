/**
 * Validates the required public app environment.
 * Run: npm run env:check
 */
require('dotenv').config();

const placeholders = /XXXX|paste|your_/i;
const apiUrl = process.env.EXPO_PUBLIC_API_URL || '';

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

console.log(ok ? '\nReady.' : '\nEdit .env with the backend URL, then rebuild the app.');
process.exit(ok ? 0 : 1);
