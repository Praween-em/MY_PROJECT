/**
 * Small in-memory limiter for login and OTP. One process, which matches this API.
 */
function createRateLimit({ windowMs, max, keyFn }) {
  const hits = new Map();
  return function rateLimit(req, res, next) {
    const now = Date.now();
    if (hits.size > 5000) {
      for (const [key, bucket] of hits) {
        if (now - bucket.start > windowMs) hits.delete(key);
      }
    }
    const key = keyFn(req);
    const bucket = hits.get(key);
    if (!bucket || now - bucket.start > windowMs) {
      hits.set(key, { start: now, count: 1 });
      return next();
    }
    bucket.count += 1;
    if (bucket.count > max) {
      return res.status(429).json({
        message: 'Too many attempts. Try again shortly.',
        code: 'RATE_LIMIT',
      });
    }
    return next();
  };
}

function clientKey(req) {
  return req.ip || req.socket?.remoteAddress || 'unknown';
}

module.exports = { createRateLimit, clientKey };
