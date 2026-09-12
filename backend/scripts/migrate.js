/**
 * migrate.js — apply SQL migrations in order
 * Usage: npm run migrate
 */

const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const envFile = ['.env', '.env.dev'].map((f) => path.join(root, f)).find((p) => fs.existsSync(p));
if (envFile) require('dotenv').config({ path: envFile });

const { pool, query } = require('../src/config/db');

async function runMigrations({ closePool = false } = {}) {
  if (!process.env.DATABASE_URL) {
    throw new Error('DATABASE_URL is required');
  }

  await query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      id SERIAL PRIMARY KEY,
      filename TEXT NOT NULL UNIQUE,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `);

  const dir = path.join(__dirname, '..', 'migrations');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();

  for (const file of files) {
    const { rows } = await query('SELECT 1 FROM schema_migrations WHERE filename = $1', [file]);
    if (rows.length) {
      console.log(`skip  ${file}`);
      continue;
    }
    const sql = fs.readFileSync(path.join(dir, file), 'utf8');
    console.log(`apply ${file}`);
    await query(sql);
    await query('INSERT INTO schema_migrations (filename) VALUES ($1)', [file]);
  }

  console.log('Migrations complete.');
  if (closePool) await pool.end();
}

if (require.main === module) {
  runMigrations({ closePool: true }).catch(async (err) => {
    console.error(err);
    try { await pool.end(); } catch { /* ignore */ }
    process.exit(1);
  });
}

module.exports = { runMigrations };
