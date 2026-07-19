/**
 * razorpay.js — Razorpay order creation and signature verification
 */

const crypto = require('crypto');
const razorpay = require('../config/razorpay');

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
    throw new Error('Invalid payment signature');
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

  const order = await razorpay.orders.create({
    amount,
    currency: 'INR',
    receipt: `pc_${phone}_${Date.now()}`,
    notes: { phone, planId },
  });

  return {
    orderId: order.id,
    amount: order.amount,
    currency: order.currency,
  };
}

async function getOrderNotes(orderId) {
  const order = await razorpay.orders.fetch(orderId);
  return {
    phone: order.notes?.phone || null,
    planId: order.notes?.planId || null,
  };
}

module.exports = {
  PLAN_AMOUNTS,
  VALID_PLANS,
  createOrder,
  verifySignature: verifyPaymentSignature,
  verifyWebhookSignature,
  getOrderNotes,
};
