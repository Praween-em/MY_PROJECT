const Razorpay = require('razorpay');

let _client = null;

/** Lazy-init so dotenv / Railway env is loaded before first use. */
function getRazorpay() {
  if (!_client) {
    const key_id = process.env.RAZORPAY_KEY_ID;
    const key_secret = process.env.RAZORPAY_KEY_SECRET;
    if (!key_id || !key_secret) {
      throw new Error('Razorpay keys not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET)');
    }
    _client = new Razorpay({ key_id, key_secret });
  }
  return _client;
}

module.exports = { getRazorpay };
