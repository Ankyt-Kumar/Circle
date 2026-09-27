-- Refresh only the known fictional demo circles; preserve memberships.
UPDATE circles c SET
  starts_at = date_trunc('hour',now()) + seed.days * interval '1 day',
  ends_at = date_trunc('hour',now()) + seed.days * interval '1 day' + interval '90 minutes'
FROM (VALUES ('coffee-01',1),('walk-01',2),('games-01',3),('run-01',4)) AS seed(id,days)
WHERE c.id=seed.id;
