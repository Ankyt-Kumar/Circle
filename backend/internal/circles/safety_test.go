package circles

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func exerciseSafety(t *testing.T, store Store, a, b, c string) {
	t.Helper()
	ctx := context.Background()
	s := store.(SafetyStore)
	draft := lifecycleDraft("safety-shared-circle-1234")
	created, err := store.Create(ctx, draft, a)
	if err != nil {
		t.Fatal(err)
	}
	publishForTest(t, store, created.ID)
	for _, u := range []string{b, c} {
		if err = store.SetJoined(ctx, created.ID, u, true); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < 2; i++ {
		if err = s.Block(ctx, created.ID, b, a); err != nil {
			t.Fatal(err)
		}
	}
	promoted, err := store.Get(ctx, created.ID, b)
	if err != nil || !promoted.IsHost || len(promoted.Attendees) != 2 {
		t.Fatal("block did not leave and transfer", err)
	}
	if _, err = store.Get(ctx, created.ID, a); !errors.Is(err, ErrNotFound) {
		t.Fatal("blocked detail", err)
	}
	if err = store.SetJoined(ctx, created.ID, a, true); !errors.Is(err, ErrBlocked) {
		t.Fatal("blocked join", err)
	}
	if _, err = store.Create(ctx, draft, a); !errors.Is(err, ErrBlocked) {
		t.Fatal("retry exposed blocked circle", err)
	}
	mine, err := store.Mine(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	nearby, err := store.Search(ctx, Query{Latitude: 12.9719, Longitude: 77.6412, RadiusKM: 10}, a)
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range append(mine, nearby...) {
		if v.ID == created.ID {
			t.Fatal("blocked circle listed")
		}
	}
	blocks, err := s.Blocks(ctx, a)
	if err != nil || len(blocks) != 1 || blocks[0].ID != b {
		t.Fatal("block list", err)
	}
	own, err := store.Create(ctx, lifecycleDraft("blocked-user-other-circle"), a)
	if err != nil {
		t.Fatal(err)
	}
	publishForTest(t, store, own.ID)
	if _, err = store.Get(ctx, own.ID, b); !errors.Is(err, ErrNotFound) {
		t.Fatal("reverse block visibility", err)
	}
	if err = store.SetJoined(ctx, own.ID, b, true); !errors.Is(err, ErrBlocked) {
		t.Fatal("reverse block join", err)
	}
	if err = s.Unblock(ctx, b, a); err != nil {
		t.Fatal(err)
	}
	revealed, err := store.Get(ctx, created.ID, a)
	if err != nil || revealed.Joined || revealed.IsHost {
		t.Fatal("unblock rejoined", err)
	}
	for _, u := range []string{b, c} {
		if err = store.SetJoined(ctx, created.ID, u, false); err != nil {
			t.Fatal(err)
		}
	}
}
func TestBlockAndUnblock(t *testing.T) {
	exerciseSafety(t, NewMemory(time.Now()), DemoUser, "b", "c")
}
func TestCirclePublishesImmediately(t *testing.T) {
	ctx := context.Background()
	m := NewMemory(time.Now())
	c, e := m.Create(ctx, lifecycleDraft("instant-publish-request"), DemoUser)
	if e != nil || c.Status != "published" {
		t.Fatal(c.Status, e)
	}
	if _, e = m.Get(ctx, c.ID, "other"); e != nil {
		t.Fatal(e)
	}
	if e = m.SetJoined(ctx, c.ID, "other", true); e != nil {
		t.Fatal(e)
	}
}
func exerciseReview(t *testing.T, p *Postgres, a, b string) {
	ctx := context.Background()
	c, e := p.Create(ctx, lifecycleDraft("post-publication-report"), a)
	if e != nil || c.Status != "published" {
		t.Fatal(c.Status, e)
	}
	if e = p.SetJoined(ctx, c.ID, b, true); e != nil {
		t.Fatal(e)
	}
	var jobs int
	if e = p.db.QueryRow(`SELECT count(*) FROM circle_review_jobs WHERE circle_id=$1`, c.ID).Scan(&jobs); e != nil || jobs != 0 {
		t.Fatal("creation queued a review", jobs, e)
	}
}
func TestConcurrentBlockAndJoinNeverShareACircle(t *testing.T) {
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		m := NewMemory(time.Now())
		c, _ := m.Create(ctx, lifecycleDraft(fmt.Sprintf("block-join-race-%04d", i)), DemoUser)
		publishForTest(t, m, c.ID)
		if err := m.SetJoined(ctx, c.ID, "b", true); err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			if err := m.Block(ctx, c.ID, "b", DemoUser); err != nil {
				t.Error(err)
			}
		}()
		go func() {
			defer wg.Done()
			if err := m.SetJoined(ctx, c.ID, DemoUser, true); err != nil && !errors.Is(err, ErrBlocked) {
				t.Error(err)
			}
		}()
		wg.Wait()
		latest, err := m.Get(ctx, c.ID, "b")
		if err != nil || !latest.IsHost || contains(latest, DemoUser) {
			t.Fatal("shared after block race", err)
		}
	}
}
