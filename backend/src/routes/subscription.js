/**
 * subscription.js — subscription routes (Postgres)
 */

const express = require('express');
const router = express.Router();
const { requirePhone, optionalDevice, requireDevice } = require('../middleware/auth');
const { createOrder, verifySignature, getOrderNotes, VALID_PLANS } = require('../services/razorpay');
const { ensureUser, activateSubscription, getUserByPhone } = require('../models/user');
const { assertDeviceAllowed, checkEntitlement, DeviceLimitError } = require('../models/device');
const { isSubscriptionActive } = require('../models/mapUser');

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
        message: 'Order created but user database unavailable. Set DATABASE_URL and run migrations.',
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

router.get('/status', requirePhone, optionalDevice, async (req, res) => {
  try {
    if (req.deviceId) {
      try {
        const entitlement = await checkEntitlement(req.phone, req.deviceId, req.deviceLabel);
        return res.json(entitlement);
      } catch (err) {
        if (err instanceof DeviceLimitError || err.code === 'DEVICE_LIMIT') {
          return res.status(403).json({
            active: false,
            deviceAllowed: false,
            code: 'DEVICE_LIMIT',
            message: err.message,
          });
        }
        if (err.code === 'BLOCKED') {
          return res.status(403).json({
            active: false,
            deviceAllowed: false,
            code: 'BLOCKED',
            message: err.message,
          });
        }
        throw err;
      }
    }

    const user = await getUserByPhone(req.phone);
    if (!user || !user.subscriptionEnd) {
      return res.json({ active: false });
    }
    res.json({
      active: isSubscriptionActive(user),
      subscriptionStart: user.subscriptionStart,
      subscriptionEnd: user.subscriptionEnd,
      planType: user.planType,
      maxDevices: user.maxDevices,
      status: user.status,
    });
  } catch (err) {
    console.error('status error:', err);
    res.status(500).json({ message: err.message });
  }
});

/** Explicit bind used at login/register when deviceId is known */
router.post('/bind-device', requirePhone, requireDevice, async (req, res) => {
  try {
    // Explicit bind (post-login) may rebind when at capacity — same as OTP.
    await assertDeviceAllowed(req.phone, req.deviceId, req.deviceLabel, {
      rebindIfFull: true,
    });
    const entitlement = await checkEntitlement(req.phone, req.deviceId, req.deviceLabel);
    res.json(entitlement);
  } catch (err) {
    if (err instanceof DeviceLimitError || err.code === 'DEVICE_LIMIT') {
      return res.status(403).json({ message: err.message, code: 'DEVICE_LIMIT' });
    }
    const status = err.status || 500;
    res.status(status).json({ message: err.message, code: err.code });
  }
});

module.exports = router;
