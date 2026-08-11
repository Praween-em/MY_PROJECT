-- 3-day trial plan (₹99) — try Super Ridex before monthly/quarterly
INSERT INTO subscription_plans (id, label, amount, duration_days, description, enabled, sort_order) VALUES
  ('trial', '3-Day Trial', 9900, 3, '3-day trial — full auto-accept access', TRUE, 0)
ON CONFLICT (id) DO UPDATE SET
  label = EXCLUDED.label,
  amount = EXCLUDED.amount,
  duration_days = EXCLUDED.duration_days,
  description = EXCLUDED.description,
  enabled = EXCLUDED.enabled,
  sort_order = EXCLUDED.sort_order,
  updated_at = NOW();
