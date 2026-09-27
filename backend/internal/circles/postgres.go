package circles

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

type Postgres struct{ db *sql.DB }

func NewPostgres(db *sql.DB) *Postgres { return &Postgres{db: db} }

const fields = `c.minimum_age,c.maximum_age,c.audience,`+eligibleTo+`,COALESCE((SELECT address FROM public_venues WHERE id=c.venue_id),''),c.revision,COALESCE(c.venue_id,''),COALESCE((SELECT fictional FROM public_venues WHERE id=c.venue_id),true), (SELECT latitude FROM public_venues WHERE id=c.venue_id),(SELECT longitude FROM public_venues WHERE id=c.venue_id),c.id,c.status,c.host_id::text,c.host_id=$1::uuid,c.title,c.category,c.description,c.neighborhood,c.venue,c.starts_at,c.ends_at,c.capacity,
 COALESCE((SELECT jsonb_agg(jsonb_build_object('id',u.id,'name',u.first_name) ORDER BY m.join_order,u.id)
 FROM circle_members m JOIN users u ON u.id=m.user_id WHERE m.circle_id=c.id),'[]'::jsonb),
 EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id AND user_id=$1::uuid)`

const savedDistance = `COALESCE((SELECT ST_Distance(c.location,ST_SetSRID(ST_MakePoint(p.longitude,p.latitude),4326)::geography) FROM user_preferences p WHERE p.user_id=$1::uuid AND p.latitude IS NOT NULL AND p.longitude IS NOT NULL),0)::float8`

type scanner interface{ Scan(...any) error }

func scanCircle(row scanner) (Circle, error) {
	var c Circle
	var people []byte
	err := row.Scan(&c.MinimumAge, &c.MaximumAge, &c.Audience, &c.Eligible, &c.VenueAddress, &c.Revision, &c.VenueID, &c.VenueFictional, &c.VenueLatitude, &c.VenueLongitude, &c.ID, &c.Status, &c.HostID, &c.IsHost, &c.Title, &c.Category, &c.Description, &c.Neighborhood, &c.Venue, &c.StartsAt, &c.EndsAt, &c.Capacity, &people, &c.Joined, &c.DistanceM)
	if errors.Is(err, sql.ErrNoRows) {
		return c, ErrNotFound
	}
	if err != nil {
		return c, err
	}
	err = json.Unmarshal(people, &c.Attendees)
	return c, err
}
func scanList(rows *sql.Rows, err error) ([]Circle, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Circle{}
	for rows.Next() {
		c, e := scanCircle(rows)
		if e != nil {
			return nil, e
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (p *Postgres) Search(ctx context.Context, q Query, user string) ([]Circle, error) {
	limit := 100
	if q.PageSize > 0 {
		limit = q.PageSize + 1
	}
	if q.Cutoff.IsZero() {
		q.Cutoff = time.Now().UTC()
	}
	return scanList(p.db.QueryContext(ctx, `SELECT `+fields+`,
 ST_Distance(c.location,ST_SetSRID(ST_MakePoint($3,$2),4326)::geography) AS distance_m
 FROM circles c WHERE c.status='published' AND c.starts_at>now() AND c.starts_at<=$8::timestamptz+interval '7 days'
 AND EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id) AND `+visibleTo+`
 AND `+eligibleTo+` AND ($5='' OR lower(c.category)=lower($5))
 AND ST_DWithin(c.location,ST_SetSRID(ST_MakePoint($3,$2),4326)::geography,$4::double precision*1000)
 AND ($7='' OR ST_Distance(c.location,ST_SetSRID(ST_MakePoint($3,$2),4326)::geography)>$6 OR (ST_Distance(c.location,ST_SetSRID(ST_MakePoint($3,$2),4326)::geography)=$6 AND c.id>$7)) ORDER BY distance_m,c.id LIMIT $9`, user, q.Latitude, q.Longitude, q.RadiusKM, q.Category, q.AfterDistance, q.AfterID, q.Cutoff, limit))
}
func (p *Postgres) Get(ctx context.Context, id, user string) (Circle, error) {
	return scanCircle(p.db.QueryRowContext(ctx, `SELECT `+fields+`,`+savedDistance+` FROM circles c WHERE c.id=$2 AND (c.status='published' OR c.host_id=$1::uuid OR (c.status IN ('pending_review','cancelled','archived') AND EXISTS(SELECT 1 FROM circle_members m WHERE m.circle_id=c.id AND m.user_id=$1::uuid))) AND `+visibleTo+` AND EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id)`, user, id))
}
func (p *Postgres) Mine(ctx context.Context, user string) ([]Circle, error) {
	return scanList(p.db.QueryContext(ctx, `SELECT `+fields+`,`+savedDistance+` FROM circles c
 WHERE (c.status='published' OR c.host_id=$1::uuid OR (c.status IN ('pending_review','cancelled','archived') AND EXISTS(SELECT 1 FROM circle_members m WHERE m.circle_id=c.id AND m.user_id=$1::uuid))) AND `+visibleTo+` AND c.ends_at>now()-interval '30 days' AND EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id AND user_id=$1::uuid)
 ORDER BY c.starts_at,c.id`, user))
}

func (p *Postgres) SetJoined(ctx context.Context, id, user string, join bool) error {
	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = lockMemberships(ctx, tx); err != nil {
		return err
	}
	var capacity int
	var starts time.Time
	var status string
	err = tx.QueryRowContext(ctx, `SELECT capacity,starts_at,status FROM circles WHERE id=$1 FOR UPDATE`, id).Scan(&capacity, &starts, &status)
	if errors.Is(err, sql.ErrNoRows) {
		if !join {
			return nil
		}
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if !join {

		_, err = tx.ExecContext(ctx, `DELETE FROM circle_members WHERE circle_id=$1 AND user_id=$2::uuid`, id, user)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM circles WHERE id=$1 AND NOT EXISTS(SELECT 1 FROM circle_members WHERE circle_id=$1)`, id)
		if err != nil {
			return err
		}
		return tx.Commit()
	}
	var allowed bool
	if err = tx.QueryRowContext(ctx, `SELECT `+visibleTo+` FROM circles c WHERE c.id=$2`, user, id).Scan(&allowed); err != nil {
		return err
	}
	if !allowed {
		return ErrBlocked
	}

	var exists bool
	err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM circle_members WHERE circle_id=$1 AND user_id=$2::uuid)`, id, user).Scan(&exists)
	if err != nil {
		return err
	}
	if exists {
		return tx.Commit()
	}
	var eligible bool
    if err = tx.QueryRowContext(ctx, `SELECT `+eligibleTo+` FROM circles c WHERE c.id=$2`, user,id).Scan(&eligible); err != nil { return err }
    if !eligible { return ErrEligibility }
    if status != "published" || !starts.After(time.Now()) {
		return ErrClosed
	}
	var count int
	err = tx.QueryRowContext(ctx, `SELECT count(*) FROM circle_members WHERE circle_id=$1`, id).Scan(&count)
	if err != nil {
		return err
	}
	if count >= capacity {
		return ErrFull
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO circle_members(circle_id,user_id) VALUES($1,$2::uuid)`, id, user)
	if err != nil {
		return err
	}
	return tx.Commit()
}
