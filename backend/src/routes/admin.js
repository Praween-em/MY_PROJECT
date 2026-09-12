/**
 * admin.js — admin API for user / subscription / device management
 */

const express = require('express');
const router = express.Router();
const { loginAdmin, requireAdmin } = require('../middleware/adminAuth');
const {
  searchUsers,
  getUserByPhone,
  adminUpdateUser,
  ensureUser,
  processReferralOnPayment,
} = require('../models/user');
const { listDevicesForUser, removeDevice, resetDevices } = require('../models/device');
const {
  logAdminAction,
  getDashboardStats,
  listActiveSubscriptions,
  listAuditLogs,
} = require('../models/admin');
const { listPlans, upsertPlans, ALLOWED_PLAN_IDS } = require('../models/plans');
const { computeSubscriptionEnd } = require('../models/user');
const { listSocialLinks, upsertSocialLinks } = require('../models/socials');
const {
  getPaymentContactConfig,
  updateTelegramUrl,
  savePaymentImage,
  removePaymentImage,
} = require('../models/paymentContact');

router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body || {};
    if (!email || !password) {
      return res.status(400).json({ message: 'email and password required' });
    }
    const result = await loginAdmin(email, password);
    if (!result) return res.status(401).json({ message: 'Invalid credentials' });
    res.json({ token: result.token, admin: result.admin });
  } catch (err) {
    console.error('admin login error:', err);
    res.status(500).json({ message: err.message });
  }
});

router.use(requireAdmin);

router.get('/me', (req, res) => {
  res.json({ admin: req.admin });
});

