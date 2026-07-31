/**
 * payment.js
 * Razorpay checkout integration.
 */

import { createOrder, verifyPayment } from './api';
import Constants from 'expo-constants';

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

function normalizePhone(raw) {
  const digits = String(raw || '').replace(/\D/g, '');
  if (digits.length >= 10) return digits.slice(-10);
  return raw;
}

/**
 * @param {string} planId
 * @param {string} phone
 * @param {{ description?: string, label?: string }} [planMeta] — from GET /subscription/plans
 */
export async function openCheckout(planId, phone, planMeta = {}) {
  const normalizedPhone = normalizePhone(phone);
  if (!normalizedPhone || String(normalizedPhone).replace(/\D/g, '').length < 10) {
    throw new Error('Valid 10-digit phone required');
  }

  const { orderId, amount, currency } = await createOrder(planId, normalizedPhone);

  const Razorpay = await getRazorpay();
  const paymentData = await Razorpay.open({
    key: RAZORPAY_KEY_ID,
    order_id: orderId,
    amount,
    currency: currency || 'INR',
    name: 'SUPER RIDEX',
    description: planMeta.description || planMeta.label || 'Super Ridex subscription',
    prefill: { contact: normalizedPhone },
    theme: { color: '#00FF7F' },
  });

  const fields = extractPaymentFields(paymentData);

  const result = await verifyPayment({
    ...fields,
    phone: normalizedPhone,
    planId,
  });

  return result;
}

export function isPaymentCancelled(err) {
  return err?.code === 0 || /cancel/i.test(err?.description || err?.message || '');
}
