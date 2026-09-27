package community

import (
	"circle.local/backend/internal/circles"
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

type Venue struct {
    Address string `json:"address"`
	SourceURL    string  `json:"source_url"`
	MeetingNote  string  `json:"meeting_note"`
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	Neighborhood string  `json:"neighborhood"`
	Latitude     float64 `json:"latitude"`
	Longitude    float64 `json:"longitude"`
	Fictional    bool    `json:"fictional"`
}

func (s *Service) Venues(ctx context.Context, q string) ([]Venue, error) {
	if len(q) > 100 {
		return nil, ErrInvalid
	}
	rows, e := s.DB.QueryContext(ctx, `SELECT id,name,neighborhood,latitude,longitude,fictional,source_url,meeting_note,address FROM public_venues WHERE active AND NOT fictional AND (verified_public OR confirmed_public) AND ($1='' OR name ILIKE '%'||$1||'%' OR neighborhood ILIKE '%'||$1||'%') ORDER BY neighborhood,name,id LIMIT 100`, strings.TrimSpace(q))
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Venue{}
	for rows.Next() {
		var v Venue
		if e = rows.Scan(&v.ID, &v.Name, &v.Neighborhood, &v.Latitude, &v.Longitude, &v.Fictional, &v.SourceURL, &v.MeetingNote, &v.Address); e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

type EventInput struct {
    MinimumAge int `json:"minimum_age"`
    MaximumAge int `json:"maximum_age"`
    Audience string `json:"audience"`
	Revision    int       `json:"revision"`
	Title       string    `json:"title"`
	Description string    `json:"description"`
	Category    string    `json:"category"`
	VenueID     string    `json:"venue_id"`
	StartsAt    time.Time `json:"starts_at"`
	EndsAt      time.Time `json:"ends_at"`
	Capacity    int       `json:"capacity"`
}

func validEvent(d EventInput, now time.Time) bool {
	return circles.ValidEligibility(d.MinimumAge,d.MaximumAge,d.Audience) && bounded(d.Title, 3, 100) && bounded(d.Description, 10, 1000) && d.Category != "" && (circles.Query{RadiusKM: 1, Category: d.Category}).Valid() && d.Capacity >= 4 && d.Capacity <= 8 && d.StartsAt.After(now) && !d.StartsAt.After(now.Add(7*24*time.Hour)) && d.EndsAt.After(d.StartsAt) && d.EndsAt.Sub(d.StartsAt) <= 6*time.Hour
}
func (s *Service) ChangeEvent(ctx context.Context, id, user string, d EventInput, cancel bool) error {
	if d.MinimumAge==0 { d.MinimumAge=18 }; if d.MaximumAge==0 { d.MaximumAge=100 }; if d.Audience=="" { d.Audience="everyone" }
    if d.Revision < 1 || !cancel && !validEvent(d, time.Now()) {
		return ErrInvalid
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var host, status string
	var revision, count int
	var start time.Time
	e = tx.QueryRowContext(ctx, `SELECT host_id::text,status,revision,starts_at,(SELECT count(*) FROM circle_members WHERE circle_id=$1) FROM circles WHERE id=$1 FOR UPDATE`, id).Scan(&host, &status, &revision, &start, &count)
	if errors.Is(e, sql.ErrNoRows) {
		return ErrForbidden
	}
	if e != nil {
		return e
	}
	if host != user {
		return ErrForbidden
	}
	if revision != d.Revision || status == "cancelled" || status == "rejected" || status == "archived" || !start.After(time.Now()) {
		return ErrConflict
	}
	kind := "changed"
	body := "The host updated this circle. Open Circle to check the latest plan."
	if cancel {
		kind = "cancelled"
		body = "The host cancelled this circle."
		_, e = tx.ExecContext(ctx, `UPDATE circles SET status='cancelled',revision=revision+1 WHERE id=$1`, id)
	} else {
		var excluded int
        if e = tx.QueryRowContext(ctx, `SELECT count(*) FROM circle_members m WHERE m.circle_id=$1 AND NOT circle_member_eligible(m.user_id,$2,$3,$4,($5::timestamptz AT TIME ZONE 'UTC')::date)`,id,d.MinimumAge,d.MaximumAge,d.Audience,d.StartsAt).Scan(&excluded); e != nil { return e }
        if excluded > 0 { return circles.ErrEligibility }
        if d.Capacity < count {
			return ErrInvalid
		}
		var v Venue
		e = tx.QueryRowContext(ctx, `SELECT id,name,neighborhood,latitude,longitude,fictional FROM public_venues WHERE id=$1 AND active AND NOT fictional AND (verified_public OR confirmed_public) FOR SHARE`, d.VenueID).Scan(&v.ID, &v.Name, &v.Neighborhood, &v.Latitude, &v.Longitude, &v.Fictional)
		if errors.Is(e, sql.ErrNoRows) {
			return ErrInvalid
		}
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, `UPDATE circles SET title=$2,description=$3,category=$4,venue_id=$5,venue=$6,neighborhood=$7,location=ST_SetSRID(ST_MakePoint($8,$9),4326)::geography,starts_at=$10,ends_at=$11,capacity=$12,minimum_age=$13,maximum_age=$14,audience=$15,status='published',revision=revision+1 WHERE id=$1`, id, strings.TrimSpace(d.Title), strings.TrimSpace(d.Description), d.Category, v.ID, v.Name, v.Neighborhood, v.Longitude, v.Latitude, d.StartsAt, d.EndsAt, d.Capacity, d.MinimumAge, d.MaximumAge, d.Audience)
	}
	if e != nil {
		return e
	}
	// Bind the Go revision as an integer before formatting it in the event key.
	// A direct $4::text makes pgx expect a string and rejects the integer argument.
	if _, e = tx.ExecContext(ctx, `INSERT INTO notification_outbox(user_id,circle_id,kind,event_key,title,body) SELECT m.user_id,m.circle_id,$2,m.circle_id||':'||$2||':'||$4::integer::text||':'||m.user_id::text,'Circle update',$3 FROM circle_members m LEFT JOIN notification_preferences p ON p.user_id=m.user_id WHERE m.circle_id=$1 AND COALESCE(p.changes,true) ON CONFLICT DO NOTHING`, id, kind, body, revision+1); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Service) Feedback(ctx context.Context, id, user string, attended bool, rating string) error {
	if rating != "good" && rating != "okay" && rating != "poor" {
		return ErrInvalid
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	end, e := member(ctx, tx, id, user, false)
	if e != nil {
		return e
	}
	if end.After(time.Now()) {
		return ErrConflict
	}
	var status string
	if e = tx.QueryRowContext(ctx, `SELECT status FROM circles WHERE id=$1`, id).Scan(&status); e != nil {
		return e
	}
	if status != "published" && status != "archived" {
		return ErrConflict
	}
	_, e = tx.ExecContext(ctx, `UPDATE circle_engagement SET attended=$3,feedback=$4 WHERE user_id=$1::uuid AND circle_id=$2`, user, id, attended, rating)
	if e != nil {
		return e
	}
	return tx.Commit()
}

type Stats struct {
	Joined   int `json:"joined"`
	Hosted   int `json:"hosted"`
	Attended int `json:"attended"`
}

func (s *Service) Stats(ctx context.Context, user string) (Stats, error) {
	var x Stats
	e := s.DB.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM circle_engagement WHERE user_id=$1::uuid),(SELECT count(*) FROM circle_creator_history WHERE creator_id=$1::uuid),(SELECT count(*) FROM circle_engagement WHERE user_id=$1::uuid AND attended=true)`, user).Scan(&x.Joined, &x.Hosted, &x.Attended)
	return x, e
}
