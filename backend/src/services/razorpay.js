/**
 * razorpay.js — Razorpay order creation and signature verification
 */

const crypto = require('crypto');
const { getRazorpay } = require('../config/razorpay');
const { normalizePhone } = require('../utils/phone');

const PLAN_AMOUNTS = {
  monthly: 29900,
  quarterly: 67500,
};

const VALID_PLANS = Object.keys(PLAN_AMOUNTS);

function verifyPaymentSignature({ razorpayOrderId, razorpayPaymentId, razorpaySignature }) {
  const secret = process.env.RAZORPAY_KEY_SECRET;
  if (!secret) throw new Error('Razorpay secret not configured');

  const body = `${razorpayOrderId}|${razorpayPaymentId}`;
  const expected = crypto.createHmac('sha256', secret).update(body).digest('hex');

  if (expected !== razorpaySignature) {
    const err = new Error(
      'Invalid payment signature — check RAZORPAY_KEY_SECRET matches your live/test Key ID on Railway'
    );
    err.code = 'INVALID_SIGNATURE';
    throw err;
  }
  return true;
}

function verifyWebhookSignature(rawBody, signature) {
  const secret = process.env.RAZORPAY_WEBHOOK_SECRET;
  if (!secret || !rawBody || !signature) return false;

  const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
  return expected === signature;
}

async function createOrder(planId, phone) {
  const amount = PLAN_AMOUNTS[planId];
  if (!amount) throw new Error('Invalid planId');

  const normalizedPhone = normalizePhone(phone);
  if (!normalizedPhone) throw new Error('Valid 10-digit phone required');

  const order = await getRazorpay().orders.create({
    amount,
    currency: 'INR',
    receipt: `sr_${normalizedPhone}_${Date.now()}`,
    notes: { phone: normalizedPhone, planId },
  });

  return {
    orderId: order.id,
    amount: order.amount,
    currency: order.currency,
  };
}

async function getOrderNotes(orderId) {
  const order = await getRazorpay().orders.fetch(orderId);
  return {
    phone: order.notes?.phone ? normalizePhone(order.notes.phone) : null,
    planId: order.notes?.planId || null,
  };
}

async function fetchPayment(paymentId) {
  return getRazorpay().payments.fetch(paymentId);
}

module.exports = {
  PLAN_AMOUNTS,
  VALID_PLANS,
  createOrder,
  verifySignature: verifyPaymentSignature,
  verifyWebhookSignature,
  getOrderNotes,
  fetchPayment,
};
