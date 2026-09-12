-- Telegram-assisted payment contact configuration.
-- The image is stored in Postgres so Railway redeploys do not delete it.
CREATE TABLE IF NOT EXISTS payment_contact_config (
  id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  telegram_url TEXT NOT NULL DEFAULT '',
  image_mime VARCHAR(32),
  image_data BYTEA,
  image_updated_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO payment_contact_config (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;
