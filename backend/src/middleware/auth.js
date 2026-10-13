/**
 * Session middleware.
 * A login session is a hex bearer issued after MSG91 confirms the OTP.
 * A phone number in the body, query, or header is not a credential.
 */

const { getUserBySessionToken } = require('../models/user');

function readBearer(req) {
  const auth = req.headers.authorization || req.headers.Authorization;
  if (!auth?.startsWith('Bearer ')) return '';
  return auth.slice(7).trim();
}

function readDeviceId(req) {
  const raw =
    req.body?.deviceId ||
    req.query?.deviceId ||
    req.headers['x-device-id'] ||
    null;
  if (raw == null) return null;
  const id = String(raw).trim();
  return id.length >= 8 ? id : null;
}

function readDeviceLabel(req) {
  return (
    req.body?.deviceLabel ||
    req.query?.deviceLabel ||
    req.headers['x-device-label'] ||
    null
  );
}

async function requirePhone(req, res, next) {
  try {
    const bearer = readBearer(req);
    if (!/^[a-f0-9]{32,128}$/i.test(bearer)) {
      return res.status(401).json({
        message: 'Log in again.',
        code: 'SESSION_REQUIRED',
      });
    }
    const user = await getUserBySessionToken(bearer);
    if (!user?.phone) {
      return res.status(401).json({
        message: 'Session expired. Log in again.',
        code: 'SESSION_REQUIRED',
      });
    }
    req.phone = user.phone;
    next();
  } catch (err) {
    if (err.code === '42703') {
      return res.status(503).json({
        message: 'Login storage is not ready. Apply migration 008, then log in again.',
        code: 'SESSION_UNAVAILABLE',
      });
    }
    next(err);
  }
}

function optionalDevice(req, _res, next) {
  req.deviceId = readDeviceId(req);
  req.deviceLabel = readDeviceLabel(req);
  next();
}

function requireDevice(req, res, next) {
  const deviceId = readDeviceId(req);
  if (!deviceId || String(deviceId).length < 8) {
    return res.status(400).json({ message: 'Valid deviceId required', code: 'DEVICE_REQUIRED' });
  }
  req.deviceId = deviceId;
  req.deviceLabel = readDeviceLabel(req);
  next();
}

module.exports = {
  requirePhone,
  optionalDevice,
  requireDevice,
  readDeviceId,
  readDeviceLabel,
};
