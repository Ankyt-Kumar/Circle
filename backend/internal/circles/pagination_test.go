package circles

import (
	"context"
	"fmt"
	"testing"
	"time"
)

func TestPaginationPast100AndDeletedBoundary(t *testing.T) {
	m := NewMemory(time.Now())
	m.data = map[string]Circle{}
	for i := 0; i < 137; i++ {
		id := fmt.Sprintf("page-%03d", i)
		m.data[id] = Circle{ID: id, Status: "published", Latitude: 12.97, Longitude: 77.64, StartsAt: time.Now().Add(time.Hour), EndsAt: time.Now().Add(2 * time.Hour), Attendees: []Person{{ID: "host"}}, HostID: "host", Category: "coffee"}
	}
	q := Query{Latitude: 12.97, Longitude: 77.64, RadiusKM: 2, Category: "coffee"}
	cursor := ""
	seen := map[string]bool{}
	for {
		pq, e := WithPage(q, "reader", cursor, 20)
		if e != nil {
			t.Fatal(e)
		}
		rows, e := m.Search(context.Background(), pq, "reader")
		if e != nil {
			t.Fatal(e)
		}
		page := MakePage(pq, "reader", rows)
		for _, c := range page.Circles {
			if seen[c.ID] {
				t.Fatal("duplicate")
			}
			seen[c.ID] = true
		}
		if page.NextCursor == "" {
			break
		}
		delete(m.data, page.Circles[len(page.Circles)-1].ID)
		cursor = page.NextCursor
	}
	if len(seen) != 137 {
		t.Fatal("truncated feed", len(seen))
	}
}
func TestCursorBoundToFiltersAndAccount(t *testing.T) {
	q := Query{Latitude: 12, Longitude: 77, RadiusKM: 3}
	p, _ := WithPage(q, "a", "", 1)
	cursor := MakePage(p, "a", []Circle{{ID: "a", DistanceM: 20}, {ID: "b", DistanceM: 30}}).NextCursor
	if _, e := WithPage(q, "a", cursor, 20); e != nil {
		t.Fatal(e)
	}
	if _, e := WithPage(q, "b", cursor, 20); e == nil {
		t.Fatal("account mismatch accepted")
	}
	q.RadiusKM = 4
	if _, e := WithPage(q, "a", cursor, 20); e == nil {
		t.Fatal("filter mismatch accepted")
	}
	for _, size := range []int{-1, 51} {
		if _, e := WithPage(q, "a", "", size); e == nil {
			t.Fatal("unbounded page")
		}
	}
	if _, e := WithPage(q, "a", "invalid", 20); e == nil {
		t.Fatal("bad cursor")
	}
}
func TestBlockHidesHostedAndJoinedCirclesInEveryPage(t *testing.T) {
	m := NewMemory(time.Now())
	ctx := context.Background()
	hosted, e := m.Create(ctx, lifecycleDraft("blocked-host-created"), "b")
	if e != nil {
		t.Fatal(e)
	}
	joined, e := m.Create(ctx, lifecycleDraft("other-host-created"), "c")
	if e != nil {
		t.Fatal(e)
	}
	if e = m.SetJoined(ctx, joined.ID, "b", true); e != nil {
		t.Fatal(e)
	}
	if e = m.Block(ctx, hosted.ID, "b", "a"); e != nil {
		t.Fatal(e)
	}
	q := Query{Latitude: 12.9719, Longitude: 77.6412, RadiusKM: 10}
	cursor := ""
	for {
		pq, _ := WithPage(q, "a", cursor, 1)
		rows, e := m.Search(ctx, pq, "a")
		if e != nil {
			t.Fatal(e)
		}
		page := MakePage(pq, "a", rows)
		for _, c := range page.Circles {
			if c.ID == hosted.ID || c.ID == joined.ID {
				t.Fatal("blocked user circle visible")
			}
		}
		cursor = page.NextCursor
		if cursor == "" {
			break
		}
	}
	if _, e = m.Get(ctx, joined.ID, "a"); e != ErrNotFound {
		t.Fatal("direct access leaked", e)
	}
	if e = m.Unblock(ctx, "b", "a"); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Get(ctx, joined.ID, "a"); e != nil {
		t.Fatal("unblock did not restore visibility", e)
	}
}
