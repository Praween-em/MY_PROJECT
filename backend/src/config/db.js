/**
 * db.js — PostgreSQL pool (Railway / local via DATABASE_URL)
 */

const { Pool } = require('pg');

const connectionString = process.env.DATABASE_URL;

if (!connectionString) {
  console.warn('[db] DATABASE_URL is not set — database calls will fail until configured.');
}

function shouldUseSsl(url) {
  if (process.env.DATABASE_SSL === 'false') return false;
  if (process.env.DATABASE_SSL === 'true') return true;
  if (process.env.RAILWAY_ENVIRONMENT) return true;
  if (process.env.NODE_ENV === 'production') return true;
  return /railway\.app|rlwy\.net|proxy\.rlwy/i.test(String(url || ''));
}

const pool = new Pool({
  connectionString,
  ssl: shouldUseSsl(connectionString) ? { rejectUnauthorized: false } : undefined,
  max: 10,
  connectionTimeoutMillis: 8000,
});

pool.on('error', (err) => {
  console.error('[db] unexpected pool error', err);
});

async function query(text, params) {
  return pool.query(text, params);
}

async function withClient(fn) {
  const client = await pool.connect();
  try {
    return await fn(client);
  } finally {
    client.release();
  }
}

async function withTransaction(fn) {
  return withClient(async (client) => {
    await client.query('BEGIN');
    try {
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    }
  });
}

async function ping() {
  const { rows } = await query('SELECT 1 AS ok');
  return rows[0]?.ok === 1;
}

module.exports = {
  pool,
  query,
  withClient,
  withTransaction,
  ping,
};
