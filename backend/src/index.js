/**
 * index.js — Express app (Postgres + Telegram-assisted payments + Admin)
 */

const path = require('path');
const fs = require('fs');

if (!process.env.AWS_LAMBDA_FUNCTION_NAME) {
  const root = path.join(__dirname, '..');
  const envFile = ['.env', '.env.dev'].map((f) => path.join(root, f)).find((p) => fs.existsSync(p));
  if (envFile) {
    require('dotenv').config({ path: envFile });
  }
}

const express = require('express');
const cors = require('cors');

const subscriptionRoutes = require('./routes/subscription');
const referralRoutes = require('./routes/referral');
const entitlementRoutes = require('./routes/entitlement');
const adminRoutes = require('./routes/admin');
const authRoutes = require('./routes/auth');
const socialsRoutes = require('./routes/socials');
const paymentContactRoutes = require('./routes/paymentContact');
const { ping } = require('./config/db');

const app = express();

app.use(cors({ origin: '*' }));

app.use(express.json({
  verify: (req, _res, buf) => {
    if (req.originalUrl.startsWith('/webhook')) {
      req.rawBody = buf.toString('utf8');
    }
  },
}));

app.use('/auth', authRoutes);
app.use('/subscription', subscriptionRoutes);
app.use('/referral', referralRoutes);
app.use('/entitlement', entitlementRoutes);
app.use('/socials', socialsRoutes);
app.use('/payment-contact', paymentContactRoutes);
app.use('/admin/api', adminRoutes);

// Simple admin panel (static)
app.use('/admin', express.static(path.join(__dirname, '../admin-panel')));
app.get('/admin', (_req, res) => {
  res.sendFile(path.join(__dirname, '../admin-panel/index.html'));
});

app.get('/', (_req, res) => {
  res.json({ ok: true, service: 'ag-rider-api' });
});

app.get('/health', async (_req, res) => {
  let dbOk = false;
  try {
    dbOk = await ping();
  } catch {
    dbOk = false;
  }
  // Always 200 so Railway healthchecks pass while the HTTP server is up.
  res.json({
    status: dbOk ? 'ok' : 'degraded',
    db: dbOk,
    ts: new Date().toISOString(),
  });
});

async function boot() {
  try {
    const { runMigrations } = require('../scripts/migrate');
    await Promise.race([
      runMigrations(),
      new Promise((_, reject) => {
        setTimeout(() => reject(new Error('migrate timed out')), 15000);
      }),
    ]);
  } catch (err) {
    console.error('[boot] migrate failed:', err.message);
  }

  const PORT = Number(process.env.PORT) || 3000;
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`AG rider backend on port ${PORT}`);
  });
}

if (require.main === module) {
  boot();
}

module.exports = app;
