/**
 * entitlement.js — subscription + device gate for auto-accept
 */

const express = require('express');
const router = express.Router();
const { requirePhone, requireDevice } = require('../middleware/auth');
const { checkEntitlement, DeviceLimitError } = require('../models/device');

router.post('/check', requirePhone, requireDevice, async (req, res) => {
  try {
    const result = await checkEntitlement(req.phone, req.deviceId, req.deviceLabel);
    res.json(result);
  } catch (err) {
    if (err instanceof DeviceLimitError || err.code === 'DEVICE_LIMIT') {
      return res.status(403).json({ message: err.message, code: 'DEVICE_LIMIT', active: false, deviceAllowed: false });
    }
    if (err.code === 'BLOCKED') {
      return res.status(403).json({ message: err.message, code: 'BLOCKED', active: false, deviceAllowed: false });
    }
    console.error('entitlement check error:', err);
    const status = Number(err.status) || 500;
    res.status(status).json({
      message: status >= 500 ? 'Could not check the plan' : err.message,
      code: status >= 500 ? undefined : err.code,
      active: false,
    });
  }
});

module.exports = router;
