/**
 * index.js — Express app
 */

const path = require('path');
const fs = require('fs');

// Load .env.dev or .env for local/Docker (Lambda gets vars from serverless deploy)
if (!process.env.AWS_LAMBDA_FUNCTION_NAME) {
  const root = path.join(__dirname, '..');
  const envFile = ['.env.dev', '.env'].map((f) => path.join(root, f)).find((p) => fs.existsSync(p));
  if (envFile) {
    require('dotenv').config({ path: envFile });
  }
}

const express = require('express');
const cors = require('cors');

const subscriptionRoutes = require('./routes/subscription');
const webhookRoutes = require('./routes/webhook');
const referralRoutes = require('./routes/referral');

const app = express();

app.use(cors({ origin: '*' }));

// Capture raw body for Razorpay webhook HMAC while still parsing JSON for handlers
app.use(express.json({
  verify: (req, _res, buf) => {
    if (req.originalUrl.startsWith('/webhook')) {
      req.rawBody = buf.toString('utf8');
    }
  },
}));

app.use('/subscription', subscriptionRoutes);
app.use('/referral', referralRoutes);
app.use('/webhook', webhookRoutes);

app.get('/health', (_, res) => res.json({ status: 'ok', ts: new Date().toISOString() }));

// Standalone server mode (Docker / EC2)
if (require.main === module) {
  const PORT = process.env.PORT || 3000;
  app.listen(PORT, () => console.log(`Playnix backend on port ${PORT}`));
}

module.exports = app;
