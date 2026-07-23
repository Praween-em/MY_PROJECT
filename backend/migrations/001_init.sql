-- SUPER RIDEX initial schema (PostgreSQL)
-- Run: npm run migrate

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  phone VARCHAR(10) NOT NULL UNIQUE,
  referral_code VARCHAR(32) NOT NULL UNIQUE,
  referred_by_phone VARCHAR(10),
  referral_payment_counted BOOLEAN NOT NULL DEFAULT FALSE,
  total_referrals INTEGER NOT NULL DEFAULT 0,
  paid_referrals INTEGER NOT NULL DEFAULT 0,
  referral_rewards_earned INTEGER NOT NULL DEFAULT 0,
  subscription_start TIMESTAMPTZ,
  subscription_end TIMESTAMPTZ,
  plan_type VARCHAR(32),
  razorpay_order_id VARCHAR(128),
  razorpay_payment_id VARCHAR(128),
  max_devices INTEGER NOT NULL DEFAULT 1 CHECK (max_devices >= 1 AND max_devices <= 3),
  status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_referral_code ON users (referral_code);
CREATE INDEX IF NOT EXISTS idx_users_subscription_end ON users (subscription_end);

CREATE TABLE IF NOT EXISTS devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id VARCHAR(128) NOT NULL,
  device_label VARCHAR(256),
  bound_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices (user_id);
CREATE INDEX IF NOT EXISTS idx_devices_device_id ON devices (device_id);

CREATE TABLE IF NOT EXISTS payments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  order_id VARCHAR(128) NOT NULL,
  payment_id VARCHAR(128) NOT NULL UNIQUE,
  plan_type VARCHAR(32) NOT NULL,
  amount INTEGER,
  status VARCHAR(32) NOT NULL DEFAULT 'captured',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments (user_id);

CREATE TABLE IF NOT EXISTS admin_users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'admin',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id UUID REFERENCES admin_users(id) ON DELETE SET NULL,
  action VARCHAR(64) NOT NULL,
  target_phone VARCHAR(10),
  details JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_created ON admin_audit_logs (created_at DESC);
