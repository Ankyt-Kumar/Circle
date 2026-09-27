package circles

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// Demo venues are curated sample public places, never user/device coordinates.
type Venue struct {
	Name, Area, Address string
	Latitude, Longitude float64
}

var Venues = map[string]Venue{
	"koramangala-cafe": {Name:"Sample public café", Area:"Koramangala", Latitude:12.9352, Longitude:77.6245},
	"indiranagar-cafe": {Name:"Sample public board-game café", Area:"Indiranagar", Latitude:12.9719, Longitude:77.6412},
	"btm-park": {Name:"Sample public park", Area:"BTM Layout", Latitude:12.9166, Longitude:77.6101},
}
var ErrInvalid = fmt.Errorf("check title, description, public venue, category, capacity 4–8, and a future time within 7 days")
var ErrRequestConflict = fmt.Errorf("request ID already used for a different circle")

type CreateInput struct {
    MinimumAge int `json:"minimum_age"`
    MaximumAge int `json:"maximum_age"`
    Audience string `json:"audience"`
	RequestID   string    `json:"request_id"`
	Title       string    `json:"title"`
	Description string    `json:"description"`
	Category    string    `json:"category"`
	VenueID     string    `json:"venue_id"`
	StartsAt    time.Time `json:"starts_at"`
	EndsAt      time.Time `json:"ends_at"`
	Capacity    int       `json:"capacity"`
}

