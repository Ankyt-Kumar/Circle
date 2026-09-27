-- Additive and repeatable. Existing accounts complete the new onboarding once.
CREATE TABLE IF NOT EXISTS user_preferences (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  interests text[] NOT NULL CHECK (
    cardinality(interests) BETWEEN 1 AND 4
    AND array_position(interests, NULL) IS NULL
    AND interests <@ ARRAY['coffee','outdoors','games','fitness']::text[]
  ),
  area_name text NOT NULL CHECK (area_name IN ('Koramangala','Indiranagar','BTM Layout')),
  radius_km integer NOT NULL CHECK (radius_km BETWEEN 1 AND 10),
  adult_confirmed boolean NOT NULL CHECK (adult_confirmed),
  terms_version text NOT NULL CHECK (length(terms_version) BETWEEN 1 AND 80),
  terms_accepted_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
