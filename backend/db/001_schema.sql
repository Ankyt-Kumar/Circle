CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
  id uuid PRIMARY KEY,
  first_name text NOT NULL CHECK (length(first_name) BETWEEN 1 AND 60)
);

CREATE TABLE circles (
  id text PRIMARY KEY,
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 100),
  category text NOT NULL CHECK (category IN ('coffee','outdoors','games','fitness')),
  description text NOT NULL,
  neighborhood text NOT NULL,
  venue text NOT NULL,
  -- Public sample venue coordinates, NEVER device/home coordinates.
  location geography(Point,4326) NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  capacity integer NOT NULL CHECK (capacity BETWEEN 4 AND 8),
  status text NOT NULL DEFAULT 'published' CHECK (status IN ('published','cancelled','archived')),
  CHECK (ends_at > starts_at)
);
CREATE INDEX circles_location_gist ON circles USING gist(location);
CREATE INDEX circles_upcoming ON circles(starts_at) WHERE status='published';

CREATE TABLE circle_members (
  circle_id text NOT NULL REFERENCES circles(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(circle_id,user_id)
);
CREATE INDEX memberships_user ON circle_members(user_id);