func (d CreateInput) circle(user string) (Circle, error) {
	v, ok := Venues[d.VenueID]
	if !ok {
		return Circle{}, ErrInvalid
	}
	return d.circleAt(user, v)
}
func (d CreateInput) circleAt(user string, v Venue) (Circle, error) {
	d.StartsAt = d.StartsAt.Truncate(time.Microsecond)
	d.EndsAt = d.EndsAt.Truncate(time.Microsecond)
	now := time.Now()
    normalizeEligibility(&d.MinimumAge,&d.MaximumAge,&d.Audience)
    if !ValidEligibility(d.MinimumAge,d.MaximumAge,d.Audience) { return Circle{},ErrInvalid }
    if len(d.RequestID) < 16 || len(d.RequestID) > 80 || utf8.RuneCountInString(strings.TrimSpace(d.Title)) < 3 || utf8.RuneCountInString(d.Title) > 100 || utf8.RuneCountInString(strings.TrimSpace(d.Description)) < 10 || utf8.RuneCountInString(d.Description) > 1000 || d.Capacity < 4 || d.Capacity > 8 || d.Category == "" || !(Query{RadiusKM: 1, Category: d.Category}).Valid() || !d.StartsAt.After(now) || d.StartsAt.After(now.Add(7*24*time.Hour)) || !d.EndsAt.After(d.StartsAt) || d.EndsAt.Sub(d.StartsAt) > 6*time.Hour {
		return Circle{}, ErrInvalid
	}
	id := fmt.Sprintf("created-%x", sha256.Sum256([]byte(user+":"+d.RequestID)))
	return Circle{MinimumAge:d.MinimumAge, MaximumAge:d.MaximumAge, Audience:d.Audience, Eligible:true, VenueAddress:v.Address, VenueID:d.VenueID, ID: id, Status: "published", HostID: user, IsHost: true, Title: strings.TrimSpace(d.Title), Description: strings.TrimSpace(d.Description), Category: d.Category, Neighborhood: v.Area, Venue: v.Name, Latitude: v.Latitude, Longitude: v.Longitude, StartsAt: d.StartsAt, EndsAt: d.EndsAt, Capacity: d.Capacity, Attendees: []Person{{ID: user, Name: "You (demo)"}}, Joined: true}, nil
}
func sameDraft(a, b Circle) bool {
	return a.VenueID == b.VenueID && a.MinimumAge == b.MinimumAge && a.MaximumAge == b.MaximumAge && a.Audience == b.Audience && a.Title == b.Title && a.Description == b.Description && a.Category == b.Category && a.Venue == b.Venue && a.Neighborhood == b.Neighborhood && a.Capacity == b.Capacity && a.StartsAt.Equal(b.StartsAt) && a.EndsAt.Equal(b.EndsAt)
}
func (m *Memory) Create(_ context.Context, d CreateInput, user string) (Circle, error) {
	c, err := d.circle(user)
	if err != nil {
		return Circle{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.retired[c.ID] {
		return Circle{}, ErrDeleted
	}
	if old, ok := m.data[c.ID]; ok {
		if !sameDraft(old, c) {
			return Circle{}, ErrRequestConflict
		}
		if m.blocked(old, user) {
			return Circle{}, ErrBlocked
		}
		if !m.visible(old, user) {
			return Circle{}, ErrNotFound
		}
		return copyFor(old, user), nil
	}
	m.data[c.ID] = c
	return copyFor(c, user), nil
}
func (p *Postgres) Create(ctx context.Context, d CreateInput, user string) (Circle, error) {
	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return Circle{}, err
	}
	defer tx.Rollback()
	if err = lockMemberships(ctx, tx); err != nil {
		return Circle{}, err
	}
	var v Venue
	err = tx.QueryRowContext(ctx, `SELECT name,neighborhood,latitude,longitude,address FROM public_venues WHERE id=$1 AND active AND NOT fictional AND (verified_public OR confirmed_public) FOR SHARE`, d.VenueID).Scan(&v.Name, &v.Area, &v.Latitude, &v.Longitude, &v.Address)
	if errors.Is(err, sql.ErrNoRows) {
		return Circle{}, ErrInvalid
	}
	if err != nil {
		return Circle{}, err
	}
	c, err := d.circleAt(user, v)
	if err != nil {
		return Circle{}, err
	}
	var eligible bool
    if err = tx.QueryRowContext(ctx, `SELECT circle_member_eligible($1::uuid,$2,$3,$4,($5::timestamptz AT TIME ZONE 'UTC')::date)`, user,c.MinimumAge,c.MaximumAge,c.Audience,c.StartsAt).Scan(&eligible); err != nil { return Circle{},err }
    if !eligible { return Circle{},ErrEligibility }
    // The durable ID ledger serializes duplicate creates and survives circle deletion.
	result, err := tx.ExecContext(ctx, `INSERT INTO circle_creation_ids(id) VALUES($1) ON CONFLICT(id) DO NOTHING`, c.ID)
	if err != nil {
		return Circle{}, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return Circle{}, err
	}
	if count > 0 {
		_, err = tx.ExecContext(ctx, `INSERT INTO circles(id,host_id,title,category,description,neighborhood,venue,location,starts_at,ends_at,capacity,status,venue_id,minimum_age,maximum_age,audience)
 VALUES($1,$12::uuid,$2,$3,$4,$5,$6,ST_SetSRID(ST_MakePoint($7,$8),4326)::geography,$9,$10,$11,'published',$13,$14,$15,$16)`, c.ID, c.Title, c.Category, c.Description, c.Neighborhood, c.Venue, c.Longitude, c.Latitude, c.StartsAt, c.EndsAt, c.Capacity, user, d.VenueID, c.MinimumAge, c.MaximumAge, c.Audience)
		if err != nil {
			return Circle{}, err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO circle_members(circle_id,user_id) VALUES($1,$2::uuid)`, c.ID, user)
		if err != nil {
			return Circle{}, err
		}
	}
	old, err := scanCircle(tx.QueryRowContext(ctx, `SELECT `+fields+`,0::float8 FROM circles c WHERE c.id=$2`, user, c.ID))
	if err == ErrNotFound {
		return Circle{}, ErrDeleted
	}
	if err != nil {
		return Circle{}, err
	}
	var allowed bool
	if err = tx.QueryRowContext(ctx, `SELECT `+visibleTo+` FROM circles c WHERE c.id=$2`, user, c.ID).Scan(&allowed); err != nil {
		return Circle{}, err
	}
	if !allowed {
		return Circle{}, ErrBlocked
	}
	if old.Status != "published" && old.HostID != user {
		return Circle{}, ErrNotFound
	}
	if !sameDraft(old, c) {
		return Circle{}, ErrRequestConflict
	}
	if err = tx.Commit(); err != nil {
		return Circle{}, err
	}
	return old, nil
}
