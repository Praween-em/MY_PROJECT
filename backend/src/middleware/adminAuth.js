/**
 * adminAuth.js — simple HMAC admin session token
 */

const crypto = require('crypto');
const { authenticateAdmin } = require('../models/admin');

const TTL_MS = 1000 * 60 * 60 * 12; // 12 hours

function secret() {
  return process.env.ADMIN_JWT_SECRET || process.env.ADMIN_SECRET || 'dev-admin-secret-change-me';
}

function signAdminToken(admin) {
  const payload = Buffer.from(JSON.stringify({
    id: admin.id,
    email: admin.email,
    role: admin.role,
    exp: Date.now() + TTL_MS,
  })).toString('base64url');
  const sig = crypto.createHmac('sha256', secret()).update(payload).digest('base64url');
  return `${payload}.${sig}`;
}

function verifyAdminToken(token) {
  if (!token || !token.includes('.')) return null;
  const [payload, sig] = token.split('.');
  const expected = crypto.createHmac('sha256', secret()).update(payload).digest('base64url');
  if (sig.length !== expected.length) return null;
  try {
    if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  } catch {
    return null;
  }
  try {
    const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!data.exp || Date.now() > data.exp) return null;
    return data;
  } catch {
    return null;
  }
}

async function loginAdmin(email, password) {
  const admin = await authenticateAdmin(email, password);
  if (!admin) return null;
  return { admin, token: signAdminToken(admin) };
}

function requireAdmin(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ')
    ? header.slice(7).trim()
    : (req.headers['x-admin-token'] || req.cookies?.adminToken || null);

  const admin = verifyAdminToken(token);
  if (!admin) {
    return res.status(401).json({ message: 'Admin auth required' });
  }
  req.admin = admin;
  next();
}

module.exports = {
  signAdminToken,
  verifyAdminToken,
  loginAdmin,
  requireAdmin,
};
