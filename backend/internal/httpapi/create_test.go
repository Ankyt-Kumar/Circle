package httpapi

import (
	"bytes"
	"circle.local/backend/internal/circles"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCreateCircleFlow(t *testing.T) {
	h := NewDemo(circles.NewMemory(time.Now()))
	draft := circles.CreateInput{RequestID: "test-create-request-001", Title: "Coffee together", Description: "A relaxed sample meetup for new neighbors.", Category: "coffee", VenueID: "indiranagar-cafe", StartsAt: time.Now().UTC().Add(24 * time.Hour).Truncate(time.Second), Capacity: 6}
	draft.EndsAt = draft.StartsAt.Add(time.Hour)
	call := func(method, path string, body any) *httptest.ResponseRecorder {
		b, _ := json.Marshal(body)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(method, path, bytes.NewReader(b)))
		return w
	}
	w := call("POST", "/v1/circles", draft)
	if w.Code != 201 {
		t.Fatalf("create: %d %s", w.Code, w.Body)
	}
	var c circles.Circle
	if err := json.Unmarshal(w.Body.Bytes(), &c); err != nil {
		t.Fatal(err)
	}
	if c.Status != "published" || !c.Joined || len(c.Attendees) != 1 || c.Capacity != 6 || c.Neighborhood != "Indiranagar" {
		t.Fatalf("bad created circle: %+v", c)
	}
	if bytes.Contains(w.Body.Bytes(), []byte(`"latitude"`)) {
		t.Fatal("coordinates leaked")
	}
	retry := call("POST", "/v1/circles", draft)
	var repeated circles.Circle
	json.Unmarshal(retry.Body.Bytes(), &repeated)
	if retry.Code != 201 || repeated.ID != c.ID {
		t.Fatal("retry created duplicate")
	}
	search := call("POST", "/v1/circles/search", map[string]any{"latitude": 12.9719, "longitude": 77.6412, "radius_km": 1, "category": "coffee"})
	if !bytes.Contains(search.Body.Bytes(), []byte(c.ID)) {
		t.Fatalf("new circle missing: %s", search.Body)
	}
	mine := call("GET", "/v1/me/circles", nil)
	if !bytes.Contains(mine.Body.Bytes(), []byte(c.ID)) {
		t.Fatal("host not in My circles")
	}
	detail := call("GET", "/v1/circles/"+c.ID, nil)
	if detail.Code != 200 {
		t.Fatal("missing detail")
	}
	draft.Title = "Changed request"
	if call("POST", "/v1/circles", draft).Code != 409 {
		t.Fatal("different payload must conflict")
	}
	for _, change := range []func(*circles.CreateInput){
		func(d *circles.CreateInput) { d.Title = " " }, func(d *circles.CreateInput) { d.Capacity = 9 },
		func(d *circles.CreateInput) { d.VenueID = "home" }, func(d *circles.CreateInput) { d.Category = "invalid" },
		func(d *circles.CreateInput) { d.StartsAt = time.Now().Add(-time.Hour) },
		func(d *circles.CreateInput) { d.StartsAt = time.Now().Add(8 * 24 * time.Hour) },
		func(d *circles.CreateInput) { d.EndsAt = d.StartsAt },
	} {
		invalid := draft
		change(&invalid)
		if call("POST", "/v1/circles", invalid).Code != 400 {
			t.Fatalf("accepted invalid: %+v", invalid)
		}
	}
}
