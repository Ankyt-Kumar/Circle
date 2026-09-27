-- Apply after 005 with the old API stopped. Repeatable.
CREATE SEQUENCE IF NOT EXISTS circle_members_join_order_seq;
ALTER TABLE circle_members ADD COLUMN IF NOT EXISTS join_order bigint;
ALTER TABLE circle_members ALTER COLUMN join_order SET DEFAULT nextval('circle_members_join_order_seq');
ALTER SEQUENCE circle_members_join_order_seq OWNED BY circle_members.join_order;
WITH pending AS (
 SELECT circle_id,user_id,row_number() OVER(ORDER BY joined_at,circle_id,user_id) AS position FROM circle_members WHERE join_order IS NULL
), base AS (SELECT COALESCE(max(join_order),0) AS position FROM circle_members)
UPDATE circle_members m SET join_order=base.position+pending.position FROM pending,base
WHERE m.circle_id=pending.circle_id AND m.user_id=pending.user_id;
SELECT setval('circle_members_join_order_seq',GREATEST(COALESCE((SELECT max(join_order) FROM circle_members),1),(SELECT last_value FROM circle_members_join_order_seq)),true);
ALTER TABLE circle_members ALTER COLUMN join_order SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS circle_members_join_order_unique ON circle_members(join_order);
CREATE INDEX IF NOT EXISTS circle_members_succession ON circle_members(circle_id,join_order);
-- Account deletion must transfer hosting before checking this FK.
ALTER TABLE circles DROP CONSTRAINT IF EXISTS circles_host_id_fkey;
ALTER TABLE circles ADD CONSTRAINT circles_host_id_fkey FOREIGN KEY(host_id) REFERENCES users(id) DEFERRABLE INITIALLY DEFERRED;
CREATE OR REPLACE FUNCTION transfer_circle_host_after_leave() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id uuid; next_owner uuid;
BEGIN
 SELECT host_id INTO owner_id FROM circles WHERE id=OLD.circle_id FOR UPDATE;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT m.user_id INTO next_owner FROM circle_members m JOIN users u ON u.id=m.user_id
 WHERE m.circle_id=OLD.circle_id ORDER BY m.join_order,m.user_id LIMIT 1;
 IF NOT FOUND THEN DELETE FROM circles WHERE id=OLD.circle_id;
 ELSIF OLD.user_id=owner_id OR NOT EXISTS(SELECT 1 FROM circle_members WHERE circle_id=OLD.circle_id AND user_id=owner_id) THEN
 UPDATE circles SET host_id=next_owner WHERE id=OLD.circle_id;
 END IF;
 RETURN NULL;
END; $$;
DROP TRIGGER IF EXISTS circle_prune_after_leave ON circle_members;
CREATE TRIGGER circle_prune_after_leave AFTER DELETE ON circle_members FOR EACH ROW EXECUTE FUNCTION transfer_circle_host_after_leave();
DELETE FROM circles c WHERE NOT EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id);
UPDATE circles c SET host_id=(SELECT user_id FROM circle_members WHERE circle_id=c.id ORDER BY join_order,user_id LIMIT 1)
WHERE NOT EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id AND user_id=c.host_id);
