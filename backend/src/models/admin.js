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

async function getDashboardStats() {
  const { rows } = await query(`
    SELECT
      (SELECT COUNT(*)::int FROM users) AS total_users,
      (SELECT COUNT(*)::int FROM users WHERE status = 'blocked') AS blocked_users,
      (SELECT COUNT(*)::int FROM users
        WHERE status = 'active'
          AND subscription_end IS NOT NULL
          AND subscription_end > NOW()) AS active_subscriptions,
      (SELECT COUNT(*)::int FROM devices) AS bound_devices
  `);
  const s = rows[0];
  return {
    totalUsers: s.total_users,
    blockedUsers: s.blocked_users,
    activeSubscriptions: s.active_subscriptions,
    boundDevices: s.bound_devices,
  };
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
  getDashboardStats,
  listActiveSubscriptions,
  listAuditLogs,
};
