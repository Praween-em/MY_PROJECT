/**
 * Backend-managed Telegram payment destination and payment-screen image.
 */

const { query } = require('../config/db');

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const ALLOWED_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

function normalizeTelegramUrl(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';

  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    const err = new Error('Enter a valid Telegram URL');
    err.status = 400;
    throw err;
  }

  const host = parsed.hostname.toLowerCase();
  if (parsed.protocol !== 'https:' || !['t.me', 'telegram.me', 'www.telegram.me'].includes(host)) {
    const err = new Error('Telegram URL must use https://t.me/ or https://telegram.me/');
    err.status = 400;
    throw err;
  }
  return parsed.toString();
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
  if (!ALLOWED_IMAGE_TYPES.has(mime)) {
    const err = new Error('Payment image must be PNG, JPEG, or WebP');
    err.status = 415;
    throw err;
  }

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
