/**
 * payment.js
 * Razorpay checkout integration.
 *
 * Install: npx expo install react-native-razorpay
 * (requires bare workflow — already the case with npx expo run:android)
 */

import { createOrder, verifyPayment } from './api';

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

const RAZORPAY_KEY_ID = process.env.EXPO_PUBLIC_RAZORPAY_KEY_ID || 'rzp_test_XXXXXXXXXXXXXXXX';

/**
 * planConfig maps plan IDs to display names and amounts (in paise).
 */
export const PLAN_CONFIG = {
  monthly:   { label: '1 Month',   amount: 29900, description: '30-day access to Super Rides' },
  quarterly: { label: '3 Months',  amount: 67500, description: '90-day access to Super Rides' },
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

  // Step 1: create backend order
  const { orderId, amount, currency } = await createOrder(planId, phone);

  // Step 2: open Razorpay sheet
  const Razorpay = await getRazorpay();
  const paymentData = await Razorpay.open({
    key: RAZORPAY_KEY_ID,
    order_id: orderId,
    amount,
    currency: currency || 'INR',
    name: 'Super Rides',
    description: plan.description,
    prefill: { contact: phone },
    theme: { color: '#00FF7F' },
  });

  // paymentData = { razorpay_order_id, razorpay_payment_id, razorpay_signature }

  // Step 3: verify on backend
  const result = await verifyPayment({
    razorpayOrderId: paymentData.razorpay_order_id,
    razorpayPaymentId: paymentData.razorpay_payment_id,
    razorpaySignature: paymentData.razorpay_signature,
    phone,
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
