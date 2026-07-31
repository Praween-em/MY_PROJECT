/**
 * payment.js
 * Razorpay checkout integration.
 *
 * Install: npx expo install react-native-razorpay
 * (requires bare workflow — already the case with npx expo run:android)
 */

import { createOrder, verifyPayment } from './api';
import Constants from 'expo-constants';

// react-native-razorpay is imported at call-time to avoid crashing on
// platforms where the native module isn't available (e.g., web preview).
let RazorpayCheckout = null;
async function getRazorpay() {
  if (!RazorpayCheckout) {
    const mod = await import('react-native-razorpay');
    RazorpayCheckout = mod.default;
  }
  return RazorpayCheckout;
}

const RAZORPAY_KEY_ID =
  Constants.expoConfig?.extra?.razorpayKeyId ||
  process.env.EXPO_PUBLIC_RAZORPAY_KEY_ID ||
  'rzp_test_XXXXXXXXXXXXXXXX';

/** Normalize Razorpay SDK response (Android/iOS field names differ slightly). */
function extractPaymentFields(paymentData) {
  if (!paymentData || typeof paymentData !== 'object') {
    throw new Error('Empty payment response from Razorpay');
  }
  const razorpayOrderId =
    paymentData.razorpay_order_id ||
    paymentData.order_id ||
    paymentData.metadata?.order_id;
  const razorpayPaymentId =
    paymentData.razorpay_payment_id ||
    paymentData.payment_id;
  const razorpaySignature =
    paymentData.razorpay_signature ||
    paymentData.signature;

  if (!razorpayOrderId || !razorpayPaymentId || !razorpaySignature) {
    throw new Error(
      'Payment succeeded but verification data was incomplete. ' +
      'Contact support with your payment ID — your subscription can be activated manually.'
    );
  }
  return { razorpayOrderId, razorpayPaymentId, razorpaySignature };
}

/** Strip +91 / spaces — must match backend normalizePhone. */
function normalizePhone(raw) {
  const digits = String(raw || '').replace(/\D/g, '');
  if (digits.length >= 10) return digits.slice(-10);
  return raw;
}

/**
 * planConfig maps plan IDs to display names and amounts (in paise).
 */
export const PLAN_CONFIG = {
  monthly:   { label: '1 Month',   amount: 29900, description: '30-day access to Super Ridex' },
  quarterly: { label: '3 Months',  amount: 67500, description: '90-day access to Super Ridex' },
};

/**
 * openCheckout
 * Full Razorpay checkout flow:
 *  1. Backend creates order → returns orderId
 *  2. Razorpay SDK opens payment sheet
 *  3. On success, backend verifies signature → returns subscriptionEnd
 *
 * @param {string} planId   - 'weekly' | 'monthly' | 'quarterly'
 * @param {string} phone    - Driver's phone number (used as userId)
 * @returns {Promise<{ subscriptionEnd: string }>}
 */
export async function openCheckout(planId, phone) {
  const plan = PLAN_CONFIG[planId];
  if (!plan) throw new Error(`Unknown plan: ${planId}`);

  const normalizedPhone = normalizePhone(phone);
  if (!normalizedPhone || String(normalizedPhone).replace(/\D/g, '').length < 10) {
    throw new Error('Valid 10-digit phone required');
  }

  // Step 1: create backend order
  const { orderId, amount, currency } = await createOrder(planId, normalizedPhone);

  // Step 2: open Razorpay sheet
  const Razorpay = await getRazorpay();
  const paymentData = await Razorpay.open({
    key: RAZORPAY_KEY_ID,
    order_id: orderId,
    amount,
    currency: currency || 'INR',
    name: 'SUPER RIDEX',
    description: plan.description,
    prefill: { contact: normalizedPhone },
    theme: { color: '#00FF7F' },
  });

  const fields = extractPaymentFields(paymentData);

  // Step 3: verify on backend
  const result = await verifyPayment({
    ...fields,
    phone: normalizedPhone,
    planId,
  });

  return result; // { success: true, subscriptionEnd }
}

/**
 * Returns true when the user dismissed the Razorpay sheet without paying.
 */
export function isPaymentCancelled(err) {
  return err?.code === 0 || /cancel/i.test(err?.description || err?.message || '');
}
