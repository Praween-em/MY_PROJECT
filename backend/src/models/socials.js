/**
 * socials.js — app social / help links (DB-backed)
 */

const { query } = require('../config/db');

const ALLOWED_KEYS = new Set([
  'whatsapp',
  'instagram',
  'youtube',
  'telegram',
  'support',
  'facebook',
  'twitter',
  'website',
]);

function mapRow(r) {
  return {
    key: r.key,
    label: r.label,
    url: r.url || '',
    enabled: !!r.enabled,
    sortOrder: r.sort_order,
    updatedAt: r.updated_at,
  };
}

async function listSocialLinks({ enabledOnly = false } = {}) {
  const sql = enabledOnly
    ? `SELECT key, label, url, enabled, sort_order, updated_at
       FROM social_links
       WHERE enabled = TRUE AND TRIM(url) <> ''
       ORDER BY sort_order ASC, key ASC`
    : `SELECT key, label, url, enabled, sort_order, updated_at
       FROM social_links
       ORDER BY sort_order ASC, key ASC`;
  const { rows } = await query(sql);
  return rows.map(mapRow);
}

/** Public shape for the mobile app: { whatsapp: url, instagram: url, ... } + links[] */
async function getPublicSocials() {
  const links = await listSocialLinks({ enabledOnly: true });
  const map = {};
  for (const l of links) {
    map[l.key] = l.url;
  }
  return { links, ...map };
}

/**
 * Upsert one or many links.
 * Body examples:
 *   { whatsapp: 'https://...', instagram: 'https://...' }
 *   { links: [{ key, url, label?, enabled?, sortOrder? }] }
 */
async function upsertSocialLinks(input = {}) {
  const items = [];

  if (Array.isArray(input.links)) {
    for (const l of input.links) {
      if (!l || !l.key) continue;
      items.push(l);
    }
  }

  for (const [key, value] of Object.entries(input)) {
    if (key === 'links') continue;
    if (!ALLOWED_KEYS.has(key)) continue;
    if (typeof value === 'string') {
      items.push({ key, url: value });
    } else if (value && typeof value === 'object') {
      items.push({ key, ...value });
    }
  }

  if (!items.length) {
    const err = new Error('No social links to update');
    err.status = 400;
    throw err;
  }

  const updated = [];
  for (const item of items) {
    const key = String(item.key || '').trim().toLowerCase();
    if (!ALLOWED_KEYS.has(key)) continue;

    const url = item.url != null ? String(item.url).trim() : '';
    const label = item.label != null
      ? String(item.label).trim().slice(0, 64)
      : key.charAt(0).toUpperCase() + key.slice(1);
    const enabled = item.enabled === undefined ? (url.length > 0) : !!item.enabled;
    const sortOrder = Number.isFinite(Number(item.sortOrder))
      ? Number(item.sortOrder)
      : 0;

    const { rows } = await query(
      `INSERT INTO social_links (key, label, url, enabled, sort_order, updated_at)
       VALUES ($1, $2, $3, $4, $5, NOW())
       ON CONFLICT (key) DO UPDATE SET
         label = EXCLUDED.label,
         url = EXCLUDED.url,
         enabled = EXCLUDED.enabled,
         sort_order = CASE
           WHEN EXCLUDED.sort_order > 0 THEN EXCLUDED.sort_order
           ELSE social_links.sort_order
         END,
         updated_at = NOW()
       RETURNING key, label, url, enabled, sort_order, updated_at`,
      [key, label || key, url, enabled, sortOrder]
    );
    if (rows[0]) updated.push(mapRow(rows[0]));
  }

  return updated;
}

module.exports = {
  listSocialLinks,
  getPublicSocials,
  upsertSocialLinks,
  ALLOWED_KEYS,
};
