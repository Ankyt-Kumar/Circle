-- Apply after 006. Repeatable; preserve existing published circles.
ALTER TABLE circles DROP CONSTRAINT IF EXISTS circles_status_check;
ALTER TABLE circles ADD CONSTRAINT circles_status_check CHECK(status IN ('published','pending_review','rejected','cancelled','archived'));
ALTER TABLE circles ALTER COLUMN status SET DEFAULT 'pending_review';
CREATE INDEX IF NOT EXISTS circles_review_queue ON circles(starts_at,id) WHERE status='pending_review';
CREATE TABLE IF NOT EXISTS user_blocks (
 blocker_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 blocked_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(blocker_id,blocked_id), CHECK(blocker_id<>blocked_id)
);
CREATE INDEX IF NOT EXISTS blocks_reverse ON user_blocks(blocked_id,blocker_id);
CREATE TABLE IF NOT EXISTS safety_reports (
 id text PRIMARY KEY, reporter_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 circle_id text REFERENCES circles(id) ON DELETE SET NULL, target_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
 original_circle_id text NOT NULL, original_target_id text NOT NULL DEFAULT '',
 reason text NOT NULL CHECK(reason IN ('harassment','spam','unsafe_meeting','other')),
 details text NOT NULL CHECK(length(details)<=1000), snapshot jsonb NOT NULL, payload_hash text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), status text NOT NULL DEFAULT 'open' CHECK(status IN ('open','reviewed','dismissed'))
);
CREATE INDEX IF NOT EXISTS reports_queue ON safety_reports(created_at,id) WHERE status='open';
CREATE INDEX IF NOT EXISTS reports_reporter ON safety_reports(reporter_id,created_at);
CREATE TABLE IF NOT EXISTS safety_review_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 subject_type text NOT NULL CHECK(subject_type IN ('circle','report')), subject_id text NOT NULL,
 actor text NOT NULL CHECK(length(actor) BETWEEN 1 AND 100), action text NOT NULL,
 note text NOT NULL CHECK(length(note) BETWEEN 1 AND 1000), created_at timestamptz NOT NULL DEFAULT now()
);
