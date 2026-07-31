/**
 * plans.js — DB-backed subscription plans (prices + duration)
 */

const { query } = require('../config/db');

const ALLOWED_PLAN_IDS = new Set(['monthly', 'quarterly']);

function formatInr(paise) {
  const rupees = paise / 100;
  return Number.isInteger(rupees) ? `₹${rupees}` : `₹${rupees.toFixed(1)}`;
}

function formatPerDay(paise, days) {
  if (!days) return '';
  const perDay = paise / 100 / days;
  return `₹${perDay.toFixed(1)} / day`;
}

function mapRow(r) {
  return {
    id: r.id,
    label: r.label,
    amount: r.amount,
    amountInr: Math.round(r.amount / 100),
    priceDisplay: formatInr(r.amount),
    durationDays: r.duration_days,
    duration: `${r.duration_days} days`,
    description: r.description || '',
    enabled: !!r.enabled,
    sortOrder: r.sort_order,
    perDay: formatPerDay(r.amount, r.duration_days),
    updatedAt: r.updated_at,
  };
}

async function getPlanById(planId, { enabledOnly = false } = {}) {
  const id = String(planId || '').trim();
  if (!ALLOWED_PLAN_IDS.has(id)) return null;

  const sql = enabledOnly
    ? `SELECT * FROM subscription_plans WHERE id = $1 AND enabled = TRUE`
    : `SELECT * FROM subscription_plans WHERE id = $1`;
  const { rows } = await query(sql, [id]);
  return rows[0] ? mapRow(rows[0]) : null;
}

async function listPlans({ enabledOnly = false } = {}) {
  const sql = enabledOnly
    ? `SELECT * FROM subscription_plans WHERE enabled = TRUE ORDER BY sort_order ASC, id ASC`
    : `SELECT * FROM subscription_plans ORDER BY sort_order ASC, id ASC`;
  const { rows } = await query(sql);
  return rows.map(mapRow);
}

async function listPublicPlans() {
  return listPlans({ enabledOnly: true });
}

/**
 * Admin update — body: { plans: [{ id, label?, amount?, durationDays?, description?, enabled?, sortOrder? }] }
 */
async function upsertPlans(input = {}) {
  const items = Array.isArray(input.plans) ? input.plans : [];
  if (!items.length) {
    const err = new Error('plans array required');
    err.status = 400;
    throw err;
  }

  const updated = [];
  for (const item of items) {
    const id = String(item.id || '').trim();
    if (!ALLOWED_PLAN_IDS.has(id)) continue;

    const existing = await getPlanById(id);
    if (!existing) continue;

    const label = item.label != null ? String(item.label).trim().slice(0, 64) : existing.label;
    const amount = item.amount != null ? Number(item.amount) : existing.amount;
    const durationDays = item.durationDays != null ? Number(item.durationDays) : existing.durationDays;
    const description = item.description != null ? String(item.description).trim() : existing.description;
    const enabled = item.enabled === undefined ? existing.enabled : !!item.enabled;
    const sortOrder = item.sortOrder != null ? Number(item.sortOrder) : existing.sortOrder;

    if (!Number.isFinite(amount) || amount < 100) {
      const err = new Error(`${id}: amount must be at least 100 paise (₹1)`);
      err.status = 400;
      throw err;
    }
    if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 730) {
      const err = new Error(`${id}: durationDays must be 1–730`);
      err.status = 400;
      throw err;
    }

    const { rows } = await query(
      `UPDATE subscription_plans SET
         label = $1,
         amount = $2,
         duration_days = $3,
         description = $4,
         enabled = $5,
         sort_order = $6,
         updated_at = NOW()
       WHERE id = $7
       RETURNING *`,
      [label || existing.label, Math.round(amount), durationDays, description, enabled, sortOrder, id]
    );
    if (rows[0]) updated.push(mapRow(rows[0]));
  }

  return updated;
}

module.exports = {
  ALLOWED_PLAN_IDS,
  getPlanById,
  listPlans,
  listPublicPlans,
  upsertPlans,
};
