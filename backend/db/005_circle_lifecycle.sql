-- Run while the old API is stopped. Repeatable; does not reset users or joins.
-- Retain IDs only: a delayed create retry must never resurrect a deleted circle.
CREATE TABLE IF NOT EXISTS circle_creation_ids (id text PRIMARY KEY);
INSERT INTO circle_creation_ids(id) SELECT id FROM circles ON CONFLICT DO NOTHING;

ALTER TABLE circles ADD COLUMN IF NOT EXISTS host_id uuid REFERENCES users(id) ON DELETE CASCADE;

-- The previous schema did not record creators. For legacy circles assign the
-- earliest REMAINING membership, with a stable user-ID tie breaker, as host.
UPDATE circles c SET host_id = (
  SELECT user_id FROM circle_members m WHERE m.circle_id=c.id
  ORDER BY joined_at,user_id LIMIT 1
) WHERE host_id IS NULL;

-- Requested cleanup: previously abandoned circles are physically removed.
DELETE FROM circles c WHERE NOT EXISTS (SELECT 1 FROM circle_members m WHERE m.circle_id=c.id);
ALTER TABLE circles ALTER COLUMN host_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS circles_host ON circles(host_id);

CREATE OR REPLACE FUNCTION remember_circle_creation_id() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO circle_creation_ids(id) VALUES(NEW.id) ON CONFLICT DO NOTHING;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS circle_remember_id ON circles;
CREATE TRIGGER circle_remember_id AFTER INSERT ON circles
FOR EACH ROW EXECUTE FUNCTION remember_circle_creation_id();

-- Also cover membership deletion by SQL/FK cascade. API operations lock the
-- circle row first, serializing joins and leaves. A parent delete's cascade
-- finds no circle and exits, so this trigger does not recurse.
CREATE OR REPLACE FUNCTION prune_circle_after_leave() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id uuid;
BEGIN
  SELECT host_id INTO owner_id FROM circles WHERE id=OLD.circle_id FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF OLD.user_id=owner_id OR NOT EXISTS (
    SELECT 1 FROM circle_members WHERE circle_id=OLD.circle_id
  ) THEN
    DELETE FROM circles WHERE id=OLD.circle_id;
  END IF;
  RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS circle_prune_after_leave ON circle_members;
CREATE TRIGGER circle_prune_after_leave AFTER DELETE ON circle_members
FOR EACH ROW EXECUTE FUNCTION prune_circle_after_leave();
