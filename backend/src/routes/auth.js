/**
 * auth.js — OTP login (client widget + server MSG91 verifyAccessToken)
 */

const express = require('express');
const router = express.Router();
const { optionalDevice } = require('../middleware/auth');
const { createRateLimit, clientKey } = require('../middleware/rateLimit');
const { sendError } = require('../utils/httpError');

const verifyOtpLimit = createRateLimit({
  windowMs: 10 * 60 * 1000,
  max: 8,
  keyFn: (req) => `${clientKey(req)}:${String(req.body?.phone || '').replace(/\D/g, '').slice(-10)}`,
});
const { verifyAccessToken } = require('../services/msg91');
const { ensureUser, applyReferralCode, getUserByPhone, issueSessionToken } = require('../models/user');
const { assertDeviceAllowed, DeviceLimitError } = require('../models/device');
const { isSubscriptionActive } = require('../models/mapUser');

/**
 * POST /auth/verify-otp
 * Body: { phone, accessToken, referralCode?, deviceId?, deviceLabel? }
 */
router.post('/verify-otp', verifyOtpLimit, optionalDevice, async (req, res) => {
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

    // OTP proves phone ownership — always bind/rebind this install (fixes same-phone
    // reinstall / debug APK getting stuck on "another device").
    if (!req.deviceId || String(req.deviceId).trim().length < 8) {
      return res.status(400).json({
        message: 'deviceId required for login',
        code: 'DEVICE_REQUIRED',
      });
    }
    try {
      await assertDeviceAllowed(phone, req.deviceId, req.deviceLabel, {
        rebindIfFull: true,
      });
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

    const updated = await getUserByPhone(phone);
    let sessionToken = null;
    try {
      const issued = await issueSessionToken(phone);
      sessionToken = issued.sessionToken;
    } catch (e) {
      if (e.code === '42703') {
        return res.status(503).json({
          message: 'Login storage is not ready. Apply migration 008, then try again.',
          code: 'SESSION_UNAVAILABLE',
        });
      }
      throw e;
    }
    res.json({
      success: true,
      phone: updated.phone,
      sessionToken,
      referralCode: updated.referralCode,
      referralApplied,
      referralError,
      active: isSubscriptionActive(updated),
      subscriptionEnd: updated.subscriptionEnd,
      planType: updated.planType,
      maxDevices: updated.maxDevices,
    });
  } catch (err) {
    sendError(res, err, 'Login failed');
  }
});

module.exports = router;
