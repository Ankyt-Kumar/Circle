-- Additive: apply once to an existing Circle database; preserve circles/joins.
ALTER TABLE users ADD COLUMN IF NOT EXISTS firebase_uid text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS firebase_project_id text;
CREATE UNIQUE INDEX IF NOT EXISTS users_firebase_identity_unique
  ON users(firebase_project_id, firebase_uid);
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status text NOT NULL DEFAULT 'active'
  CHECK (account_status IN ('active', 'suspended'));
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_complete boolean NOT NULL DEFAULT false;
UPDATE users SET profile_complete = true WHERE firebase_uid IS NULL;
