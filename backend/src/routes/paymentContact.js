/**
 * Public Telegram payment contact configuration for the mobile app.
 */

const express = require('express');
const router = express.Router();
const {
  getPaymentContactConfig,
  getPaymentImage,
} = require('../models/paymentContact');
const { getEnabledSocialUrl } = require('../models/socials');

router.get('/', async (_req, res) => {
  try {
    const config = await getPaymentContactConfig();
    const socialTelegram = await getEnabledSocialUrl('telegram').catch(() => '');
    const telegramUrl = config.telegramUrl || socialTelegram || '';
    res.set('Cache-Control', 'no-store');
    res.json({
      ...config,
      telegramUrl,
      source: config.telegramUrl ? 'payment-contact' : (telegramUrl ? 'socials' : 'none'),
      imagePath: config.hasImage && config.imageUpdatedAt
        ? `/payment-contact/image?v=${encodeURIComponent(new Date(config.imageUpdatedAt).getTime())}`
        : null,
    });
  } catch (err) {
    console.error('GET /payment-contact:', err);
    res.status(500).json({ message: err.message });
  }
});

router.get('/image', async (_req, res) => {
  try {
    const image = await getPaymentImage();
    if (!image) return res.status(404).end();
    res.set({
      'Content-Type': image.mime,
      'Cache-Control': 'public, max-age=300, must-revalidate',
      'Content-Length': image.data.length,
    });
    return res.send(image.data);
  } catch (err) {
    console.error('GET /payment-contact/image:', err);
    return res.status(500).json({ message: err.message });
  }
});

module.exports = router;
