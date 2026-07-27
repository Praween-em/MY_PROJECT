/**
 * socials.js — public social links for the mobile app
 */

const express = require('express');
const router = express.Router();
const { getPublicSocials } = require('../models/socials');

/** GET /socials — no auth; app fetches on Home load */
router.get('/', async (_req, res) => {
  try {
    const data = await getPublicSocials();
    res.json(data);
  } catch (err) {
    console.error('GET /socials:', err);
    res.status(500).json({ message: err.message });
  }
});

module.exports = router;
