package circles

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func lifecycleDraft(request string) CreateInput {
	start := time.Now().UTC().Add(time.Hour)
	return CreateInput{RequestID: request, Title: "Coffee together", Description: "A sample public meetup.", Category: "coffee", VenueID: "indiranagar-cafe", StartsAt: start, EndsAt: start.Add(time.Hour), Capacity: 6}
}

func assertAbsent(t *testing.T, store Store, id string, users ...string) {
	t.Helper()
	ctx := context.Background()
	for _, user := range users {
		if _, err := store.Get(ctx, id, user); !errors.Is(err, ErrNotFound) {
			t.Fatalf("deleted detail: %v", err)
		}
		mine, err := store.Mine(ctx, user)
		if err != nil {
			t.Fatal(err)
		}
		nearby, err := store.Search(ctx, Query{Latitude: 12.9719, Longitude: 77.6412, RadiusKM: 10}, user)
		if err != nil {
			t.Fatal(err)
		}
		for _, c := range append(mine, nearby...) {
			if c.ID == id {
				t.Fatal("deleted circle still listed")
			}
		}
	}
}

func publishForTest(t *testing.T, store Store, id string) {
	t.Helper()
	// New creates are already public. Kept as an assertion in lifecycle fixtures.
	if p, ok := store.(*Postgres); ok {
		var status string
		if e := p.db.QueryRow(`SELECT status FROM circles WHERE id=$1`, id).Scan(&status); e != nil || status != "published" {
			t.Fatal(status, e)
		}
	}
}
func exerciseSuccession(t *testing.T, store Store, a, b, c string) {
	t.Helper()
	ctx := context.Background()
	draft := lifecycleDraft("host-transfer-request-123")
	created, err := store.Create(ctx, draft, a)
	if err != nil || !created.IsHost {
		t.Fatal(err)
	}
	publishForTest(t, store, created.ID)
	for _, u := range []string{b, c} {
		if err = store.SetJoined(ctx, created.ID, u, true); err != nil {
			t.Fatal(err)
		}
	}
	if err = store.SetJoined(ctx, created.ID, b, false); err != nil {
		t.Fatal(err)
	}
	unchanged, err := store.Get(ctx, created.ID, a)
	if err != nil || !unchanged.IsHost || len(unchanged.Attendees) != 2 {
		t.Fatal("ordinary leave", err)
	}
	for i := 0; i < 2; i++ {
		if err = store.SetJoined(ctx, created.ID, b, true); err != nil {
			t.Fatal(err)
		}
	}
	if pg, ok := store.(*Postgres); ok {
		if _, err = pg.db.ExecContext(ctx, `UPDATE circle_members SET joined_at='2026-01-01T00:00:00Z' WHERE circle_id=$1`, created.ID); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < 2; i++ {
		if err = store.SetJoined(ctx, created.ID, a, false); err != nil {
			t.Fatal(err)
		}
	}
	successor, err := store.Get(ctx, created.ID, c)
	if err != nil || !successor.IsHost || len(successor.Attendees) != 2 {
		t.Fatal("wrong successor", successor, err)
	}
	retry, err := store.Create(ctx, draft, a)
	if err != nil || retry.IsHost || retry.Joined {
		t.Fatal("retry reclaimed hosting", err)
	}
	if err = store.SetJoined(ctx, created.ID, c, false); err != nil {
		t.Fatal(err)
	}
	successor, err = store.Get(ctx, created.ID, b)
	if err != nil || !successor.IsHost || len(successor.Attendees) != 1 {
		t.Fatal("second succession", err)
	}
	mine, err := store.Mine(ctx, b)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, v := range mine {
		if v.ID == created.ID && v.IsHost {
			found = true
		}
	}
	if !found {
		t.Fatal("new host lost circle on refresh")
	}
	if err = store.SetJoined(ctx, created.ID, b, false); err != nil {
		t.Fatal(err)
	}
	assertAbsent(t, store, created.ID, a, b, c)
	if _, err = store.Create(ctx, draft, a); !errors.Is(err, ErrDeleted) {
		t.Fatal("deleted circle revived", err)
	}
}
func TestHostTransfersInRemainingJoinOrder(t *testing.T) {
	exerciseSuccession(t, NewMemory(time.Now()), DemoUser, "b", "c")
}

func TestOrdinaryLeaveKeepsCircleAndLastMemberRemovesLegacyCircle(t *testing.T) {
	ctx := context.Background()
	store := NewMemory(time.Now())
	c, _ := store.Create(ctx, lifecycleDraft("ordinary-leave-request"), DemoUser)
	publishForTest(t, store, c.ID)
	_ = store.SetJoined(ctx, c.ID, "guest", true)
	if err := store.SetJoined(ctx, c.ID, "guest", false); err != nil {
		t.Fatal(err)
	}
	if c, err := store.Get(ctx, c.ID, DemoUser); err != nil || len(c.Attendees) != 1 {
		t.Fatal("ordinary leave removed host's circle", err)
	}
	// Legacy/inconsistent data with a missing host is still cleaned up.
	store.mu.Lock()
	c = store.data[c.ID]
	c.HostID = "missing-legacy-host"
	store.data[c.ID] = c
	store.mu.Unlock()
	if err := store.SetJoined(ctx, c.ID, DemoUser, false); err != nil {
		t.Fatal(err)
	}
	assertAbsent(t, store, c.ID, DemoUser)
}

func TestJoinRacingHostLeaveNeverLeavesAnOrphan(t *testing.T) {
	ctx := context.Background()
	store := NewMemory(time.Now())
	for i := 0; i < 25; i++ {
		draft := lifecycleDraft(fmt.Sprintf("race-host-leave-%06d", i))
		c, err := store.Create(ctx, draft, DemoUser)
		if err != nil {
			t.Fatal(err)
		}
		publishForTest(t, store, c.ID)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			if err := store.SetJoined(ctx, c.ID, DemoUser, false); err != nil {
				t.Error(err)
			}
		}()
		go func() {
			defer wg.Done()
			if err := store.SetJoined(ctx, c.ID, "guest", true); err != nil && !errors.Is(err, ErrNotFound) {
				t.Error(err)
			}
		}()
		wg.Wait()
		surviving, err := store.Get(ctx, c.ID, "guest")
		if err == nil {
			if !surviving.IsHost || len(surviving.Attendees) != 1 {
				t.Fatal("orphan after race")
			}
			if err = store.SetJoined(ctx, c.ID, "guest", false); err != nil {
				t.Fatal(err)
			}
		} else if !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		assertAbsent(t, store, c.ID, DemoUser, "guest")
	}
}
