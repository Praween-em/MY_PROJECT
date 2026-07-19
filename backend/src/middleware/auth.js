/**
 * auth.js — lightweight phone-based auth middleware
 *
 * For this version: the app sends the phone number as a Bearer token.
 * In production, replace with a signed JWT issued after OTP verification.
 */

function requirePhone(req, res, next) {
  let phone =
    req.body?.phone ||
    req.query?.phone ||
    req.headers['x-phone'];

  if (!phone) {
    const auth = req.headers.authorization || req.headers.Authorization;
    if (auth?.startsWith('Bearer ')) {
      const token = auth.slice(7).trim();
      if (/^\d{10}$/.test(token)) phone = token;
    }
  }

  if (!phone || !/^\d{10}$/.test(phone)) {
    return res.status(401).json({ message: 'Valid 10-digit phone number required' });
  }

  req.phone = phone;
  next();
}

module.exports = { requirePhone };
