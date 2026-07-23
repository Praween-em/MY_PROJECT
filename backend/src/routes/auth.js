/**
 * auth.js — OTP login (client widget + server MSG91 verifyAccessToken)
 */

const express = require('express');
const router = express.Router();
const { optionalDevice } = require('../middleware/auth');
const { verifyAccessToken } = require('../services/msg91');
const { ensureUser, applyReferralCode, getUserByPhone } = require('../models/user');
const { assertDeviceAllowed, DeviceLimitError } = require('../models/device');
const { isSubscriptionActive } = require('../models/mapUser');

/**
 * POST /auth/verify-otp
 * Body: { phone, accessToken, referralCode?, deviceId?, deviceLabel? }
 */
router.post('/verify-otp', optionalDevice, async (req, res) => {
  try {
    const phone = String(req.body?.phone || '').replace(/\D/g, '');
    const accessToken =
      req.body?.accessToken ||
      req.body?.['access-token'] ||
      req.body?.access_token ||
      req.headers['access-token'] ||
      null;
    const referralCode = req.body?.referralCode;

    if (!/^\d{10}$/.test(phone)) {
      return res.status(400).json({ message: 'Valid 10-digit phone required' });
    }
    if (!accessToken || typeof accessToken !== 'string') {
      console.warn('[auth] missing accessToken body keys=', Object.keys(req.body || {}));
      return res.status(400).json({ message: 'accessToken required' });
    }
    console.log('[auth] verify-otp phone=', phone, 'tokenLen=', accessToken.length);

    // Server-side confirmation with MSG91 (authkey from env — never hardcoded)
    await verifyAccessToken(accessToken);

    const user = await ensureUser(phone);

    let referralApplied = false;
    let referralError = null;
    if (referralCode?.trim()) {
      try {
        await applyReferralCode(phone, referralCode.trim());
        referralApplied = true;
      } catch (e) {
        referralError = e.message;
      }
    }

    if (req.deviceId) {
      try {
        await assertDeviceAllowed(phone, req.deviceId, req.deviceLabel);
      } catch (e) {
        if (e instanceof DeviceLimitError || e.code === 'DEVICE_LIMIT') {
          return res.status(403).json({
            message: e.message,
            code: 'DEVICE_LIMIT',
            phone,
            referralCode: user.referralCode,
          });
        }
        if (e.code === 'BLOCKED') {
          return res.status(403).json({ message: e.message, code: 'BLOCKED' });
        }
        throw e;
      }
    }

    const updated = await getUserByPhone(phone);
    res.json({
      success: true,
      phone: updated.phone,
      referralCode: updated.referralCode,
      referralApplied,
      referralError,
      active: isSubscriptionActive(updated),
      subscriptionEnd: updated.subscriptionEnd,
      planType: updated.planType,
      maxDevices: updated.maxDevices,
    });
  } catch (err) {
    console.error('verify-otp error:', err.message);
    const status = err.status || 500;
    res.status(status).json({ message: err.message, code: err.code });
  }
});

module.exports = router;
