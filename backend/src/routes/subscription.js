/**
 * subscription.js — subscription routes (Postgres)
 */

const express = require('express');
const router = express.Router();
const { requirePhone, optionalDevice, requireDevice } = require('../middleware/auth');
const { listPublicPlans } = require('../models/plans');
const { getUserByPhone } = require('../models/user');
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
