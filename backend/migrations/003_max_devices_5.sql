-- Raise per-user max_devices ceiling from 3 → 5
-- Run: npm run migrate

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_max_devices_check;
ALTER TABLE users
  ADD CONSTRAINT users_max_devices_check
  CHECK (max_devices >= 1 AND max_devices <= 5);
