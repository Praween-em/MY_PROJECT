/**
 * subscription.js — subscription routes (Postgres)
 */

const express = require('express');
const router = express.Router();
const { requirePhone, optionalDevice, requireDevice } = require('../middleware/auth');
const { createOrder } = require('../services/razorpay');
const { activateFromCheckout } = require('../services/paymentActivation');
const { getPlanById, listPublicPlans } = require('../models/plans');
const { ensureUser, getUserByPhone } = require('../models/user');
const { assertDeviceAllowed, checkEntitlement, DeviceLimitError } = require('../models/device');
const { isSubscriptionActive } = require('../models/mapUser');

/** GET /subscription/plans — public; app loads prices from here (no rebuild needed) */
router.get('/plans', async (_req, res) => {
  try {
    const plans = await listPublicPlans();
    res.json({ plans });
  } catch (err) {
    console.error('GET /subscription/plans:', err);
    res.status(500).json({ message: err.message });
  }
});

router.post('/create-order', requirePhone, async (req, res) => {
  try {
    const { planId } = req.body;
    const plan = await getPlanById(planId, { enabledOnly: true });
    if (!plan) {
      return res.status(400).json({ message: 'Invalid or disabled planId' });
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

    const { subscriptionEnd, referralResult, alreadyProcessed, phone, planId: resolvedPlan } =
      await activateFromCheckout({
        razorpayOrderId,
        razorpayPaymentId,
        razorpaySignature,
        phone: req.phone,
        planId,
      });

    console.log(
      `[payment] verify-payment ${alreadyProcessed ? 'idempotent' : 'activated'} ` +
      `phone=${phone} plan=${resolvedPlan} payment=${razorpayPaymentId}`
    );

    res.json({ success: true, subscriptionEnd, referralResult, alreadyProcessed });
  } catch (err) {
    console.error('verify-payment error:', err.message || err);
    const status =
      err.status ||
      (err.code === 'INVALID_SIGNATURE' || err.message === 'Invalid payment signature' ? 400 : 500);
    res.status(status).json({ message: err.message, code: err.code });
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
