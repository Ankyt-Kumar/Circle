package circles

import (
	"context"
	"errors"
	"math"
	"sort"
	"sync"
	"time"
)

const DemoUser = "00000000-0000-0000-0000-000000000001"

var (
	ErrNotFound = errors.New("circle not found")
	ErrFull     = errors.New("this circle is full")
	ErrClosed   = errors.New("this circle is no longer open")
	ErrDeleted  = errors.New("this circle was deleted; start a new create form")
)

type Person struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type Circle struct {
    MinimumAge int `json:"minimum_age"`
    MaximumAge int `json:"maximum_age"`
    Audience string `json:"audience"`
    Eligible bool `json:"eligible"`
    VenueAddress string `json:"venue_address"`
	Revision       int       `json:"revision"`
	VenueID        string    `json:"venue_id"`
	VenueFictional bool      `json:"venue_fictional"`
	VenueLatitude  *float64  `json:"venue_latitude,omitempty"`
	VenueLongitude *float64  `json:"venue_longitude,omitempty"`
	ID             string    `json:"id"`
	Status         string    `json:"status"`
	HostID         string    `json:"-"`
	IsHost         bool      `json:"is_host"`
	Title          string    `json:"title"`
	Category       string    `json:"category"`
	Description    string    `json:"description"`
	Neighborhood   string    `json:"neighborhood"`
	Venue          string    `json:"venue"`
	StartsAt       time.Time `json:"starts_at"`
	EndsAt         time.Time `json:"ends_at"`
	Capacity       int       `json:"capacity"`
	Attendees      []Person  `json:"attendees"`
	Joined         bool      `json:"joined"`
	DistanceM      float64   `json:"distance_m"`
	Latitude       float64   `json:"-"`
	Longitude      float64   `json:"-"`
}

type Query struct {
	Latitude, Longitude, RadiusKM float64
	Category                      string
	PageSize                      int
	AfterDistance                 float64
	AfterID                       string
	Cutoff                        time.Time
}

func (q Query) Valid() bool {
	return finite(q.Latitude) && finite(q.Longitude) && finite(q.RadiusKM) &&
		q.Latitude >= -90 && q.Latitude <= 90 && q.Longitude >= -180 && q.Longitude <= 180 &&
		q.RadiusKM >= 1 && q.RadiusKM <= 10 &&
		(q.Category == "" || ValidCategory(q.Category))
}
func finite(v float64) bool { return !math.IsNaN(v) && !math.IsInf(v, 0) }

type Store interface {
	Create(context.Context, CreateInput, string) (Circle, error)
	Search(context.Context, Query, string) ([]Circle, error)
	Get(context.Context, string, string) (Circle, error)
	Mine(context.Context, string) ([]Circle, error)
	SetJoined(context.Context, string, string, bool) error
}

type Memory struct {
	mu      sync.Mutex
	data    map[string]Circle
	retired map[string]bool
	blocks  map[string]map[string]Person
}

func NewMemory(now time.Time) *Memory {
	m := &Memory{data: make(map[string]Circle), retired: make(map[string]bool), blocks: make(map[string]map[string]Person)}
	seeds := []struct {
		title, category, description, area, venue string
		lat, lon                                  float64
		count                                     int
	}{
		{"Coffee & new connections", "coffee", "A relaxed hour for people new to the neighborhood. Grab your own coffee and meet a small group. All venues and attendees in this build are fictional demo data.", "Koramangala", "Sample public café · Koramangala", 12.9352, 77.6245, 4},
		{"An easy morning walk", "outdoors", "An easy-paced walk and conversation. Bring water and comfortable shoes. This is a sample event, not a real gathering.", "Koramangala", "Sample public park entrance", 12.9320, 77.6180, 3},
		{"Board games, zero experience needed", "games", "Learn a quick game together. Beginners welcome; the sample host explains the rules. This is a fictional demo event.", "Indiranagar", "Sample public board-game café", 12.9719, 77.6412, 5},
		{"A gentle weekend run", "fitness", "A short, conversational-pace run with a small group. This is fictional demo data, not a real event.", "BTM Layout", "Sample public running track", 12.9166, 77.6101, 6},
	}
	for i, s := range seeds {
		id := []string{"coffee-01", "walk-01", "games-01", "run-01"}[i]
		start := now.UTC().Add(time.Duration(24+i*24) * time.Hour).Truncate(time.Hour)
		people := make([]Person, s.count)
		for j := range people {
			people[j] = Person{ID: id + string(rune('a'+j)), Name: []string{"Riya", "Arjun", "Meera", "Kabir", "Sana", "Dev"}[j]}
		}
		m.data[id] = Circle{ID: id, Status: "published", HostID: people[0].ID, Title: s.title, Category: s.category, Description: s.description, Neighborhood: s.area, Venue: s.venue, StartsAt: start, EndsAt: start.Add(90 * time.Minute), Capacity: 6, Attendees: people, Latitude: s.lat, Longitude: s.lon}
	}
	return m
}

