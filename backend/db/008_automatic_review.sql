-- Stop the API before applying. Additive and repeatable after 007.
-- Creation history survives circle deletion; no names or descriptions here.
CREATE TABLE IF NOT EXISTS circle_creator_history (
 circle_id text PRIMARY KEY,
 creator_id uuid REFERENCES users(id) ON DELETE SET NULL,
 created_at timestamptz DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS creator_history_user ON circle_creator_history(creator_id,created_at);
-- Pending circles have only their creator: joining is forbidden until published.
-- Older published circles may have transferred hosts; do not invent a creator.
INSERT INTO circle_creator_history(circle_id,creator_id,created_at)
 SELECT id,CASE WHEN status='pending_review' THEN host_id ELSE NULL END,NULL FROM circles
 ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS circle_review_jobs (
 circle_id text PRIMARY KEY REFERENCES circles(id) ON DELETE CASCADE,
 state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','manual','done')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
 lease_token text NOT NULL DEFAULT '', lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_reason text NOT NULL DEFAULT 'awaiting_ai', updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS review_jobs_ready ON circle_review_jobs(next_attempt_at) WHERE state IN ('queued','running');
CREATE TABLE IF NOT EXISTS circle_ai_daily_usage (day date PRIMARY KEY, requests integer NOT NULL CHECK(requests>=0));
CREATE TABLE IF NOT EXISTS circle_ai_audit (
 circle_id text NOT NULL, attempt integer NOT NULL,
 model text NOT NULL, policy_version text NOT NULL, input_hash text NOT NULL,
 decision text NOT NULL, reason text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), completed_at timestamptz,
 PRIMARY KEY(circle_id,attempt)
);
ALTER TABLE circle_ai_audit ADD COLUMN IF NOT EXISTS recommendation text NOT NULL DEFAULT '';
ALTER TABLE circle_ai_audit ADD COLUMN IF NOT EXISTS confidence text NOT NULL DEFAULT '';
ALTER TABLE circle_ai_audit ADD COLUMN IF NOT EXISTS provider_reason text NOT NULL DEFAULT '';
ALTER TABLE circle_ai_audit ADD COLUMN IF NOT EXISTS provider_error text NOT NULL DEFAULT '';
ALTER TABLE safety_review_audit ADD COLUMN IF NOT EXISTS creator_id uuid REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE safety_review_audit ADD COLUMN IF NOT EXISTS review_source text NOT NULL DEFAULT 'manual' CHECK(review_source IN ('manual','ai'));
CREATE INDEX IF NOT EXISTS review_history_creator ON safety_review_audit(creator_id,created_at);
CREATE OR REPLACE FUNCTION enqueue_circle_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO circle_creator_history(circle_id,creator_id) VALUES(NEW.id,NEW.host_id) ON CONFLICT DO NOTHING;
 IF NEW.status='pending_review' THEN
  INSERT INTO circle_review_jobs(circle_id) VALUES(NEW.id) ON CONFLICT DO NOTHING;
 END IF;
 RETURN NEW;
END; $$;
DROP TRIGGER IF EXISTS circle_enqueue_review ON circles;
CREATE TRIGGER circle_enqueue_review AFTER INSERT ON circles FOR EACH ROW EXECUTE FUNCTION enqueue_circle_review();
INSERT INTO circle_review_jobs(circle_id) SELECT id FROM circles WHERE status='pending_review' ON CONFLICT DO NOTHING;
CREATE OR REPLACE FUNCTION record_review_creator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.subject_type='circle' THEN
  SELECT creator_id INTO NEW.creator_id FROM circle_creator_history WHERE circle_id=NEW.subject_id;
 END IF;
 RETURN NEW;
END; $$;
DROP TRIGGER IF EXISTS review_record_creator ON safety_review_audit;
CREATE TRIGGER review_record_creator BEFORE INSERT ON safety_review_audit FOR EACH ROW EXECUTE FUNCTION record_review_creator();
CREATE OR REPLACE FUNCTION close_circle_review_job() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status<>'pending_review' THEN
  UPDATE circle_review_jobs SET state='done',last_reason='external_decision',updated_at=now() WHERE circle_id=NEW.id;
 END IF;
 RETURN NEW;
END; $$;
DROP TRIGGER IF EXISTS circle_close_review_job ON circles;
CREATE TRIGGER circle_close_review_job AFTER UPDATE OF status ON circles FOR EACH ROW EXECUTE FUNCTION close_circle_review_job();
