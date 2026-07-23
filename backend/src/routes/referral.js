/**
 * referral.js — referral routes (Postgres + device bind on register)
 */

const express = require('express');
const router = express.Router();
const { requirePhone, optionalDevice } = require('../middleware/auth');
const { ensureUser, applyReferralCode, getUserByPhone } = require('../models/user');
const { assertDeviceAllowed, DeviceLimitError } = require('../models/device');
const { REFERRAL_GOAL } = require('../services/referral');

router.get('/status', requirePhone, async (req, res) => {
  try {
    const user = await ensureUser(req.phone);
    const paid = user.paidReferrals || 0;
    res.json({
      referralCode: user.referralCode,
      totalReferrals: user.totalReferrals || 0,
      paidReferrals: paid,
      referralRewardsEarned: user.referralRewardsEarned || 0,
      goal: REFERRAL_GOAL,
      rewardDescription: '1 month free when 10 referrals pay for at least 1 month',
      referredByPhone: user.referredByPhone || null,
      progress: paid % REFERRAL_GOAL,
      progressTowardNext: paid % REFERRAL_GOAL,
      paidReferralsTotal: paid,
    });
  } catch (err) {
    console.error('referral status error:', err);
    res.status(500).json({ message: err.message });
  }
});

router.post('/register', requirePhone, optionalDevice, async (req, res) => {
  try {
    const user = await ensureUser(req.phone);
    const { referralCode } = req.body;
    let referralApplied = false;
    let referralError = null;
    let deviceBound = false;
    let deviceError = null;

    if (referralCode?.trim()) {
      try {
        await applyReferralCode(req.phone, referralCode.trim());
        referralApplied = true;
      } catch (e) {
        referralError = e.message;
        console.warn('referral apply on register:', e.message);
      }
    }

    if (req.deviceId) {
      try {
        await assertDeviceAllowed(req.phone, req.deviceId, req.deviceLabel);
        deviceBound = true;
      } catch (e) {
        if (e instanceof DeviceLimitError || e.code === 'DEVICE_LIMIT') {
          return res.status(403).json({
            message: e.message,
            code: 'DEVICE_LIMIT',
            referralCode: user.referralCode,
            referralApplied,
            referralError,
          });
        }
        deviceError = e.message;
      }
    }

    const updated = await getUserByPhone(req.phone);
    res.json({
      referralCode: updated.referralCode,
      referralApplied,
      referralError,
      deviceBound,
      deviceError,
      maxDevices: updated.maxDevices,
    });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.post('/apply', requirePhone, async (req, res) => {
  try {
    const { code } = req.body;
    if (!code?.trim()) {
      return res.status(400).json({ message: 'Referral code required' });
    }
    const referrerPhone = await applyReferralCode(req.phone, code.trim());
    const referrer = await getUserByPhone(referrerPhone);
    res.json({
      success: true,
      referrerPhone,
      totalReferrals: referrer?.totalReferrals || 0,
    });
  } catch (err) {
    const status = /already applied|Invalid|not found/i.test(err.message) ? 400 : 500;
    res.status(status).json({ message: err.message });
  }
});

module.exports = router;
