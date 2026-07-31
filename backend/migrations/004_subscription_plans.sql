-- Subscription plans (amounts + duration editable from admin; app reads via GET /subscription/plans)
CREATE TABLE IF NOT EXISTS subscription_plans (
  id VARCHAR(32) PRIMARY KEY,
  label VARCHAR(64) NOT NULL,
  amount INTEGER NOT NULL CHECK (amount >= 100),
  duration_days INTEGER NOT NULL CHECK (duration_days >= 1 AND duration_days <= 730),
  description TEXT NOT NULL DEFAULT '',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO subscription_plans (id, label, amount, duration_days, description, enabled, sort_order) VALUES
  ('monthly',   '1 Month',   29900, 30, '30-day access to Super Ridex', TRUE, 1),
  ('quarterly', '3 Months',  67500, 90, '90-day access to Super Ridex', TRUE, 2)
ON CONFLICT (id) DO NOTHING;
