/**
 * device.js — device binding / entitlement
 *
 * Policy:
 * - Same deviceId → always allowed (touch last_seen).
 * - New deviceId under max_devices → bind.
 * - New deviceId at capacity:
 *     - login / explicit rebind → replace oldest bind (OTP proves phone ownership).
 *     - status / entitlement poll → DEVICE_LIMIT (do not silently steal slots).
 */

const { query, withTransaction } = require('../config/db');
const { getUserByPhone, ensureUser } = require('./user');
const { isSubscriptionActive } = require('./mapUser');

class DeviceLimitError extends Error {
  constructor(message = 'This account is linked to another device. Sign in again with OTP on this phone, or reset devices in admin.') {
    super(message);
    this.name = 'DeviceLimitError';
    this.code = 'DEVICE_LIMIT';
    this.status = 403;
  }
}

function normalizeDeviceId(deviceId) {
  if (deviceId == null) return null;
  const id = String(deviceId).trim();
  return id.length >= 8 ? id : null;
}

async function listDevicesForUser(userId) {
  const { rows } = await query(
    `SELECT id, device_id, device_label, bound_at, last_seen_at
     FROM devices WHERE user_id = $1 ORDER BY bound_at ASC`,
    [userId]
  );
  return rows.map((r) => ({
    id: r.id,
    deviceId: r.device_id,
    deviceLabel: r.device_label,
    boundAt: r.bound_at,
    lastSeenAt: r.last_seen_at,
  }));
}

async function deleteOldestDevice(client, userId) {
  await client.query(
    `DELETE FROM devices WHERE id = (
       SELECT id FROM devices
       WHERE user_id = $1
       ORDER BY last_seen_at ASC NULLS FIRST, bound_at ASC
       LIMIT 1
     )`,
    [userId]
  );
}

/**
 * Bind or touch a device for the user.
 * @param {object} [options]
 * @param {boolean} [options.rebindIfFull=false] — OTP login: replace oldest when at capacity
 */
async function assertDeviceAllowed(phone, deviceId, deviceLabel = null, options = {}) {
  const { rebindIfFull = false } = options;
  const normalizedId = normalizeDeviceId(deviceId);
  if (!normalizedId) {
    const err = new Error('Valid deviceId required');
    err.status = 400;
    throw err;
  }

  const label = deviceLabel != null ? String(deviceLabel).slice(0, 256) : null;
  const user = await ensureUser(phone);
  if (user.status === 'blocked') {
    const err = new Error('Account is blocked');
    err.status = 403;
    err.code = 'BLOCKED';
    throw err;
  }

  return withTransaction(async (client) => {
    const existing = await client.query(
      `SELECT id FROM devices WHERE user_id = $1 AND device_id = $2`,
      [user.id, normalizedId]
    );

    if (existing.rows.length) {
      await client.query(
        `UPDATE devices SET last_seen_at = NOW(),
           device_label = COALESCE($1, device_label)
         WHERE user_id = $2 AND device_id = $3`,
        [label, user.id, normalizedId]
      );
      return { user, bound: true, isNew: false, deviceId: normalizedId };
    }

    const countRes = await client.query(
      `SELECT COUNT(*)::int AS c FROM devices WHERE user_id = $1`,
      [user.id]
    );
    let count = countRes.rows[0].c;

    if (count >= user.maxDevices) {
      if (!rebindIfFull) {
        throw new DeviceLimitError();
      }
      // OTP-proven rebind: free a slot (all slots if single-device account)
      if (user.maxDevices <= 1) {
        await client.query(`DELETE FROM devices WHERE user_id = $1`, [user.id]);
      } else {
        await deleteOldestDevice(client, user.id);
      }
      const again = await client.query(
        `SELECT COUNT(*)::int AS c FROM devices WHERE user_id = $1`,
        [user.id]
      );
      count = again.rows[0].c;
      if (count >= user.maxDevices) {
        await client.query(`DELETE FROM devices WHERE user_id = $1`, [user.id]);
      }
    }

    await client.query(
      `INSERT INTO devices (user_id, device_id, device_label)
       VALUES ($1, $2, $3)
       ON CONFLICT (user_id, device_id) DO UPDATE
         SET last_seen_at = NOW(),
             device_label = COALESCE(EXCLUDED.device_label, devices.device_label)`,
      [user.id, normalizedId, label]
    );
    return { user, bound: true, isNew: true, deviceId: normalizedId };
  });
}

/**
 * Entitlement for app gates. Touches existing binds; only inserts when under cap.
 * Does not steal another device's slot (use login rebind for that).
 */
async function checkEntitlement(phone, deviceId, deviceLabel = null) {
  const normalizedId = normalizeDeviceId(deviceId);
  if (!normalizedId) {
    const err = new Error('Valid deviceId required');
    err.status = 400;
    throw err;
  }

  const user = await ensureUser(phone);
  if (user.status === 'blocked') {
    const err = new Error('Account is blocked');
    err.status = 403;
    err.code = 'BLOCKED';
    throw err;
  }

  const devices = await listDevicesForUser(user.id);
  const already = devices.some((d) => d.deviceId === normalizedId);

  if (already) {
    await assertDeviceAllowed(phone, normalizedId, deviceLabel, { rebindIfFull: false });
  } else if (devices.length < user.maxDevices) {
    await assertDeviceAllowed(phone, normalizedId, deviceLabel, { rebindIfFull: false });
  } else {
    // At capacity with a new install id — do not auto-bind on status polls
    throw new DeviceLimitError(
      'This account is linked to another device. Sign in again with OTP on this phone to move your subscription here.'
    );
  }

  const refreshed = await listDevicesForUser(user.id);
  const active = isSubscriptionActive(user);

  return {
    active,
    blocked: false,
    deviceAllowed: true,
    maxDevices: user.maxDevices,
    boundDeviceCount: refreshed.length,
    subscriptionStart: user.subscriptionStart,
    subscriptionEnd: user.subscriptionEnd,
    planType: user.planType,
  };
}

async function removeDevice(phone, deviceId) {
  const user = await getUserByPhone(phone);
  if (!user) return false;
  const normalizedId = normalizeDeviceId(deviceId);
  if (!normalizedId) return false;
  const { rowCount } = await query(
    `DELETE FROM devices WHERE user_id = $1 AND device_id = $2`,
    [user.id, normalizedId]
  );
  return rowCount > 0;
}

async function resetDevices(phone) {
  const user = await getUserByPhone(phone);
  if (!user) return 0;
  const { rowCount } = await query(`DELETE FROM devices WHERE user_id = $1`, [user.id]);
  return rowCount;
}

module.exports = {
  DeviceLimitError,
  listDevicesForUser,
  assertDeviceAllowed,
  checkEntitlement,
  removeDevice,
  resetDevices,
  normalizeDeviceId,
};
