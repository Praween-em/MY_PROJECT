-- Manual plan orders. The app records the chosen plan; an admin confirms it.
-- No payment gateway captures money here.
CREATE TABLE IF NOT EXISTS subscription_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone VARCHAR(10) NOT NULL,
  plan_id VARCHAR(32) NOT NULL,
  plan_label VARCHAR(64) NOT NULL,
  amount INTEGER NOT NULL,
  duration_days INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'awaiting_confirmation'
    CHECK (status IN ('awaiting_confirmation', 'confirmed', 'rejected', 'cancelled')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  confirmed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_subscription_orders_status_created
  ON subscription_orders (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_subscription_orders_phone
  ON subscription_orders (phone, created_at DESC);
