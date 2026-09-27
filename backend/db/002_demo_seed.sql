-- All people, venues, and events are fictional. Do not use as launch inventory.
INSERT INTO users(id,first_name) VALUES ('00000000-0000-0000-0000-000000000001','You (demo)');

INSERT INTO circles(id,title,category,description,neighborhood,venue,location,starts_at,ends_at,capacity)
SELECT id,title,category,
 'A small activity to help neighbors connect. All attendees and venues in this build are fictional demo data; this is not a real gathering.',
 area,venue,ST_SetSRID(ST_MakePoint(lon,lat),4326)::geography,
 date_trunc('hour',now())+days*interval '1 day',
 date_trunc('hour',now())+days*interval '1 day'+interval '90 minutes',6
FROM (VALUES
 ('coffee-01','Coffee & new connections','coffee','Koramangala','Sample public café · Koramangala',77.6245,12.9352,1),
 ('walk-01','An easy morning walk','outdoors','Koramangala','Sample public park entrance',77.6180,12.9320,2),
 ('games-01','Board games, zero experience needed','games','Indiranagar','Sample public board-game café',77.6412,12.9719,3),
 ('run-01','A gentle weekend run','fitness','BTM Layout','Sample public running track',77.6101,12.9166,4)
) AS seed(id,title,category,area,venue,lon,lat,days);

INSERT INTO users(id,first_name)
SELECT md5(c.id||n::text)::uuid,(ARRAY['Riya','Arjun','Meera','Kabir','Sana','Dev'])[n]
FROM circles c CROSS JOIN generate_series(1,6) n;

INSERT INTO circle_members(circle_id,user_id)
SELECT c.id,md5(c.id||n::text)::uuid FROM circles c CROSS JOIN generate_series(1,6) n
WHERE n <= CASE c.id WHEN 'coffee-01' THEN 4 WHEN 'walk-01' THEN 3 WHEN 'games-01' THEN 5 ELSE 6 END;
