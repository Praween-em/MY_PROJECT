/**
 * device.js — device binding / entitlement
 */

const { query, withTransaction } = require('../config/db');
const { getUserByPhone, ensureUser } = require('./user');
const { isSubscriptionActive } = require('./mapUser');

class DeviceLimitError extends Error {
  constructor(message = 'This account is linked to another device. Contact support.') {
    super(message);
    this.name = 'DeviceLimitError';
    this.code = 'DEVICE_LIMIT';
    this.status = 403;
  }
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

/**
 * Bind or touch a device for the user.
 * @throws {DeviceLimitError} when a new device would exceed max_devices
 */
async function assertDeviceAllowed(phone, deviceId, deviceLabel = null) {
  if (!deviceId || typeof deviceId !== 'string' || deviceId.length < 8) {
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

  return withTransaction(async (client) => {
    const existing = await client.query(
      `SELECT id FROM devices WHERE user_id = $1 AND device_id = $2`,
      [user.id, deviceId]
    );

    if (existing.rows.length) {
      await client.query(
        `UPDATE devices SET last_seen_at = NOW(),
           device_label = COALESCE($1, device_label)
         WHERE user_id = $2 AND device_id = $3`,
        [deviceLabel, user.id, deviceId]
      );
      return { user, bound: true, isNew: false };
    }

    const countRes = await client.query(
      `SELECT COUNT(*)::int AS c FROM devices WHERE user_id = $1`,
      [user.id]
    );
    const count = countRes.rows[0].c;
    if (count >= user.maxDevices) {
      throw new DeviceLimitError();
    }

    await client.query(
      `INSERT INTO devices (user_id, device_id, device_label)
       VALUES ($1, $2, $3)`,
      [user.id, deviceId, deviceLabel]
    );
    return { user, bound: true, isNew: true };
  });
}

async function checkEntitlement(phone, deviceId, deviceLabel = null) {
  const { user } = await assertDeviceAllowed(phone, deviceId, deviceLabel);
  const devices = await listDevicesForUser(user.id);
  const active = isSubscriptionActive(user);

  return {
    active,
    blocked: user.status === 'blocked',
    deviceAllowed: true,
    maxDevices: user.maxDevices,
    boundDeviceCount: devices.length,
    subscriptionStart: user.subscriptionStart,
    subscriptionEnd: user.subscriptionEnd,
    planType: user.planType,
  };
}

async function removeDevice(phone, deviceId) {
  const user = await getUserByPhone(phone);
  if (!user) return false;
  const { rowCount } = await query(
    `DELETE FROM devices WHERE user_id = $1 AND device_id = $2`,
    [user.id, deviceId]
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
};
