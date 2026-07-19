/**
 * api.js
 * All HTTP communication with the AWS backend.
 * Base URL is read from environment / config.
 */

const BASE_URL = process.env.EXPO_PUBLIC_API_URL || 'https://api.playnix.in';

async function request(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const data = await res.json();
  if (!res.ok) throw new Error(data.message || 'Request failed');
  return data;
}

// ─── Subscription ────────────────────────────────────────────────────────────

/**
 * Create a Razorpay order on the backend.
 * Returns { orderId, amount, currency }
 */
export async function createOrder(planId, phone) {
  return request('POST', '/subscription/create-order', { planId, phone });
}

/**
 * Verify payment signature after Razorpay checkout succeeds.
 * Returns { success: true, subscriptionEnd }
 */
export async function verifyPayment({ razorpayOrderId, razorpayPaymentId, razorpaySignature, phone, planId }) {
  return request('POST', '/subscription/verify-payment', {
    razorpayOrderId,
    razorpayPaymentId,
    razorpaySignature,
    phone,
    planId,
  });
}

/**
 * Check subscription status for a phone number.
 * Returns { active: bool, subscriptionStart, subscriptionEnd, planType }
 */
export async function getSubscriptionStatus(phone) {
  return request('GET', `/subscription/status?phone=${encodeURIComponent(phone)}`);
}

// ─── Referral ────────────────────────────────────────────────────────────────

export async function getReferralStatus(phone) {
  return request('GET', `/referral/status?phone=${encodeURIComponent(phone)}`);
}

export async function applyReferralCode(phone, code) {
  return request('POST', '/referral/apply', { phone, code });
}

export async function registerUser(phone, referralCode) {
  return request('POST', '/referral/register', { phone, referralCode });
}
