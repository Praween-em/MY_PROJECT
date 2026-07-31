/**
 * paymentActivation.js — shared verify + activate logic for app, webhook, admin reconcile
 */

const { normalizePhone } = require('../utils/phone');
const {
  verifySignature,
  getOrderNotes,
  fetchPayment,
  VALID_PLANS,
  PLAN_AMOUNTS,
} = require('./razorpay');
const { activateSubscription } = require('../models/user');

/**
 * After Razorpay checkout — verify HMAC signature and activate subscription.
 */
async function activateFromCheckout({
  razorpayOrderId,
  razorpayPaymentId,
  razorpaySignature,
  phone,
  planId,
  amount = null,
}) {
  if (!razorpayOrderId || !razorpayPaymentId || !razorpaySignature) {
    const err = new Error('Missing payment fields');
    err.status = 400;
    throw err;
  }

  verifySignature({ razorpayOrderId, razorpayPaymentId, razorpaySignature });

  const notes = await getOrderNotes(razorpayOrderId);
  const orderPhone = normalizePhone(notes.phone);
  const reqPhone = normalizePhone(phone);

  if (orderPhone && reqPhone && orderPhone !== reqPhone) {
    const err = new Error('Phone does not match order');
    err.status = 400;
    throw err;
  }

  const resolvedPhone = orderPhone || reqPhone;
  if (!resolvedPhone) {
    const err = new Error('Could not resolve phone for this payment');
    err.status = 400;
    throw err;
  }

  const resolvedPlan = VALID_PLANS.includes(notes.planId)
    ? notes.planId
    : VALID_PLANS.includes(planId)
      ? planId
      : null;

  if (!resolvedPlan) {
    const err = new Error('Invalid planId');
    err.status = 400;
    throw err;
  }

  const resolvedAmount = amount ?? PLAN_AMOUNTS[resolvedPlan] ?? null;

  const result = await activateSubscription(resolvedPhone, {
    planId: resolvedPlan,
    razorpayOrderId,
    razorpayPaymentId,
    amount: resolvedAmount,
  });

  return { ...result, phone: resolvedPhone, planId: resolvedPlan };
}

/**
 * Webhook / admin — activate from a captured Razorpay payment id (no client signature).
 */
async function activateFromPaymentId(paymentId, phoneOverride = null) {
  const payment = await fetchPayment(paymentId);
  if (!payment?.id) {
    const err = new Error('Payment not found on Razorpay');
    err.status = 404;
    throw err;
  }
  if (payment.status !== 'captured') {
    const err = new Error(`Payment status is "${payment.status}", expected captured`);
    err.status = 400;
    throw err;
  }

  const orderId = payment.order_id;
  if (!orderId) {
    const err = new Error('Payment has no order_id');
    err.status = 400;
    throw err;
  }

  const notes = await getOrderNotes(orderId);
  const phone = normalizePhone(phoneOverride) || normalizePhone(notes.phone);
  if (!phone) {
    const err = new Error('Phone not found on order — pass phone in request body');
    err.status = 400;
    throw err;
  }

  const planId = notes.planId;
  if (!VALID_PLANS.includes(planId)) {
    const err = new Error(`Invalid or missing plan on order (got: ${planId || 'none'})`);
    err.status = 400;
    throw err;
  }

  const result = await activateSubscription(phone, {
    planId,
    razorpayOrderId: orderId,
    razorpayPaymentId: payment.id,
    amount: payment.amount ?? PLAN_AMOUNTS[planId] ?? null,
  });

  return { ...result, phone, planId };
}

module.exports = {
  activateFromCheckout,
  activateFromPaymentId,
};
