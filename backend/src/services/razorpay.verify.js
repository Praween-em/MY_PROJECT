/**
 * Razorpay signature verification — run: node src/services/razorpay.verify.js
 */
const crypto = require('crypto');

process.env.RAZORPAY_KEY_ID = process.env.RAZORPAY_KEY_ID || 'rzp_test_verify';
process.env.RAZORPAY_KEY_SECRET = 'test_key_secret';
process.env.RAZORPAY_WEBHOOK_SECRET = 'test_webhook_secret';

const { verifySignature, verifyWebhookSignature } = require('./razorpay');

let passed = 0;
let failed = 0;

function assert(label, condition) {
  if (condition) {
    passed++;
    console.log(`  ✓ ${label}`);
  } else {
    failed++;
    console.error(`  ✗ ${label}`);
  }
}

console.log('Razorpay signature verification\n');

const orderId = 'order_test123';
const paymentId = 'pay_test456';
const validSig = crypto
  .createHmac('sha256', process.env.RAZORPAY_KEY_SECRET)
  .update(`${orderId}|${paymentId}`)
  .digest('hex');

try {
  verifySignature({
    razorpayOrderId: orderId,
    razorpayPaymentId: paymentId,
    razorpaySignature: validSig,
  });
  assert('valid payment signature accepted', true);
} catch {
  assert('valid payment signature accepted', false);
}

try {
  verifySignature({
    razorpayOrderId: orderId,
    razorpayPaymentId: paymentId,
    razorpaySignature: 'bad_signature',
  });
  assert('invalid payment signature rejected', false);
} catch (e) {
  assert('invalid payment signature rejected', e.message === 'Invalid payment signature');
}

const webhookBody = JSON.stringify({ event: 'payment.captured' });
const validWebhookSig = crypto
  .createHmac('sha256', process.env.RAZORPAY_WEBHOOK_SECRET)
  .update(webhookBody)
  .digest('hex');

assert('valid webhook signature accepted', verifyWebhookSignature(webhookBody, validWebhookSig));
assert('invalid webhook signature rejected', !verifyWebhookSignature(webhookBody, 'bad'));

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed > 0 ? 1 : 0);
