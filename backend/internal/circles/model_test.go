package circles

import (
	"context"
	"errors"
	"fmt"
	"math"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSearchFiltersAndPrivacy(t *testing.T) {
	store := NewMemory(time.Now())
	ctx := context.Background()
	list, err := store.Search(ctx, Query{Latitude: 12.9352, Longitude: 77.6245, RadiusKM: 1}, DemoUser)
	if err != nil || len(list) != 2 {
		t.Fatalf("local results: %v %v", list, err)
	}
	if list[0].ID != "coffee-01" || list[0].DistanceM > list[1].DistanceM {
		t.Fatal("results must be nearest first")
	}
	list, err = store.Search(ctx, Query{Latitude: 12.9352, Longitude: 77.6245, RadiusKM: 10, Category: "games"}, DemoUser)
	if err != nil || len(list) != 1 || list[0].ID != "games-01" {
		t.Fatal("category filter failed")
	}
	list, err = store.Search(ctx, Query{Latitude: 0, Longitude: 0, RadiusKM: 10}, DemoUser)
	if err != nil || len(list) != 0 {
		t.Fatal("far away search should be empty")
	}
}

func TestConcurrentLastSpot(t *testing.T) {
	store := NewMemory(time.Now())
	ctx := context.Background()
	var wins atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 25; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			err := store.SetJoined(ctx, "games-01", fmt.Sprint("viewer-", i), true)
			if err == nil {
				wins.Add(1)
			} else if !errors.Is(err, ErrFull) {
				t.Errorf("unexpected: %v", err)
			}
		}(i)
	}
	wg.Wait()
	if wins.Load() != 1 {
		t.Fatalf("wanted one winner, got %d", wins.Load())
	}
	c, _ := store.Get(ctx, "games-01", DemoUser)
	if len(c.Attendees) != c.Capacity {
		t.Fatal("capacity invariant failed")
	}
}

func TestMembershipRetriesAndClosedCircle(t *testing.T) {
	store := NewMemory(time.Now())
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if err := store.SetJoined(ctx, "coffee-01", DemoUser, true); err != nil {
			t.Fatal(err)
		}
	}
	c, _ := store.Get(ctx, "coffee-01", DemoUser)
	if !c.Joined || len(c.Attendees) != 5 {
		t.Fatal("duplicate join")
	}
	// Caller mutation must not modify the store's attendee slice.
	c.Attendees[0].Name = "Changed"
	fresh, _ := store.Get(ctx, "coffee-01", DemoUser)
	if fresh.Attendees[0].Name == "Changed" {
		t.Fatal("aliased slice")
	}
	mine, _ := store.Mine(ctx, DemoUser)
	if len(mine) != 1 {
		t.Fatal("missing joined circle")
	}
	for i := 0; i < 3; i++ {
		if err := store.SetJoined(ctx, "coffee-01", DemoUser, false); err != nil {
			t.Fatal(err)
		}
	}
	mine, _ = store.Mine(ctx, DemoUser)
	if len(mine) != 0 {
		t.Fatal("leave not persisted")
	}
	c = store.data["coffee-01"]
	c.StartsAt = time.Now().Add(-time.Hour)
	store.data[c.ID] = c
	if err := store.SetJoined(ctx, c.ID, DemoUser, true); !errors.Is(err, ErrClosed) {
		t.Fatalf("expected closed: %v", err)
	}
	if err := store.SetJoined(ctx, "missing", DemoUser, true); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestInvalidQueries(t *testing.T) {
	for _, q := range []Query{{Latitude: 91, RadiusKM: 5}, {Longitude: 181, RadiusKM: 5}, {RadiusKM: 0}, {RadiusKM: 11}, {RadiusKM: math.NaN()}, {RadiusKM: 5, Category: "bad/category"}} {
		if q.Valid() {
			t.Fatalf("accepted invalid query %#v", q)
		}
	}
}
