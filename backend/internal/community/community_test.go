package community

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/circles"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeAI struct {
	calls      int
	generate   json.RawMessage
	vector     []float64
	embedCalls int
}

func (f *fakeAI) Generate(context.Context, string, any, any) (json.RawMessage, error) {
	f.calls++
	if f.generate != nil {
		return f.generate, nil
	}
	return nil, errors.New("fixture has no generation response")
}

type fixture struct {
	ctx             context.Context
	s               *Service
	c               *circles.Postgres
	a, b, other, id string
}

func setup(t *testing.T) *fixture {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL to a disposable PostGIS database")
	}
	ctx := context.Background()
	admin, e := sql.Open("pgx", dsn)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = admin.Exec(`CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public`); e != nil {
		t.Fatal(e)
	}
	schema := fmt.Sprintf("community_%d", time.Now().UnixNano())
	if _, e = admin.Exec("CREATE SCHEMA " + schema); e != nil {
		t.Fatal(e)
	}
	config, e := pgx.ParseConfig(dsn)
	if e != nil {
		t.Fatal(e)
	}
	config.RuntimeParams["search_path"] = schema + ",public"
	db := stdlib.OpenDB(*config)
	db.SetMaxOpenConns(1)
	if _, e = db.Exec("SET search_path TO " + schema + ",public"); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close(); admin.Exec("DROP SCHEMA " + schema + " CASCADE"); admin.Close() })
	paths, _ := filepath.Glob("../../db/*.sql")
	for _, p := range paths {
		if strings.Contains(p, "optional") {
			continue
		}
		raw, e := os.ReadFile(p)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = db.Exec(string(raw)); e != nil {
			t.Fatalf("%s: %v", p, e)
		}
	}
	f := &fixture{ctx: ctx, s: New(db, &fakeAI{}, "test-gemini", 50), c: circles.NewPostgres(db)}
	for _, target := range []*string{&f.a, &f.b, &f.other} {
		a, e := accounts.NewPostgres(db).Resolve(ctx, "test", token())
		if e != nil {
			t.Fatal(e)
		}
		*target = a.ID
		_, e = accounts.NewPostgres(db).SavePreferences(ctx, a.ID, accounts.PreferencesInput{FirstName: "Tester", Preferences: accounts.Preferences{BirthDate:"1995-06-15", Gender:"male", Latitude:coordinate(12.9719), Longitude:coordinate(77.6412), Interests: []string{"coffee"}, AreaName: "Koramangala", RadiusKM: 5, AdultConfirmed: true, TermsVersion: accounts.TermsVersion}})
		if e != nil {
			t.Fatal(e)
		}
	}
	c, e := f.c.Create(ctx, circles.CreateInput{RequestID: token(), Title: "Coffee together", Description: "Meet at the sample public cafe.", Category: "coffee", VenueID: "twc-hsr", Capacity: 6, StartsAt: time.Now().Add(30 * time.Minute), EndsAt: time.Now().Add(90 * time.Minute)}, f.a)
	if e != nil {
		t.Fatal(e)
	}
	f.id = c.ID
	if e = f.c.SetJoined(ctx, f.id, f.b, true); e != nil {
		t.Fatal(e)
	}
	return f
}
func (f *fixture) exec(t *testing.T, q string, args ...any) {
	t.Helper()
	if _, e := f.s.DB.ExecContext(f.ctx, q, args...); e != nil {
		t.Fatal(e)
	}
}
func (f *fixture) send(t *testing.T, user string) Message {
	t.Helper()
	m, e := f.s.Send(f.ctx, f.id, user, token(), "Looking forward to coffee!")
	if e != nil {
		t.Fatal(e)
	}
	return m
}
func TestChatAuthorizationAndDeduplication(t *testing.T) {
	f := setup(t)
	if _, e := f.s.Messages(f.ctx, f.id, f.other, 0); e != ErrForbidden {
		t.Fatal(e)
	}
	id := token()
	m, e := f.s.Send(f.ctx, f.id, f.a, id, "Hello circle!")
	if e != nil {
		t.Fatal(e)
	}
	again, e := f.s.Send(f.ctx, f.id, f.a, id, "Hello circle!")
	if e != nil || m.ID != again.ID {
		t.Fatal("duplicate", e)
	}
	if _, e = f.s.Send(f.ctx, f.id, f.a, id, "Changed body"); e != ErrConflict {
		t.Fatal(e)
	}
	page, e := f.s.Messages(f.ctx, f.id, f.b, 0)
	if e != nil || len(page.Messages) != 1 {
		t.Fatal("message was not immediately visible", page, e)
	}
	page, e = f.s.Messages(f.ctx, f.id, f.b, 0)
	if e != nil || len(page.Messages) != 1 || page.Messages[0].Status != "allowed" {
		t.Fatal(page, e)
	}
	if e = f.c.SetJoined(f.ctx, f.id, f.b, false); e != nil {
		t.Fatal(e)
	}
	if _, e = f.s.Messages(f.ctx, f.id, f.b, 0); e != ErrForbidden {
		t.Fatal("left member read", e)
	}
}
func TestChatDoesNotUseAIOrWaitForApproval(t *testing.T) {
	f := setup(t)
	ai := &fakeAI{}
	f.s.AI = ai
	f.s.DailyLimit = 0
	m := f.send(t, f.a)
	if m.Status != "allowed" {
		t.Fatal(m.Status)
	}
	if ai.calls != 0 {
		t.Fatal("AI called")
	}
	page, e := f.s.Messages(f.ctx, f.id, f.b, 0)
	if e != nil || len(page.Messages) != 1 {
		t.Fatal(page, e)
	}
	f.s.AI = nil
	if m = f.send(t, f.b); m.Status != "allowed" {
		t.Fatal(m.Status)
	}
}
func TestNoSendingAfterLeaveEndOrBlock(t *testing.T) {
	for _, which := range []string{"leave", "end", "block"} {
		t.Run(which, func(t *testing.T) {
			f := setup(t)
			switch which {
			case "leave":
				if e := f.c.SetJoined(f.ctx, f.id, f.b, false); e != nil {
					t.Fatal(e)
				}
			case "end":
				f.exec(t, `UPDATE circles SET starts_at=now()-interval '2 hours',ends_at=now()-interval '1 hour' WHERE id=$1`, f.id)
			case "block":
				if e := f.c.Block(f.ctx, f.id, f.a, f.b); e != nil {
					t.Fatal(e)
				}
			}
			if _, e := f.s.Send(f.ctx, f.id, f.b, token(), "Should not be posted"); e == nil {
				t.Fatal("write accepted after", which)
			}
		})
	}
}
func TestArchiveAndFeedback(t *testing.T) {
	f := setup(t)
	f.send(t, f.a)
	f.exec(t, `UPDATE circles SET starts_at=now()-interval '2 hours',ends_at=now()-interval '1 hour' WHERE id=$1`, f.id)
	p, e := f.s.Messages(f.ctx, f.id, f.b, 0)
	if e != nil || !p.Archived || len(p.Messages) != 1 {
		t.Fatal(p, e)
	}
	if _, e = f.s.Send(f.ctx, f.id, f.b, token(), "Late message"); e != ErrConflict {
		t.Fatal(e)
	}
	if e = f.s.Feedback(f.ctx, f.id, f.b, true, "good"); e != nil {
		t.Fatal(e)
	}
	stats, e := f.s.Stats(f.ctx, f.b)
	if e != nil || stats.Attended != 1 {
		t.Fatal(stats, e)
	}
	list, e := f.c.Mine(f.ctx, f.b)
	if e != nil || len(list) != 1 {
		t.Fatal("archive missing", e)
	}
}
func TestHostEditAndCancellation(t *testing.T) {
	f := setup(t)
	c, e := f.c.Get(f.ctx, f.id, f.a)
	if e != nil {
		t.Fatal(e)
	}
	d := EventInput{Revision: c.Revision, Title: "Board games together", Description: "Try a new game at the public cafe.", Category: "games", VenueID: "twc-indiranagar", Capacity: 6, StartsAt: time.Now().Add(time.Hour), EndsAt: time.Now().Add(2 * time.Hour)}
	if e = f.s.ChangeEvent(f.ctx, f.id, f.b, d, false); e != ErrForbidden {
		t.Fatal(e)
	}
	if e = f.s.ChangeEvent(f.ctx, f.id, f.a, d, false); e != nil {
		t.Fatal(e)
	}
	c, e = f.c.Get(f.ctx, f.id, f.b)
	if e != nil || c.Status != "published" || c.Revision != 2 {
		t.Fatal(c, e)
	}
	if e = f.s.ChangeEvent(f.ctx, f.id, f.a, d, false); e != ErrConflict {
		t.Fatal("lost update", e)
	}
	d.Revision = 2
	if e = f.s.ChangeEvent(f.ctx, f.id, f.a, d, true); e != nil {
		t.Fatal(e)
	}
	c, e = f.c.Get(f.ctx, f.id, f.b)
	if e != nil || c.Status != "cancelled" {
		t.Fatal(c, e)
	}
	n, e := f.s.Inbox(f.ctx, f.b)
	if e != nil || len(n) != 2 || n[0].Kind != "cancelled" {
		t.Fatal(n, e)
	}
	if e = f.c.Block(f.ctx, f.id, f.a, f.other); e == nil {
		t.Fatal("outsider used a cancelled event to block a member")
	}
	if e = f.c.Block(f.ctx, f.id, f.a, f.b); e != nil {
		t.Fatal("member could not block host after cancellation", e)
	}
	if _, e = f.c.Get(f.ctx, f.id, f.b); e == nil {
		t.Fatal("blocked cancelled event still visible")
	}
}

