package circles

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"
)

type Page struct {
	Circles    []Circle `json:"circles"`
	NextCursor string   `json:"next_cursor"`
}
type pageCursor struct {
	Version  int       `json:"v"`
	Query    string    `json:"q"`
	Distance float64   `json:"d"`
	ID       string    `json:"id"`
	Cutoff   time.Time `json:"at"`
}

func queryKey(q Query, user string) string {
	b := sha256.Sum256([]byte(fmt.Sprintf("%s|%.8f|%.8f|%.4f|%s", user, q.Latitude, q.Longitude, q.RadiusKM, q.Category)))
	return hex.EncodeToString(b[:])
}
func WithPage(q Query, user, cursor string, size int) (Query, error) {
	if size == 0 {
		size = 20
	}
	if size < 1 || size > 50 || len(cursor) > 2048 {
		return q, ErrInvalid
	}
	q.PageSize = size
	q.Cutoff = time.Now().UTC()
	if cursor == "" {
		return q, nil
	}
	b, e := base64.RawURLEncoding.DecodeString(cursor)
	if e != nil {
		return q, ErrInvalid
	}
	var c pageCursor
	if json.Unmarshal(b, &c) != nil || c.Version != 1 || c.Query != queryKey(q, user) || !finite(c.Distance) || c.Distance < 0 || c.ID == "" || len(c.ID) > 200 || c.Cutoff.After(time.Now().Add(time.Minute)) || c.Cutoff.Before(time.Now().Add(-24*time.Hour)) {
		return q, ErrInvalid
	}
	q.AfterDistance = c.Distance
	q.AfterID = c.ID
	q.Cutoff = c.Cutoff
	return q, nil
}
func MakePage(q Query, user string, list []Circle) Page {
	p := Page{Circles: list}
	if p.Circles == nil {
		p.Circles = []Circle{}
	}
	if len(list) > q.PageSize {
		p.Circles = list[:q.PageSize]
		last := p.Circles[len(p.Circles)-1]
		b, _ := json.Marshal(pageCursor{1, queryKey(q, user), last.DistanceM, last.ID, q.Cutoff})
		p.NextCursor = base64.RawURLEncoding.EncodeToString(b)
	}
	return p
}
