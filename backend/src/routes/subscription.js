/**
 * subscription.js — subscription routes
 */

const express = require('express');
const router = express.Router();
const { requirePhone } = require('../middleware/auth');
const { createOrder, verifySignature, getOrderNotes, VALID_PLANS } = require('../services/razorpay');
const { ensureUser, activateSubscription, getUserByPhone } = require('../models/user');

router.post('/create-order', requirePhone, async (req, res) => {
  try {
    const { planId } = req.body;
    if (!VALID_PLANS.includes(planId)) {
      return res.status(400).json({ message: 'Invalid planId' });
    }

    const order = await createOrder(planId, req.phone);

    try {
      await ensureUser(req.phone);
    } catch (dbErr) {
      console.error('ensureUser after create-order:', dbErr);
      return res.status(503).json({
        message: 'Order created but user database unavailable. Configure AWS credentials or deploy backend.',
        orderId: order.orderId,
        amount: order.amount,
        currency: order.currency,
      });
    }

    res.json(order);
  } catch (err) {
    console.error('create-order error:', err);
    res.status(500).json({ message: err.message });
  }
});

router.post('/verify-payment', requirePhone, async (req, res) => {
  try {
    const { razorpayOrderId, razorpayPaymentId, razorpaySignature, planId } = req.body;

    if (!razorpayOrderId || !razorpayPaymentId || !razorpaySignature) {
      return res.status(400).json({ message: 'Missing payment fields' });
    }

    verifySignature({ razorpayOrderId, razorpayPaymentId, razorpaySignature });

    const notes = await getOrderNotes(razorpayOrderId);
    if (notes.phone && notes.phone !== req.phone) {
      return res.status(400).json({ message: 'Phone does not match order' });
    }

    const resolvedPlan = VALID_PLANS.includes(notes.planId)
      ? notes.planId
      : VALID_PLANS.includes(planId)
        ? planId
        : null;

    if (!resolvedPlan) {
      return res.status(400).json({ message: 'Invalid planId' });
    }

    const { subscriptionEnd, referralResult, alreadyProcessed } = await activateSubscription(
      req.phone,
      { planId: resolvedPlan, razorpayOrderId, razorpayPaymentId }
    );

    res.json({ success: true, subscriptionEnd, referralResult, alreadyProcessed });
  } catch (err) {
    console.error('verify-payment error:', err);
    const status = err.message === 'Invalid payment signature' ? 400 : 500;
    res.status(status).json({ message: err.message });
  }
});

router.get('/status', requirePhone, async (req, res) => {
  try {
    const user = await getUserByPhone(req.phone);

    if (!user || !user.subscriptionEnd) {
      return res.json({ active: false });
    }

    const active = new Date(user.subscriptionEnd) > new Date();
    res.json({
      active,
      subscriptionStart: user.subscriptionStart,
      subscriptionEnd: user.subscriptionEnd,
      planType: user.planType,
    });
  } catch (err) {
    console.error('status error:', err);
    res.status(500).json({ message: err.message });
  }
});

module.exports = router;
