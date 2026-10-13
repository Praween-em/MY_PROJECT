/**
 * Backend-managed Telegram payment destination and payment-screen image.
 */

const { query } = require('../config/db');

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const ALLOWED_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

function normalizeTelegramUrl(value) {
  let raw = String(value || '').trim();
  if (!raw) return '';

  if (raw.startsWith('@')) {
    const name = raw.slice(1).replace(/^\/+/, '');
    if (!/^[A-Za-z0-9_]{4,64}$/.test(name)) {
      const err = new Error('Enter a Telegram username like @your_name');
      err.status = 400;
      throw err;
    }
    raw = `https://t.me/${name}`;
  } else if (!/^https?:\/\//i.test(raw)) {
    raw = `https://${raw.replace(/^\/+/, '')}`;
  }

  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    const err = new Error('Enter a Telegram link, like https://t.me/your_name or @your_name');
    err.status = 400;
    throw err;
  }

  const host = parsed.hostname.toLowerCase().replace(/^www\./, '');
  if (!['t.me', 'telegram.me'].includes(host)) {
    const err = new Error('Telegram link must be t.me or telegram.me');
    err.status = 400;
    throw err;
  }
  if (!parsed.pathname || parsed.pathname === '/') {
    const err = new Error('Telegram link needs a username or invite path');
    err.status = 400;
    throw err;
  }

  parsed.protocol = 'https:';
  parsed.hostname = host;
  return parsed.toString();
}

function sniffImageMime(buffer) {
  if (buffer.length >= 8
      && buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) {
    return 'image/png';
  }
  if (buffer.length >= 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return 'image/jpeg';
  }
  if (buffer.length >= 12
      && buffer.toString('ascii', 0, 4) === 'RIFF'
      && buffer.toString('ascii', 8, 12) === 'WEBP') {
    return 'image/webp';
  }
  return null;
}

function mapConfig(row) {
  return {
    telegramUrl: row?.telegram_url || '',
    hasImage: !!row?.has_image,
    imageUpdatedAt: row?.image_updated_at || null,
    updatedAt: row?.updated_at || null,
  };
}

async function getPaymentContactConfig() {
  const { rows } = await query(
    `SELECT telegram_url,
            image_data IS NOT NULL AS has_image,
            image_updated_at,
            updated_at
       FROM payment_contact_config
      WHERE id = 1`
  );
  return mapConfig(rows[0]);
}

async function updateTelegramUrl(value) {
  const telegramUrl = normalizeTelegramUrl(value);
  const { rows } = await query(
    `INSERT INTO payment_contact_config (id, telegram_url, updated_at)
     VALUES (1, $1, NOW())
     ON CONFLICT (id) DO UPDATE SET
       telegram_url = EXCLUDED.telegram_url,
       updated_at = NOW()
     RETURNING telegram_url,
               image_data IS NOT NULL AS has_image,
               image_updated_at,
               updated_at`,
    [telegramUrl]
  );
  return mapConfig(rows[0]);
}

async function savePaymentImage(buffer, mime) {
  if (!Buffer.isBuffer(buffer) || buffer.length === 0) {
    const err = new Error('Choose a payment image to upload');
    err.status = 400;
    throw err;
  }
  if (buffer.length > MAX_IMAGE_BYTES) {
    const err = new Error('Payment image must be 2 MB or smaller');
    err.status = 413;
    throw err;
  }
  const sniffed = sniffImageMime(buffer);
  if (!sniffed || !ALLOWED_IMAGE_TYPES.has(sniffed)) {
    const err = new Error('Payment image must be PNG, JPEG, or WebP');
    err.status = 415;
    throw err;
  }
  mime = sniffed;

  const { rows } = await query(
    `INSERT INTO payment_contact_config
       (id, image_mime, image_data, image_updated_at, updated_at)
     VALUES (1, $1, $2, NOW(), NOW())
     ON CONFLICT (id) DO UPDATE SET
       image_mime = EXCLUDED.image_mime,
       image_data = EXCLUDED.image_data,
       image_updated_at = NOW(),
       updated_at = NOW()
     RETURNING telegram_url,
               image_data IS NOT NULL AS has_image,
               image_updated_at,
               updated_at`,
    [mime, buffer]
  );
  return mapConfig(rows[0]);
}

async function getPaymentImage() {
  const { rows } = await query(
    `SELECT image_mime, image_data, image_updated_at
       FROM payment_contact_config
      WHERE id = 1`
  );
  const row = rows[0];
  if (!row?.image_data) return null;
  return {
    mime: row.image_mime || 'image/png',
    data: row.image_data,
    updatedAt: row.image_updated_at,
  };
}

async function removePaymentImage() {
  const { rows } = await query(
    `UPDATE payment_contact_config
        SET image_mime = NULL,
            image_data = NULL,
            image_updated_at = NULL,
            updated_at = NOW()
      WHERE id = 1
      RETURNING telegram_url,
                FALSE AS has_image,
                image_updated_at,
                updated_at`
  );
  return mapConfig(rows[0]);
}

module.exports = {
  MAX_IMAGE_BYTES,
  ALLOWED_IMAGE_TYPES,
  normalizeTelegramUrl,
  getPaymentContactConfig,
  updateTelegramUrl,
  savePaymentImage,
  getPaymentImage,
  removePaymentImage,
};
