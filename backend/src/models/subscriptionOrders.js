/**
 * Manual subscription orders. The user places one; an admin confirms it.
 */

const { query } = require('../config/db');
const { getPlanById } = require('./plans');
const { getUserByPhone } = require('./user');

function mapOrder(row) {
  if (!row) return null;
  return {
    id: row.id,
    phone: row.phone,
    planId: row.plan_id,
    planLabel: row.plan_label,
    amount: row.amount,
    amountInr: Math.round(row.amount / 100),
    priceDisplay: Number.isInteger(row.amount / 100)
      ? `₹${row.amount / 100}`
      : `₹${(row.amount / 100).toFixed(1)}`,
    durationDays: row.duration_days,
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    confirmedAt: row.confirmed_at,
  };
}

async function getOrderById(id) {
  const { rows } = await query(`SELECT * FROM subscription_orders WHERE id = $1`, [id]);
  return mapOrder(rows[0]);
}

async function getAwaitingOrder(phone) {
  const { rows } = await query(
    `SELECT * FROM subscription_orders
     WHERE phone = $1 AND status = 'awaiting_confirmation'
     ORDER BY created_at DESC
     LIMIT 1`,
    [phone]
  );
  return mapOrder(rows[0]);
}

async function listAwaitingOrders(limit = 100) {
  const n = Math.min(200, Math.max(1, Number(limit) || 100));
  const { rows } = await query(
    `SELECT * FROM subscription_orders
     WHERE status = 'awaiting_confirmation'
     ORDER BY created_at ASC
     LIMIT $1`,
    [n]
  );
  return rows.map(mapOrder);
}

/**
 * Replace any open order for this phone with a new awaiting order.
 */
async function placeOrder(phone, planId) {
  const user = await getUserByPhone(phone);
  if (!user) {
    const err = new Error('Log in with OTP before choosing a plan.');
    err.status = 404;
    throw err;
  }

  const plan = await getPlanById(planId, { enabledOnly: true });
  if (!plan) {
    const err = new Error('That plan is not available.');
    err.status = 400;
    throw err;
  }

  await query(
    `UPDATE subscription_orders
     SET status = 'cancelled', updated_at = NOW()
     WHERE phone = $1 AND status = 'awaiting_confirmation'`,
    [phone]
  );

  const { rows } = await query(
    `INSERT INTO subscription_orders
       (phone, plan_id, plan_label, amount, duration_days, status)
     VALUES ($1, $2, $3, $4, $5, 'awaiting_confirmation')
     RETURNING *`,
    [phone, plan.id, plan.label, plan.amount, plan.durationDays]
  );
  return mapOrder(rows[0]);
}

async function setOrderStatus(id, status) {
  const confirmedAt = status === 'confirmed' ? new Date().toISOString() : null;
  const { rows } = await query(
    `UPDATE subscription_orders
     SET status = $2,
         updated_at = NOW(),
         confirmed_at = COALESCE($3::timestamptz, confirmed_at)
     WHERE id = $1 AND status = 'awaiting_confirmation'
     RETURNING *`,
    [id, status, confirmedAt]
  );
  return mapOrder(rows[0]);
}

module.exports = {
  getOrderById,
  getAwaitingOrder,
  listAwaitingOrders,
  placeOrder,
  setOrderStatus,
};
