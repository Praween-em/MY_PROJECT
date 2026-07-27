-- App social links (editable from admin; served to mobile app)
CREATE TABLE IF NOT EXISTS social_links (
  key VARCHAR(32) PRIMARY KEY,
  label VARCHAR(64) NOT NULL,
  url TEXT NOT NULL DEFAULT '',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO social_links (key, label, url, enabled, sort_order) VALUES
  ('whatsapp',  'WhatsApp',  'https://whatsapp.com/channel/0029Vb8CrnM72WTtKSpCMZ09', TRUE, 1),
  ('instagram', 'Instagram', 'https://www.instagram.com/', TRUE, 2),
  ('youtube',   'YouTube',   'https://www.youtube.com/', TRUE, 3),
  ('telegram',  'Telegram',  '', FALSE, 4),
  ('support',   'Support',   '', FALSE, 5)
ON CONFLICT (key) DO NOTHING;
