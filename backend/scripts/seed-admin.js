/**
 * seed-admin.js — create first admin user
 * Usage: node scripts/seed-admin.js admin@example.com 'StrongPassword123'
 */

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const envFile = ['.env', '.env.dev'].map((f) => path.join(root, f)).find((p) => fs.existsSync(p));
if (envFile) require('dotenv').config({ path: envFile });

const { pool } = require('../src/config/db');
const { createAdmin, findAdminByEmail } = require('../src/models/admin');

async function main() {
  const email = process.argv[2] || process.env.ADMIN_EMAIL;
  const password = process.argv[3] || process.env.ADMIN_PASSWORD;

  if (!email || !password) {
    console.error('Usage: node scripts/seed-admin.js <email> <password>');
    process.exit(1);
  }
  if (!process.env.DATABASE_URL) {
    console.error('DATABASE_URL is required');
    process.exit(1);
  }

  const existing = await findAdminByEmail(email);
  if (existing) {
    console.log(`Admin already exists: ${email}`);
    await pool.end();
    return;
  }

  const admin = await createAdmin(email, password);
  console.log('Admin created:', admin.email, admin.id);
  await pool.end();
}

main().catch(async (err) => {
  console.error(err);
  try { await pool.end(); } catch { /* ignore */ }
  process.exit(1);
});
