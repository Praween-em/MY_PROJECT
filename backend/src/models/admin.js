/**
 * admin.js — admin users + audit log
 */

const crypto = require('crypto');
const { query } = require('../config/db');

function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
  const [salt, hash] = String(stored).split(':');
  if (!salt || !hash) return false;
  const next = crypto.scryptSync(password, salt, 64).toString('hex');
  if (next.length !== hash.length) return false;
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(next, 'hex'));
}

async function createAdmin(email, password, role = 'admin') {
  const passwordHash = hashPassword(password);
  const { rows } = await query(
    `INSERT INTO admin_users (email, password_hash, role)
     VALUES ($1, $2, $3)
     RETURNING id, email, role, created_at`,
    [email.toLowerCase(), passwordHash, role]
  );
  return rows[0];
}

async function findAdminByEmail(email) {
  const { rows } = await query(
    `SELECT * FROM admin_users WHERE email = $1`,
    [email.toLowerCase()]
  );
  return rows[0] || null;
}

async function authenticateAdmin(email, password) {
  const admin = await findAdminByEmail(email);
  if (!admin || !verifyPassword(password, admin.password_hash)) return null;
  return { id: admin.id, email: admin.email, role: admin.role };
}

async function logAdminAction(adminId, action, targetPhone, details = {}) {
  await query(
    `INSERT INTO admin_audit_logs (admin_id, action, target_phone, details)
     VALUES ($1, $2, $3, $4)`,
    [adminId || null, action, targetPhone || null, JSON.stringify(details)]
  );
}

async function listPaymentsForUser(userId, limit = 20) {
  const { rows } = await query(
    `SELECT id, order_id, payment_id, plan_type, amount, status, created_at
     FROM payments WHERE user_id = $1
     ORDER BY created_at DESC LIMIT $2`,
    [userId, limit]
  );
  return rows.map((r) => ({
    id: r.id,
    orderId: r.order_id,
    paymentId: r.payment_id,
    planType: r.plan_type,
    amount: r.amount,
    status: r.status,
    createdAt: r.created_at,
  }));
}

async function getDashboardStats() {
  const { rows } = await query(`
    SELECT
      (SELECT COUNT(*)::int FROM users) AS total_users,
      (SELECT COUNT(*)::int FROM users WHERE status = 'blocked') AS blocked_users,
      (SELECT COUNT(*)::int FROM users
        WHERE status = 'active'
          AND subscription_end IS NOT NULL
          AND subscription_end > NOW()) AS active_subscriptions,
      (SELECT COUNT(*)::int FROM users
        WHERE razorpay_payment_id IS NOT NULL
           OR EXISTS (SELECT 1 FROM payments p WHERE p.user_id = users.id)) AS paid_customers,
      (SELECT COUNT(*)::int FROM payments WHERE status = 'captured') AS successful_payments,
      (SELECT COALESCE(SUM(amount), 0)::int FROM payments WHERE status = 'captured') AS revenue_paise,
      (SELECT COUNT(*)::int FROM devices) AS bound_devices
  `);
  const s = rows[0];
  return {
    totalUsers: s.total_users,
    blockedUsers: s.blocked_users,
    activeSubscriptions: s.active_subscriptions,
    paidCustomers: s.paid_customers,
    successfulPayments: s.successful_payments,
    revenuePaise: s.revenue_paise,
    revenueInr: Math.round((s.revenue_paise || 0) / 100),
    boundDevices: s.bound_devices,
  };
}

async function listPaidCustomers(limit = 100) {
  const { rows } = await query(
    `SELECT u.*,
            (SELECT COUNT(*)::int FROM payments p WHERE p.user_id = u.id AND p.status = 'captured') AS payment_count,
            (SELECT MAX(p.created_at) FROM payments p WHERE p.user_id = u.id AND p.status = 'captured') AS last_payment_at,
            (SELECT COUNT(*)::int FROM devices d WHERE d.user_id = u.id) AS device_count
     FROM users u
     WHERE u.razorpay_payment_id IS NOT NULL
        OR EXISTS (SELECT 1 FROM payments p WHERE p.user_id = u.id AND p.status = 'captured')
     ORDER BY COALESCE(
       (SELECT MAX(p.created_at) FROM payments p WHERE p.user_id = u.id),
       u.updated_at
     ) DESC
     LIMIT $1`,
    [limit]
  );
  const { mapUser, isSubscriptionActive } = require('./mapUser');
  return rows.map((r) => {
    const user = mapUser(r);
    return {
      ...user,
      paymentCount: r.payment_count,
      lastPaymentAt: r.last_payment_at,
      deviceCount: r.device_count,
      active: isSubscriptionActive(user),
    };
  });
}

async function listActiveSubscriptions(limit = 100) {
  const { rows } = await query(
    `SELECT u.*,
            (SELECT COUNT(*)::int FROM devices d WHERE d.user_id = u.id) AS device_count
     FROM users u
     WHERE u.status = 'active'
       AND u.subscription_end IS NOT NULL
       AND u.subscription_end > NOW()
     ORDER BY u.subscription_end ASC
     LIMIT $1`,
    [limit]
  );
  const { mapUser } = require('./mapUser');
  return rows.map((r) => ({
    ...mapUser(r),
    deviceCount: r.device_count,
    active: true,
  }));
}

async function listAllPayments(limit = 100) {
  const { rows } = await query(
    `SELECT p.id, p.order_id, p.payment_id, p.plan_type, p.amount, p.status, p.created_at,
            u.phone, u.referral_code, u.status AS user_status
     FROM payments p
     JOIN users u ON u.id = p.user_id
     WHERE p.status = 'captured'
     ORDER BY p.created_at DESC
     LIMIT $1`,
    [limit]
  );
  return rows.map((r) => ({
    id: r.id,
    phone: r.phone,
    referralCode: r.referral_code,
    userStatus: r.user_status,
    orderId: r.order_id,
    paymentId: r.payment_id,
    planType: r.plan_type,
    amount: r.amount,
    amountInr: r.amount != null ? Math.round(r.amount / 100) : null,
    status: r.status,
    createdAt: r.created_at,
  }));
}

async function listAuditLogs(limit = 100) {
  const { rows } = await query(
    `SELECT l.id, l.action, l.target_phone, l.details, l.created_at,
            a.email AS admin_email
     FROM admin_audit_logs l
     LEFT JOIN admin_users a ON a.id = l.admin_id
     ORDER BY l.created_at DESC
     LIMIT $1`,
    [Math.min(Math.max(Number(limit) || 100, 1), 500)]
  );
  return rows.map((r) => ({
    id: r.id,
    action: r.action,
    targetPhone: r.target_phone,
    details: r.details,
    adminEmail: r.admin_email,
    createdAt: r.created_at,
  }));
}

module.exports = {
  hashPassword,
  verifyPassword,
  createAdmin,
  findAdminByEmail,
  authenticateAdmin,
  logAdminAction,
  listPaymentsForUser,
  getDashboardStats,
  listPaidCustomers,
  listActiveSubscriptions,
  listAllPayments,
  listAuditLogs,
};