func TestCancelEventPersistsAndNotifiesMembers(t *testing.T) {
	for _, wantsChanges := range []bool{true, false} {
		t.Run(fmt.Sprintf("member_changes_%t", wantsChanges), func(t *testing.T) {
			f := setup(t)
			f.send(t, f.a)
			if e := f.s.SaveNotificationPrefs(f.ctx, f.b, NotificationPreferences{Changes: wantsChanges}); e != nil {
				t.Fatal(e)
			}
			input := EventInput{Revision: 1}
			if e := f.s.ChangeEvent(f.ctx, f.id, f.b, input, true); e != ErrForbidden {
				t.Fatalf("non-host cancellation: %v", e)
			}
			if e := f.s.ChangeEvent(f.ctx, f.id, f.a, input, true); e != nil {
				t.Fatalf("cancel event and save notifications: %v", e)
			}
			c, e := f.c.Get(f.ctx, f.id, f.b)
			if e != nil || c.Status != "cancelled" || c.Revision != 2 || len(c.Attendees) != 2 {
				t.Fatalf("cancel must persist without removing members: %+v, %v", c, e)
			}
			chat, e := f.s.Messages(f.ctx, f.id, f.b, 0)
			if e != nil || !chat.Archived || len(chat.Messages) != 1 {
				t.Fatalf("cancelled chat must retain history as read-only: %+v, %v", chat, e)
			}
			if _, e = f.s.Send(f.ctx, f.id, f.b, token(), "After cancellation"); e != ErrConflict {
				t.Fatalf("cancelled chat accepted a message: %v", e)
			}
			// A stale repeat must neither increment the revision nor duplicate notices.
			if e = f.s.ChangeEvent(f.ctx, f.id, f.a, input, true); e != ErrConflict {
				t.Fatalf("repeat cancellation: %v", e)
			}
			for _, user := range []string{f.a, f.b} {
				want := 1
				if user == f.b && !wantsChanges {
					want = 0
				}
				notices, e := f.s.Inbox(f.ctx, user)
				if e != nil || len(notices) != want {
					t.Fatalf("member inbox: %+v, %v; want %d notices", notices, e, want)
				}
				if want == 1 {
					if notices[0].Kind != "cancelled" {
						t.Fatalf("unexpected notification: %+v", notices[0])
					}
					var key string
					e = f.s.DB.QueryRowContext(f.ctx, `SELECT event_key FROM notification_outbox WHERE circle_id=$1 AND user_id=$2::uuid AND kind='cancelled'`, f.id, user).Scan(&key)
					if e != nil || key != f.id+":cancelled:2:"+user {
						t.Fatalf("revision missing from notification key: %q, %v", key, e)
					}
				}
			}
		})
	}
}

