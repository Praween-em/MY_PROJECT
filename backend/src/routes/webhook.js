/**
 * webhook.js — Razorpay webhook handler
 *
 * Register in Razorpay Dashboard → Webhooks:
 *   URL: https://<api-gateway-url>/webhook/razorpay
 *   Event: payment.captured
 */

const express = require('express');
const router = express.Router();
const { verifyWebhookSignature, getOrderNotes, VALID_PLANS } = require('../services/razorpay');
const { activateSubscription } = require('../models/user');

router.post('/razorpay', async (req, res) => {
  const signature = req.headers['x-razorpay-signature'];
  const rawBody = req.rawBody;

  if (!signature || !rawBody) {
    return res.status(400).json({ message: 'Missing signature or body' });
  }

  if (!verifyWebhookSignature(rawBody, signature)) {
    return res.status(401).json({ message: 'Invalid webhook signature' });
  }

  const event = req.body;

  try {
    if (event.event === 'payment.captured') {
      const payment = event.payload?.payment?.entity;
      if (!payment) {
        return res.status(400).json({ message: 'Invalid payment payload' });
      }

      const orderId = payment.order_id;
      const paymentId = payment.id;
      const notes = await getOrderNotes(orderId);
      const { phone, planId } = notes;

      if (phone && VALID_PLANS.includes(planId)) {
        const result = await activateSubscription(phone, {
          planId,
          razorpayOrderId: orderId,
          razorpayPaymentId: paymentId,
        });
        console.log(
          `Webhook: subscription ${result.alreadyProcessed ? 'already active' : 'activated'} ` +
          `for ${phone} (${planId})`
        );
      } else {
        console.warn(`Webhook: missing phone/planId in order ${orderId}`);
      }
    }

    res.json({ received: true });
  } catch (err) {
    console.error('Webhook processing error:', err);
    res.status(500).json({ message: err.message });
  }
});

module.exports = router;
