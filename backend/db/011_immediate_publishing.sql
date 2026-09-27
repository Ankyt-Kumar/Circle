-- Stop the API and all old review workers. Apply after 009; 010 is optional.
ALTER TABLE circles ALTER COLUMN status SET DEFAULT 'published';
CREATE OR REPLACE FUNCTION enqueue_circle_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO circle_creator_history(circle_id,creator_id) VALUES(NEW.id,NEW.host_id) ON CONFLICT DO NOTHING;
 RETURN NEW;
END; $$;
UPDATE circles SET status='published' WHERE status='pending_review';
UPDATE circle_review_jobs SET state='done',lease_token='',lease_until=NULL,last_reason='reviews_disabled',updated_at=now() WHERE state<>'done' OR lease_until IS NOT NULL;
ALTER TABLE chat_messages ALTER COLUMN status SET DEFAULT 'allowed';
ALTER TABLE chat_messages ALTER COLUMN reason SET DEFAULT 'posted';
UPDATE chat_messages SET status='allowed',review_required=false,reason='reviews_disabled',lease_token='',lease_until=NULL WHERE status='pending';
CREATE TABLE IF NOT EXISTS circle_schema_updates(name text PRIMARY KEY,applied_at timestamptz NOT NULL DEFAULT now());
INSERT INTO circle_schema_updates(name) VALUES('011_immediate_publishing') ON CONFLICT DO NOTHING;
