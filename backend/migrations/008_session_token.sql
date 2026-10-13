-- Login session. Phone-as-bearer still works for one release of already-installed apps.
ALTER TABLE users ADD COLUMN IF NOT EXISTS session_token TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_session_token
  ON users (session_token)
  WHERE session_token IS NOT NULL;
