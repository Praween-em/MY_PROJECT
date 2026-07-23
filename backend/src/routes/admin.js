/**
 * admin.js — admin API for user / subscription / device management
 */

const express = require('express');
const router = express.Router();
const { loginAdmin, requireAdmin } = require('../middleware/adminAuth');
const { searchUsers, getUserByPhone, adminUpdateUser, ensureUser } = require('../models/user');
const { listDevicesForUser, removeDevice, resetDevices } = require('../models/device');
const { logAdminAction, listPaymentsForUser, getDashboardStats, listPaidCustomers, listActiveSubscriptions, listAllPayments } = require('../models/admin');
const { computeSubscriptionEnd } = require('../models/user');

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

router.get('/payments', async (req, res) => {
  try {
    const payments = await listAllPayments(Number(req.query.limit) || 100);
    res.json({ payments });
  } catch (err) {
    res.status(500).json({ message: err.message });
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

router.get('/paid-customers', async (req, res) => {
  try {
    const customers = await listPaidCustomers(Number(req.query.limit) || 100);
    res.json({ customers });
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

router.get('/users/:phone', async (req, res) => {
  try {
    const user = await getUserByPhone(req.params.phone);
    if (!user) return res.status(404).json({ message: 'User not found' });
    const devices = await listDevicesForUser(user.id);
    const payments = await listPaymentsForUser(user.id);
    const active = !!(user.subscriptionEnd && new Date(user.subscriptionEnd) > new Date() && user.status !== 'blocked');
    res.json({ user, devices, payments, active });
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
      if (![1, 2, 3].includes(n)) {
        return res.status(400).json({ message: 'maxDevices must be 1, 2, or 3' });
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
      if (!['monthly', 'quarterly'].includes(planId)) {
        return res.status(400).json({ message: 'grantPlan must be monthly or quarterly' });
      }
      const base =
        user.subscriptionEnd && new Date(user.subscriptionEnd) > new Date()
          ? new Date(user.subscriptionEnd)
          : new Date();
      patch.planType = planId;
      patch.subscriptionStart = user.subscriptionStart || new Date().toISOString();
      patch.subscriptionEnd = computeSubscriptionEnd(planId, base);
    }

    const updated = await adminUpdateUser(req.params.phone, patch);
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

module.exports = router;