router.get('/dashboard', async (_req, res) => {
  try {
    const stats = await getDashboardStats();
    res.json({ stats });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/** GET /admin/api/plans — all plans for editing */
router.get('/plans', async (_req, res) => {
  try {
    const plans = await listPlans();
    res.json({ plans });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/**
 * PUT /admin/api/plans
 * Body: { plans: [{ id, label?, amount?, durationDays?, description?, enabled?, sortOrder? }] }
 * amount is in paise (₹299 = 29900)
 */
router.put('/plans', async (req, res) => {
  try {
    const updated = await upsertPlans(req.body || {});
    await logAdminAction(req.admin.id, 'plans.update', null, {
      planIds: updated.map((p) => p.id),
    });
    const plans = await listPlans();
    res.json({ success: true, updated, plans });
  } catch (err) {
    const status = err.status || 500;
    res.status(status).json({ message: err.message });
  }
});

router.get('/subscriptions', async (req, res) => {
  try {
    const subscriptions = await listActiveSubscriptions(Number(req.query.limit) || 100);
    res.json({ subscriptions });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.get('/users', async (req, res) => {
  try {
    const users = await searchUsers(req.query.q, Number(req.query.limit) || 50);
    res.json({ users });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/** Create / ensure a user by phone (so admin can grant before first login). */
router.post('/users', async (req, res) => {
  try {
    const phone = String(req.body?.phone || '').replace(/\D/g, '');
    if (!/^\d{10}$/.test(phone)) {
      return res.status(400).json({ message: 'Valid 10-digit phone required' });
    }
    const user = await ensureUser(phone);
    await logAdminAction(req.admin.id, 'user.create', phone, {});
    res.json({ user });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.get('/users/:phone', async (req, res) => {
  try {
    const user = await getUserByPhone(req.params.phone);
    if (!user) return res.status(404).json({ message: 'User not found' });
    const devices = await listDevicesForUser(user.id);
    const active = !!(user.subscriptionEnd && new Date(user.subscriptionEnd) > new Date() && user.status !== 'blocked');
    res.json({ user, devices, active });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.patch('/users/:phone', async (req, res) => {
  try {
    let user = await getUserByPhone(req.params.phone);
    if (!user) {
      user = await ensureUser(req.params.phone);
    }

    const patch = {};
    if (req.body.maxDevices !== undefined) {
      const n = Number(req.body.maxDevices);
      if (![1, 2, 3, 4, 5].includes(n)) {
        return res.status(400).json({ message: 'maxDevices must be 1–5' });
      }
      patch.maxDevices = n;
    }
    if (req.body.status !== undefined) {
      if (!['active', 'blocked'].includes(req.body.status)) {
        return res.status(400).json({ message: 'status must be active or blocked' });
      }
      patch.status = req.body.status;
    }
    if (req.body.planType !== undefined) patch.planType = req.body.planType;
    if (req.body.subscriptionStart !== undefined) patch.subscriptionStart = req.body.subscriptionStart;
    if (req.body.subscriptionEnd !== undefined) patch.subscriptionEnd = req.body.subscriptionEnd;

    // Convenience: grant N days from now or extend from current end
    if (req.body.grantDays !== undefined) {
      const days = Number(req.body.grantDays);
      if (!Number.isFinite(days) || days <= 0) {
        return res.status(400).json({ message: 'grantDays must be a positive number' });
      }
      const base =
        user.subscriptionEnd && new Date(user.subscriptionEnd) > new Date()
          ? new Date(user.subscriptionEnd)
          : new Date();
      base.setDate(base.getDate() + days);
      patch.subscriptionEnd = base.toISOString();
      if (!user.subscriptionStart) patch.subscriptionStart = new Date().toISOString();
      if (req.body.planType) patch.planType = req.body.planType;
    }

    // Convenience: grant a full plan from now
    if (req.body.grantPlan) {
      const planId = req.body.grantPlan;
      if (!ALLOWED_PLAN_IDS.has(planId)) {
        return res.status(400).json({ message: 'grantPlan must be trial, monthly, or quarterly' });
      }
      const base =
        user.subscriptionEnd && new Date(user.subscriptionEnd) > new Date()
          ? new Date(user.subscriptionEnd)
          : new Date();
      patch.planType = planId;
      patch.subscriptionStart = user.subscriptionStart || new Date().toISOString();
      patch.subscriptionEnd = await computeSubscriptionEnd(planId, base);
    }

    // Revoke plan immediately
    if (req.body.revokeSubscription === true) {
      patch.subscriptionEnd = new Date(Date.now() - 60_000).toISOString();
    }

    const updated = await adminUpdateUser(req.params.phone, patch);
    if (req.body.grantPlan) {
      await processReferralOnPayment(updated.phone, req.body.grantPlan);
    }
    await logAdminAction(req.admin.id, 'user.patch', req.params.phone, patch);
    res.json({ user: updated });
  } catch (err) {
    console.error('admin patch user:', err);
    res.status(500).json({ message: err.message });
  }
});

router.delete('/users/:phone/devices/:deviceId', async (req, res) => {
  try {
    const ok = await removeDevice(req.params.phone, req.params.deviceId);
    if (!ok) return res.status(404).json({ message: 'Device not found' });
    await logAdminAction(req.admin.id, 'device.remove', req.params.phone, {
      deviceId: req.params.deviceId,
    });
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.post('/users/:phone/devices/reset', async (req, res) => {
  try {
    const removed = await resetDevices(req.params.phone);
    await logAdminAction(req.admin.id, 'device.reset', req.params.phone, { removed });
    res.json({ success: true, removed });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/** GET /admin/api/socials — all links including disabled */
router.get('/socials', async (_req, res) => {
  try {
    const links = await listSocialLinks({ enabledOnly: false });
    res.json({ links });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/**
 * PUT /admin/api/socials
 * Body: { whatsapp: 'https://...', instagram: '...' }
 *    or { links: [{ key, url, label?, enabled?, sortOrder? }] }
 */
router.put('/socials', async (req, res) => {
  try {
    const updated = await upsertSocialLinks(req.body || {});
    await logAdminAction(req.admin.id, 'socials.update', null, {
      keys: updated.map((l) => l.key),
    });
    const links = await listSocialLinks({ enabledOnly: false });
    res.json({ success: true, updated, links });
  } catch (err) {
    const status = err.status || 500;
    res.status(status).json({ message: err.message });
  }
});

router.get('/payment-contact', async (_req, res) => {
  try {
    res.json({ config: await getPaymentContactConfig() });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

router.put('/payment-contact', async (req, res) => {
  try {
    const config = await updateTelegramUrl(req.body?.telegramUrl);
    await logAdminAction(req.admin.id, 'payment-contact.telegram.update', null, {
      configured: !!config.telegramUrl,
    });
    res.json({ success: true, config });
  } catch (err) {
    res.status(err.status || 500).json({ message: err.message });
  }
});

router.post(
  '/payment-contact/image',
  express.raw({ type: ['image/png', 'image/jpeg', 'image/webp'], limit: '2mb' }),
  async (req, res) => {
    try {
      const config = await savePaymentImage(req.body, req.get('content-type'));
      await logAdminAction(req.admin.id, 'payment-contact.image.update', null, {
        mime: req.get('content-type'),
        bytes: req.body?.length || 0,
      });
      res.json({ success: true, config });
    } catch (err) {
      res.status(err.status || 500).json({ message: err.message });
    }
  }
);

router.delete('/payment-contact/image', async (req, res) => {
  try {
    const config = await removePaymentImage();
    await logAdminAction(req.admin.id, 'payment-contact.image.remove', null, {});
    res.json({ success: true, config });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

/** Recent admin actions */
router.get('/audit', async (req, res) => {
  try {
    const logs = await listAuditLogs(Number(req.query.limit) || 100);
    res.json({ logs });
  } catch (err) {
    res.status(500).json({ message: err.message });
  }
});

module.exports = router;