func copyFor(c Circle, user string) Circle {
	c.Attendees = append([]Person{}, c.Attendees...)
	c.IsHost = c.HostID == user
	c.Joined = false
	for _, p := range c.Attendees {
		if p.ID == user {
			c.Joined = true
		}
	}
	return c
}

func (m *Memory) Search(_ context.Context, q Query, user string) ([]Circle, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Circle{}
	for _, c := range m.data {
		if c.Status != "published" || m.blocked(c, user) || len(c.Attendees) == 0 || !c.StartsAt.After(time.Now()) || c.StartsAt.After(time.Now().Add(7*24*time.Hour)) || (q.Category != "" && c.Category != q.Category) {
			continue
		}
		c = copyFor(c, user)
		c.DistanceM = distance(q.Latitude, q.Longitude, c.Latitude, c.Longitude)
		if c.DistanceM <= q.RadiusKM*1000 {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].DistanceM == out[j].DistanceM {
			return out[i].ID < out[j].ID
		}
		return out[i].DistanceM < out[j].DistanceM
	})
	if q.PageSize > 0 {
		page := []Circle{}
		for _, c := range out {
			if !q.Cutoff.IsZero() && c.StartsAt.After(q.Cutoff.Add(7*24*time.Hour)) {
				continue
			}
			if q.AfterID != "" && (c.DistanceM < q.AfterDistance || c.DistanceM == q.AfterDistance && c.ID <= q.AfterID) {
				continue
			}
			page = append(page, c)
			if len(page) == q.PageSize+1 {
				break
			}
		}
		return page, nil
	}
	return out, nil
}
func (m *Memory) Get(_ context.Context, id, user string) (Circle, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.data[id]
	if !ok || !m.visible(c, user) {
		return Circle{}, ErrNotFound
	}
	return copyFor(c, user), nil
}

func (m *Memory) Mine(_ context.Context, user string) ([]Circle, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Circle{}
	for _, c := range m.data {
		c = copyFor(c, user)
		if c.Joined && m.visible(c, user) && c.EndsAt.After(time.Now()) {
			out = append(out, c)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartsAt.Before(out[j].StartsAt) })
	return out, nil
}

func (m *Memory) SetJoined(_ context.Context, id, user string, join bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.data[id]
	if !ok {
		if !join {
			return nil
		} // Retrying a leave after deletion is successful.
		return ErrNotFound
	}
	index := -1
	for i, p := range c.Attendees {
		if p.ID == user {
			index = i
		}
	}
	if !join {
		m.leave(id, user)
		return nil
	}
	if m.blocked(c, user) {
		return ErrBlocked
	}

	if index >= 0 {
		return nil
	}
	if c.Status != "published" || !c.StartsAt.After(time.Now()) {
		return ErrClosed
	}
	if len(c.Attendees) >= c.Capacity {
		return ErrFull
	}
	c.Attendees = append(c.Attendees, Person{ID: user, Name: "You (demo)"})
	m.data[id] = c
	return nil
}

// Caller holds mu. Retain only the ID so a delayed create retry cannot revive it.
func (m *Memory) retire(id string) {
	delete(m.data, id)
	if m.retired == nil {
		m.retired = make(map[string]bool)
	}
	m.retired[id] = true
}

func distance(lat1, lon1, lat2, lon2 float64) float64 {
	r := math.Pi / 180
	dlat := (lat2 - lat1) * r
	dlon := (lon2 - lon1) * r
	a := math.Sin(dlat/2)*math.Sin(dlat/2) + math.Cos(lat1*r)*math.Cos(lat2*r)*math.Sin(dlon/2)*math.Sin(dlon/2)
	return 6371000 * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(math.Max(0, 1-a)))
}