type fakeDeleter struct{ fail bool }

func (d *fakeDeleter) DeleteAccount(context.Context, string) error {
	if d.fail {
		return errors.New("offline")
	}
	return nil
}
func TestDeletionTransfersHostingAndRetriesFirebase(t *testing.T) {
	f := setup(t)
	f.send(t, f.a)
	d := &fakeDeleter{fail: true}
	f.s.Deleter = d
	if e := f.s.DeleteAccount(f.ctx, f.a); e != nil {
		t.Fatal(e)
	}
	c, e := f.c.Get(f.ctx, f.id, f.b)
	if e != nil || !c.IsHost {
		t.Fatal(c, e)
	}
	var status string
	if e = f.s.DB.QueryRow(`SELECT account_status FROM users WHERE id=$1::uuid`, f.a).Scan(&status); e != nil || status != "deleting" {
		t.Fatal(status, e)
	}
	if e = f.s.DeleteOne(f.ctx); e != nil {
		t.Fatal(e)
	}
	d.fail = false
	f.exec(t, `UPDATE account_deletion_jobs SET next_attempt_at=now()`)
	if e = f.s.DeleteOne(f.ctx); e != nil {
		t.Fatal(e)
	}
	var n int
	f.s.DB.QueryRow(`SELECT count(*) FROM users WHERE id=$1::uuid`, f.a).Scan(&n)
	if n != 0 {
		t.Fatal("identity retained")
	}
	if e = f.c.SetJoined(f.ctx, f.id, f.b, false); e != nil {
		t.Fatal(e)
	}
	if _, e = f.c.Get(f.ctx, f.id, f.b); e != circles.ErrNotFound {
		t.Fatal("last member circle retained", e)
	}
}
func TestReminderOutboxDeduplicatesAndRespectsPreferences(t *testing.T) {
	f := setup(t)
	if e := f.s.SaveNotificationPrefs(f.ctx, f.b, NotificationPreferences{Changes: true}); e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 2; i++ {
		if e := f.s.Reminders(f.ctx); e != nil {
			t.Fatal(e)
		}
	}
	a, e := f.s.Inbox(f.ctx, f.a)
	if e != nil || len(a) != 1 {
		t.Fatal(a, e)
	}
	b, e := f.s.Inbox(f.ctx, f.b)
	if e != nil || len(b) != 0 {
		t.Fatal(b, e)
	}
}

