/**
 * auth.js — phone + optional deviceId middleware
 *
 * Mobile app sends phone as Bearer token / body / query / x-phone.
 * deviceId from body / query / x-device-id.
 */

const { normalizePhone } = require('../utils/phone');

function requirePhone(req, res, next) {
  let raw =
    req.body?.phone ||
    req.query?.phone ||
    req.headers['x-phone'];

  if (!raw) {
    const auth = req.headers.authorization || req.headers.Authorization;
    if (auth?.startsWith('Bearer ')) {
      raw = auth.slice(7).trim();
    }
  }

  const phone = normalizePhone(raw);
  if (!phone) {
    return res.status(401).json({ message: 'Valid 10-digit phone number required' });
  }

  req.phone = phone;
  next();
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
