-- Stop the API; apply after 008. Additive and repeatable.
CREATE TABLE IF NOT EXISTS public_venues (
 id text PRIMARY KEY, name text NOT NULL CHECK(length(name) BETWEEN 3 AND 160),
 neighborhood text NOT NULL CHECK(length(neighborhood) BETWEEN 1 AND 100),
 latitude double precision NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude double precision NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 verified_public boolean NOT NULL DEFAULT false, fictional boolean NOT NULL DEFAULT true,
 active boolean NOT NULL DEFAULT true, reviewed_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public_venues(id,name,neighborhood,latitude,longitude,verified_public) VALUES
 ('koramangala-cafe','Sample public café','Koramangala',12.9352,77.6245,true),
 ('indiranagar-cafe','Sample public board-game café','Indiranagar',12.9719,77.6412,true),
 ('btm-park','Sample public park','BTM Layout',12.9166,77.6101,true) ON CONFLICT DO NOTHING;
ALTER TABLE circles ADD COLUMN IF NOT EXISTS venue_id text REFERENCES public_venues(id);
ALTER TABLE circles ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 1;
UPDATE circles c SET venue_id=v.id FROM public_venues v WHERE c.venue_id IS NULL AND c.venue=v.name AND c.neighborhood=v.neighborhood;
CREATE TABLE IF NOT EXISTS chat_messages (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 circle_id text NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
 author_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 client_id text NOT NULL CHECK(length(client_id) BETWEEN 16 AND 80),
 body text NOT NULL CHECK(length(body) BETWEEN 1 AND 1000),
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','allowed','rejected')),
 created_at timestamptz NOT NULL DEFAULT now(), moderated_at timestamptz,
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 lease_token text NOT NULL DEFAULT '', lease_until timestamptz,
 review_required boolean NOT NULL DEFAULT false, reason text NOT NULL DEFAULT 'awaiting_review',
 UNIQUE(circle_id,author_id,client_id)
);
CREATE INDEX IF NOT EXISTS chat_circle_page ON chat_messages(circle_id,id);
CREATE INDEX IF NOT EXISTS chat_pending ON chat_messages(next_attempt_at) WHERE status='pending' AND NOT review_required;
CREATE TABLE IF NOT EXISTS chat_review_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,message_id bigint, circle_id text NOT NULL,
 actor text NOT NULL, action text NOT NULL, reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS notification_preferences (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 reminders boolean NOT NULL DEFAULT true, changes boolean NOT NULL DEFAULT true, push_enabled boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS device_tokens (
 token text PRIMARY KEY CHECK(length(token) BETWEEN 20 AND 4096),user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS notification_outbox (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 circle_id text, kind text NOT NULL, event_key text NOT NULL UNIQUE,
 title text NOT NULL, body text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 read_at timestamptz, delivered_at timestamptz, attempts integer NOT NULL DEFAULT 0,
 lease_until timestamptz, lease_token text NOT NULL DEFAULT '', next_attempt_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS inbox_user ON notification_outbox(user_id,id);
CREATE TABLE IF NOT EXISTS circle_engagement (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 circle_id text NOT NULL, joined_at timestamptz NOT NULL DEFAULT now(), attended boolean,
 feedback text NOT NULL DEFAULT '' CHECK(feedback IN ('','good','okay','poor')),
 PRIMARY KEY(user_id,circle_id)
);
INSERT INTO circle_engagement(user_id,circle_id,joined_at) SELECT user_id,circle_id,joined_at FROM circle_members ON CONFLICT DO NOTHING;
CREATE OR REPLACE FUNCTION remember_circle_join() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO circle_engagement(user_id,circle_id,joined_at) VALUES(NEW.user_id,NEW.circle_id,NEW.joined_at) ON CONFLICT DO NOTHING;
 RETURN NEW; END; $$;
DROP TRIGGER IF EXISTS circle_remember_join ON circle_members;
CREATE TRIGGER circle_remember_join AFTER INSERT ON circle_members FOR EACH ROW EXECUTE FUNCTION remember_circle_join();
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_account_status_check;
ALTER TABLE users ADD CONSTRAINT users_account_status_check CHECK(account_status IN ('active','suspended','deleting'));
CREATE TABLE IF NOT EXISTS account_deletion_jobs (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, firebase_uid text NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS ai_feature_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, feature text NOT NULL, model text NOT NULL,
 result text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
-- Editing a circle invalidates any old review. Attempts remain monotonically
-- increasing in audit; a new revision gets a fresh job, with old history retained.
ALTER TABLE circle_review_jobs DROP CONSTRAINT IF EXISTS circle_review_jobs_attempts_check;
ALTER TABLE circle_review_jobs ADD CONSTRAINT circle_review_jobs_attempts_check CHECK(attempts>=0);
ALTER TABLE circle_review_jobs ADD COLUMN IF NOT EXISTS revision_start_attempt integer NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS feature_requests(user_id uuid REFERENCES users(id) ON DELETE CASCADE,feature text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS feature_requests_user ON feature_requests(user_id,created_at);
CREATE TABLE IF NOT EXISTS embedding_jobs(circle_id text PRIMARY KEY REFERENCES circles(id) ON DELETE CASCADE,next_attempt_at timestamptz NOT NULL);
ALTER TABLE embedding_jobs ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 0;
ALTER TABLE embedding_jobs ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS public_venue_audit(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, venue_id text NOT NULL, actor text NOT NULL, action text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