func (f *fakeAI) Embed(context.Context, string) ([]float64, error) {
	f.embedCalls++
	if f.vector == nil {
		return nil, errors.New("no vector")
	}
	return f.vector, nil
}

func TestDraftWithoutCatalogRequiresExplicitVenueChoiceAndEnforcesQuota(t *testing.T) {
	f := setup(t)
	f.exec(t, `UPDATE public_venues SET active=false`)
	ai := f.s.AI.(*fakeAI)
	ai.generate = json.RawMessage(`{"title":"Coffee together","description":"Meet for a public coffee chat.","category":"coffee","venue_id":"invented","time_suggestion":"Tomorrow afternoon"}`)
	if _, e := f.s.Draft(f.ctx, f.a, "Coffee tomorrow"); e != ErrUnavailable {
		t.Fatal("invented venue accepted", e)
	}
	ai.generate = json.RawMessage(`{"title":"Sketch together","description":"Meet for a public outdoor sketching session.","category":"Urban sketching","venue_id":"","time_suggestion":"Tomorrow afternoon"}`)
	for i := 0; i < 4; i++ {
		if _, e := f.s.Draft(f.ctx, f.a, "Coffee tomorrow"); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := f.s.Draft(f.ctx, f.a, "Coffee tomorrow"); e != ErrLimit {
		t.Fatal("per-account quota", e)
	}
	f.s.DailyLimit = 5
	if _, e := f.s.Draft(f.ctx, f.b, "Coffee tomorrow"); e != ErrLimit {
		t.Fatal("shared quota", e)
	}
}

func TestUnconfiguredSuggestionsDoNotRequireDatabaseOrProvider(t *testing.T) {
	service := New(nil, nil, "", 0)
	if _, err := service.Draft(context.Background(), "unused", "Coffee tomorrow"); err != ErrUnavailable {
		t.Fatal("unconfigured suggestions must be unavailable", err)
	}
}

func TestSuggestionRejectsTrailingResponse(t *testing.T) {
	f := setup(t)
	f.s.AI.(*fakeAI).generate = json.RawMessage(`{"title":"Coffee together","description":"Meet for a public coffee chat.","category":"coffee","venue_id":"","time_suggestion":"Tomorrow afternoon"}{}`)
	if _, err := f.s.Draft(f.ctx, f.a, "Coffee tomorrow"); err != ErrUnavailable {
		t.Fatal("trailing response accepted", err)
	}
}
func TestRemovedCircleCannotExposeChat(t *testing.T) {
	f := setup(t)
	f.send(t, f.a)
	f.exec(t, `UPDATE circles SET status='archived' WHERE id=$1`, f.id)
	if _, e := f.s.Messages(f.ctx, f.id, f.b, 0); e != ErrForbidden {
		t.Fatal("removed chat visible", e)
	}
}
func TestOptionalRecommendationsAndEmbeddingRetries(t *testing.T) {
	if os.Getenv("TEST_VECTOR") != "true" {
		t.Skip("set TEST_VECTOR=true with pgvector installed")
	}
	f := setup(t)
	f.exec(t, `UPDATE circles SET status='archived' WHERE id<>$1`, f.id)
	f.exec(t, `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public`)
	migration, e := os.ReadFile("../../db/010_recommendations_optional.sql")
	if e != nil {
		t.Fatal(e)
	}
	f.exec(t, string(migration))
	ai := f.s.AI.(*fakeAI)
	for i := 0; i < 3; i++ {
		if e = f.s.EmbedOne(f.ctx); e == nil {
			t.Fatal("provider failure lost")
		}
		f.exec(t, `UPDATE embedding_jobs SET next_attempt_at=now()-interval '1 second'`)
	}
	if e = f.s.EmbedOne(f.ctx); e != nil || ai.embedCalls != 3 {
		t.Fatal("unbounded retries", e, ai.embedCalls)
	}
	f.exec(t, `UPDATE circles SET revision=revision+1 WHERE id=$1`, f.id)
	ai.vector = make([]float64, 768)
	ai.vector[0] = 1
	if e = f.s.EmbedOne(f.ctx); e != nil {
		t.Fatal(e)
	}
	var count int
	if e = f.s.DB.QueryRow(`SELECT count(*) FROM circle_embeddings`).Scan(&count); e != nil || count != 1 {
		t.Fatal("embedding not stored", e, count)
	}
	start := time.Now().Add(time.Hour)
	c, e := f.c.Create(f.ctx, circles.CreateInput{RequestID: token(), Title: "Another coffee", Description: "Meet at the sample cafe together.", Category: "coffee", VenueID: "twc-hsr", Capacity: 6, StartsAt: start, EndsAt: start.Add(time.Hour)}, f.other)
	if e != nil {
		t.Fatal(e)
	}

	if e = f.s.EmbedOne(f.ctx); e != nil {
		t.Fatal(e)
	}
	q := circles.Query{Latitude: 12.9352, Longitude: 77.6245, RadiusKM: 5}
	list, basis, e := f.s.Recommend(f.ctx, f.c, f.b, q)
	if e != nil || basis != "past_joins" || len(list) == 0 || list[0].ID != c.ID {
		t.Fatal("join ranking", list, basis, e)
	}
	f.exec(t, `UPDATE circles SET status='pending_review' WHERE id=$1`, c.ID)
	list, _, e = f.s.Recommend(f.ctx, f.c, f.b, q)
	if e != nil {
		t.Fatal(e)
	}
	for _, item := range list {
		if item.ID == c.ID || item.ID == f.id {
			t.Fatal("pending/joined recommendation leaked")
		}
	}
}

func TestHostCannotReopenRemovedCircle(t *testing.T) {
	f := setup(t)
	f.exec(t, `UPDATE circles SET status='archived' WHERE id=$1`, f.id)
	start := time.Now().Add(time.Hour)
	input := EventInput{Revision: 1, Title: "Coffee again", Description: "Meet at a public cafe together.", Category: "coffee", VenueID: "twc-hsr", Capacity: 6, StartsAt: start, EndsAt: start.Add(time.Hour)}
	if e := f.s.ChangeEvent(f.ctx, f.id, f.a, input, false); e != ErrConflict {
		t.Fatal("removed circle reopened", e)
	}
}

func TestBlockedPersonHostedAndJoinedPlansDisappear(t *testing.T) {
	f := setup(t)
	start := time.Now().Add(time.Hour)
	c, e := f.c.Create(f.ctx, circles.CreateInput{RequestID: token(), Title: "Another plan", Description: "A coffee with the neighbors.", Category: "coffee", VenueID: "twc-indiranagar", Capacity: 6, StartsAt: start, EndsAt: start.Add(time.Hour)}, f.other)
	if e != nil {
		t.Fatal(e)
	}
	if e = f.c.SetJoined(f.ctx, c.ID, f.b, true); e != nil {
		t.Fatal(e)
	}
	// A blocks B in A's circle. B becomes host there; B is only an attendee in C's circle.
	if e = f.c.Block(f.ctx, f.id, f.b, f.a); e != nil {
		t.Fatal(e)
	}
	q := circles.Query{Latitude: 12.95, Longitude: 77.63, RadiusKM: 10}
	cursor := ""
	for {
		pq, e := circles.WithPage(q, f.a, cursor, 1)
		if e != nil {
			t.Fatal(e)
		}
		rows, e := f.c.Search(f.ctx, pq, f.a)
		if e != nil {
			t.Fatal(e)
		}
		page := circles.MakePage(pq, f.a, rows)
		for _, v := range page.Circles {
			if v.ID == f.id || v.ID == c.ID {
				t.Fatal("blocked member visible in page")
			}
		}
		cursor = page.NextCursor
		if cursor == "" {
			break
		}
	}
	list, _, e := f.s.Recommend(f.ctx, f.c, f.a, q)
	if e != nil {
		t.Fatal(e)
	}
	for _, v := range list {
		if v.ID == f.id || v.ID == c.ID {
			t.Fatal("blocked member recommended")
		}
	}
	if e = f.c.Unblock(f.ctx, f.b, f.a); e != nil {
		t.Fatal(e)
	}
	restored, e := f.c.Get(f.ctx, f.id, f.a)
	if e != nil || restored.Joined {
		t.Fatal("unblock rejoined or hid circle", e)
	}
}

func TestImmediatePublishingUpgradeReleasesBacklogAndIsRepeatable(t *testing.T) {
	f := setup(t)
	m := f.send(t, f.a)
	f.exec(t, `UPDATE circles SET status='pending_review' WHERE id=$1`, f.id)
	f.exec(t, `INSERT INTO circle_review_jobs(circle_id,state,lease_token,lease_until) VALUES($1,'running','old-worker',now()+interval '1 hour')`, f.id)
	f.exec(t, `UPDATE chat_messages SET status='pending',review_required=true,lease_token='old-worker',lease_until=now()+interval '1 hour' WHERE id=$1`, m.ID)
	raw, e := os.ReadFile("../../db/011_immediate_publishing.sql")
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 2; i++ {
		if _, e = f.s.DB.Exec(string(raw)); e != nil {
			t.Fatal(e)
		}
	}
	c, e := f.c.Get(f.ctx, f.id, f.b)
	if e != nil || c.Status != "published" {
		t.Fatal("circle backlog", c, e)
	}
	page, e := f.s.Messages(f.ctx, f.id, f.b, 0)
	if e != nil || len(page.Messages) != 1 || page.Messages[0].Status != "allowed" {
		t.Fatal("message backlog", page, e)
	}
	var done bool
	if e = f.s.DB.QueryRow(`SELECT state='done' AND lease_token='' AND lease_until IS NULL FROM circle_review_jobs WHERE circle_id=$1`, f.id).Scan(&done); e != nil || !done {
		t.Fatal("old lease remains", e)
	}
	if e = circles.CheckSchema(f.ctx, f.s.DB); e != nil {
		t.Fatal(e)
	}
	f.exec(t, `UPDATE circles SET status='cancelled' WHERE id=$1`, f.id)
	if _, e = f.s.DB.Exec(string(raw)); e != nil {
		t.Fatal(e)
	}
	c, e = f.c.Get(f.ctx, f.id, f.a)
	if e != nil || c.Status != "cancelled" {
		t.Fatal("cancelled event reopened", c, e)
	}
}

func coordinate(value float64) *float64 { return &value }
