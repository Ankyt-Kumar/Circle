-- Additive upgrade. Existing accounts finish the new private profile once.
ALTER TABLE user_preferences DROP CONSTRAINT IF EXISTS user_preferences_area_name_check;
ALTER TABLE user_preferences ADD COLUMN IF NOT EXISTS birth_date date;
ALTER TABLE user_preferences ADD COLUMN IF NOT EXISTS gender text;
ALTER TABLE user_preferences ADD COLUMN IF NOT EXISTS latitude double precision;
ALTER TABLE user_preferences ADD COLUMN IF NOT EXISTS longitude double precision;
ALTER TABLE user_preferences ADD COLUMN IF NOT EXISTS location_updated_at timestamptz;
ALTER TABLE user_preferences DROP CONSTRAINT IF EXISTS preferences_private_profile_check;
ALTER TABLE user_preferences ADD CONSTRAINT preferences_private_profile_check CHECK (
 (gender IS NULL OR gender IN ('male','female','other','prefer_not_to_say')) AND
 ((latitude IS NULL AND longitude IS NULL) OR
  (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
ALTER TABLE circles DROP CONSTRAINT IF EXISTS circles_category_check;
ALTER TABLE circles ADD CONSTRAINT circles_category_check CHECK (length(btrim(category)) BETWEEN 1 AND 40);
ALTER TABLE circles ADD COLUMN IF NOT EXISTS minimum_age integer NOT NULL DEFAULT 18;
ALTER TABLE circles ADD COLUMN IF NOT EXISTS maximum_age integer NOT NULL DEFAULT 100;
ALTER TABLE circles ADD COLUMN IF NOT EXISTS audience text NOT NULL DEFAULT 'everyone';
ALTER TABLE circles DROP CONSTRAINT IF EXISTS circles_eligibility_check;
ALTER TABLE circles ADD CONSTRAINT circles_eligibility_check CHECK (
 minimum_age BETWEEN 18 AND 100 AND maximum_age BETWEEN minimum_age AND 100 AND
 audience IN ('everyone','male','female')
);
ALTER TABLE public_venues ADD COLUMN IF NOT EXISTS address text NOT NULL DEFAULT '';
ALTER TABLE public_venues ADD COLUMN IF NOT EXISTS confirmed_public boolean NOT NULL DEFAULT false;
ALTER TABLE public_venues ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE public_venues ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS circles_category_future ON circles(lower(category),starts_at) WHERE status='published';
CREATE INDEX IF NOT EXISTS public_venues_creator_created ON public_venues(created_by,created_at);
-- Keep old records available to their members, but remove fictional discovery inventory.
UPDATE public_venues SET active=false WHERE fictional;
UPDATE circles SET status='archived' WHERE id IN ('coffee-01','walk-01','games-01','run-01');
CREATE OR REPLACE FUNCTION circle_member_eligible(who uuid, youngest integer, oldest integer, audience text, event_day date)
RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM user_preferences p JOIN users u ON u.id=p.user_id
 WHERE p.user_id=who AND u.account_status='active' AND p.adult_confirmed
 AND p.birth_date IS NOT NULL AND p.gender IS NOT NULL
 AND extract(year FROM age(event_day,p.birth_date)) BETWEEN youngest AND oldest
 AND (audience='everyone' OR p.gender=audience));
$$;
INSERT INTO circle_schema_updates(name) VALUES ('013_profiles_location_eligibility') ON CONFLICT DO NOTHING;
