/**
 * webhook.js — Razorpay webhook handler
 *
 * Register in Razorpay Dashboard → Webhooks (LIVE mode):
 *   URL: https://superridexversion2-production.up.railway.app/webhook/razorpay
 *   Event: payment.captured
 */

const express = require('express');
const router = express.Router();
const { verifyWebhookSignature } = require('../services/razorpay');
const { activateFromPaymentId } = require('../services/paymentActivation');

router.post('/razorpay', async (req, res) => {
  const signature = req.headers['x-razorpay-signature'];
  const rawBody = req.rawBody;

  if (!signature || !rawBody) {
    return res.status(400).json({ message: 'Missing signature or body' });
  }

  if (!verifyWebhookSignature(rawBody, signature)) {
    console.error('[webhook] invalid signature — check RAZORPAY_WEBHOOK_SECRET on Railway');
    return res.status(401).json({ message: 'Invalid webhook signature' });
  }

  const event = req.body;

  try {
    if (event.event === 'payment.captured') {
      const payment = event.payload?.payment?.entity;
      if (!payment?.id) {
        return res.status(400).json({ message: 'Invalid payment payload' });
      }

      const result = await activateFromPaymentId(payment.id);
      console.log(
        `[webhook] payment.captured ${result.alreadyProcessed ? 'already active' : 'activated'} ` +
        `phone=${result.phone} plan=${result.planId} payment=${payment.id}`
      );
    }

    res.json({ received: true });
  } catch (err) {
    console.error('[webhook] processing error:', err.message || err);
    res.status(500).json({ message: err.message });
  }
});

module.exports = router;
