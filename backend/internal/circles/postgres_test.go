package circles

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Run only against a disposable local test database. Tables live in an isolated
// schema; this test installs PostGIS in public if it is not already installed.
func TestPostgresIntegration(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL for PostGIS integration")
	}
	ctx := context.Background()
	admin, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	if _, err = admin.ExecContext(ctx, `CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public`); err != nil {
		t.Fatal(err)
	}
	schema := fmt.Sprintf("circle_test_%d", time.Now().UnixNano())
	if _, err = admin.ExecContext(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.ExecContext(ctx, "DROP SCHEMA "+schema+" CASCADE")
	config, err := pgx.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	config.RuntimeParams["search_path"] = schema + ",public"
	db := stdlib.OpenDB(*config)
	defer db.Close()
	db.SetMaxOpenConns(10)
	if os.Getenv("PGLITE_TEST") == "true" {
		db.SetMaxOpenConns(1)
		if _, e := db.ExecContext(ctx, "SET search_path TO "+schema+",public"); e != nil {
			t.Fatal(e)
		}
	}
	for _, name := range []string{"001_schema.sql", "002_demo_seed.sql", "003_accounts.sql", "004_user_preferences.sql", "005_circle_lifecycle.sql", "006_host_succession.sql", "007_safety.sql", "008_automatic_review.sql", "009_community.sql", "011_immediate_publishing.sql", "012_public_venues.sql", "013_profiles_location_eligibility.sql"} {
		if name == "005_circle_lifecycle.sql" {
			if _, err = db.ExecContext(ctx, `INSERT INTO circles SELECT 'empty-legacy',title,category,description,neighborhood,venue,location,starts_at,ends_at,capacity,status FROM circles WHERE id='coffee-01'`); err != nil {
				t.Fatal(err)
			}
		}
		body, err := os.ReadFile("../../db/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = db.ExecContext(ctx, string(body)); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"004_user_preferences.sql", "005_circle_lifecycle.sql", "006_host_succession.sql", "007_safety.sql", "008_automatic_review.sql", "009_community.sql", "011_immediate_publishing.sql", "012_public_venues.sql", "013_profiles_location_eligibility.sql"} {
		body, err := os.ReadFile("../../db/" + name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = db.ExecContext(ctx, string(body)); err != nil {
			t.Fatal("repeat migration", err)
		}
	}
	if _, err = db.Exec(`UPDATE public_venues SET active=true,fictional=false,confirmed_public=true WHERE fictional`); err != nil {
		t.Fatal(err)
	}
    // Legacy fixtures are explicitly reactivated only in this isolated test schema.
    if _,err=db.ExecContext(ctx,`UPDATE circles SET status='published' WHERE id IN ('coffee-01','walk-01','games-01','run-01')`);err!=nil {t.Fatal(err)}
    seedEligibility(t,db)
	var emptyCount int
	if err = db.QueryRowContext(ctx, `SELECT count(*) FROM circles WHERE id='empty-legacy'`).Scan(&emptyCount); err != nil || emptyCount != 0 {
		t.Fatal("empty legacy circle survived migration", err)
	}
	var legacyHostCorrect bool
	if err = db.QueryRowContext(ctx, `SELECT host_id=(SELECT user_id FROM circle_members WHERE circle_id='coffee-01' ORDER BY joined_at,user_id LIMIT 1) FROM circles WHERE id='coffee-01'`).Scan(&legacyHostCorrect); err != nil || !legacyHostCorrect {
		t.Fatal("legacy host assignment", err)
	}
	if err = CheckSchema(ctx, db); err != nil {
		t.Fatal(err)
	}
	old, _ := os.ReadFile("../../db/005_circle_lifecycle.sql")
	if _, err = db.ExecContext(ctx, string(old)); err != nil {
		t.Fatal(err)
	}
	if CheckSchema(ctx, db) == nil {
		t.Fatal("old deletion trigger accepted")
	}
	current, _ := os.ReadFile("../../db/006_host_succession.sql")
	if _, err = db.ExecContext(ctx, string(current)); err != nil {
		t.Fatal(err)
	}
	store := NewPostgres(db)
	list, err := store.Search(ctx, Query{Latitude: 12.9352, Longitude: 77.6245, RadiusKM: 1}, DemoUser)
	if err != nil || len(list) != 2 {
		t.Fatalf("PostGIS search: %v %v", list, err)
	}
	if list[0].ID != "coffee-01" {
		t.Fatal("nearest order")
	}
	for i := 0; i < 2; i++ {
		if err := store.SetJoined(ctx, "coffee-01", DemoUser, true); err != nil {
			t.Fatal(err)
		}
	}
	c, err := store.Get(ctx, "coffee-01", DemoUser)
	if err != nil || !c.Joined || len(c.Attendees) != 5 {
		t.Fatalf("membership: %+v %v", c, err)
	}
	mine, err := store.Mine(ctx, DemoUser)
	if err != nil || len(mine) != 1 {
		t.Fatalf("mine: %v %v", mine, err)
	}
	if err = store.SetJoined(ctx, "coffee-01", DemoUser, false); err != nil {
		t.Fatal(err)
	}
	var users []string
	for i := 1; i <= 20; i++ {
		id := fmt.Sprintf("10000000-0000-0000-0000-%012d", i)
		users = append(users, id)
		if _, err = db.ExecContext(ctx, `INSERT INTO users(id,first_name) VALUES($1,'Test')`, id); err != nil {
			t.Fatal(err)
		}
	}
    seedEligibility(t,db)
	var wins atomic.Int32
	var wg sync.WaitGroup
	for _, id := range users {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			err := store.SetJoined(ctx, "games-01", id, true)
			if err == nil {
				wins.Add(1)
			} else if !errors.Is(err, ErrFull) {
				t.Errorf("join: %v", err)
			}
		}(id)
	}
	wg.Wait()
	if wins.Load() != 1 {
		t.Fatalf("expected one last-spot winner; got %d", wins.Load())
	}
	c, err = store.Get(ctx, "games-01", DemoUser)
	if err != nil || len(c.Attendees) != 6 {
		t.Fatalf("capacity: %+v %v", c, err)
	}
	start := time.Now().UTC().Add(time.Hour)
	draft := CreateInput{RequestID: "postgres-create-123456", Title: "Coffee together", Description: "Sample public meetup.", Category: "coffee", VenueID: "indiranagar-cafe", StartsAt: start, EndsAt: start.Add(time.Hour), Capacity: 6}
	created, err := store.Create(ctx, draft, DemoUser)
	if err != nil || !created.Joined {
		t.Fatalf("create: %+v %v", created, err)
	}
	repeated, err := store.Create(ctx, draft, DemoUser)
	if err != nil || repeated.ID != created.ID || len(repeated.Attendees) != 1 {
		t.Fatalf("retry: %+v %v", repeated, err)
	}
	draft.Title = "Different payload"
	if _, err = store.Create(ctx, draft, DemoUser); !errors.Is(err, ErrRequestConflict) {
		t.Fatalf("expected conflict, got %v", err)
	}
	if !created.IsHost {
		t.Fatal("creator missing host status")
	}
	publishForTest(t, store, created.ID)
	if err = store.SetJoined(ctx, created.ID, users[0], true); err != nil {
		t.Fatal(err)
	}
	guest, err := store.Get(ctx, created.ID, users[0])
	if err != nil || guest.IsHost || !guest.Joined {
		t.Fatal("guest host status", err)
	}
	if err = store.SetJoined(ctx, created.ID, DemoUser, false); err != nil {
		t.Fatal(err)
	}
	promoted, err := store.Get(ctx, created.ID, users[0])
	if err != nil || !promoted.IsHost {
		t.Fatal("transfer", err)
	}
	if err = store.SetJoined(ctx, created.ID, users[0], false); err != nil {
		t.Fatal(err)
	}
	assertAbsent(t, store, created.ID, DemoUser, users[0])
	if err = store.SetJoined(ctx, created.ID, DemoUser, false); err != nil {
		t.Fatal("retry leave", err)
	}
	if _, err = store.Create(ctx, draft, DemoUser); !errors.Is(err, ErrDeleted) {
		t.Fatal("deleted create ID reused", err)
	}
	var count int
	if err = db.QueryRowContext(ctx, `SELECT count(*) FROM circle_members WHERE circle_id=$1`, created.ID).Scan(&count); err != nil || count != 0 {
		t.Fatal("membership cascade", count, err)
	}
	// SQL-trigger path, including a parent-delete cascade with multiple members.
	direct, err := store.Create(ctx, lifecycleDraft("direct-sql-leave-request"), DemoUser)
	if err != nil {
		t.Fatal(err)
	}
	publishForTest(t, store, direct.ID)
	if err = store.SetJoined(ctx, direct.ID, users[0], true); err != nil {
		t.Fatal(err)
	}
	if _, err = db.ExecContext(ctx, `DELETE FROM circle_members WHERE circle_id=$1 AND user_id=$2::uuid`, direct.ID, DemoUser); err != nil {
		t.Fatal(err)
	}
	promoted, err = store.Get(ctx, direct.ID, users[0])
	if err != nil || !promoted.IsHost {
		t.Fatal("SQL transfer", err)
	}
	if _, err = db.ExecContext(ctx, `DELETE FROM circle_members WHERE circle_id=$1`, direct.ID); err != nil {
		t.Fatal(err)
	}
	assertAbsent(t, store, direct.ID, DemoUser, users[0])
	exerciseSuccession(t, store, DemoUser, users[1], users[2])
	exerciseSafety(t, store, DemoUser, users[3], users[4])
	exerciseReview(t, store, users[5], users[6])
	accountCircle, err := store.Create(ctx, lifecycleDraft("delete-host-account-123"), users[7])
	if err != nil {
		t.Fatal(err)
	}
	publishForTest(t, store, accountCircle.ID)
	if err = store.SetJoined(ctx, accountCircle.ID, users[8], true); err != nil {
		t.Fatal(err)
	}
	if _, err = db.ExecContext(ctx, `DELETE FROM users WHERE id=$1::uuid`, users[7]); err != nil {
		t.Fatal("delete host account", err)
	}
	promoted, err = store.Get(ctx, accountCircle.ID, users[8])
	if err != nil || !promoted.IsHost {
		t.Fatal("account removal lost circle", err)
	}
	// Joins and host leaves share the parent row lock.
	for i := 0; i < 10; i++ {
		racing, err := store.Create(ctx, lifecycleDraft(fmt.Sprintf("postgres-leave-race-%06d", i)), DemoUser)
		if err != nil {
			t.Fatal(err)
		}
		publishForTest(t, store, racing.ID)
		wg.Add(2)
		go func() {
			defer wg.Done()
			if err := store.SetJoined(ctx, racing.ID, DemoUser, false); err != nil {
				t.Error(err)
			}
		}()
		go func() {
			defer wg.Done()
			if err := store.SetJoined(ctx, racing.ID, users[0], true); err != nil && !errors.Is(err, ErrNotFound) {
				t.Error(err)
			}
		}()
		wg.Wait()
		surviving, err := store.Get(ctx, racing.ID, users[0])
		if err == nil {
			if !surviving.IsHost || len(surviving.Attendees) != 1 {
				t.Fatal("orphan after race")
			}
			if err = store.SetJoined(ctx, racing.ID, users[0], false); err != nil {
				t.Fatal(err)
			}
		} else if !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		assertAbsent(t, store, racing.ID, DemoUser, users[0])
	}

}

func seedEligibility(t *testing.T,db *sql.DB) {
    t.Helper()
    _,err:=db.Exec(`INSERT INTO user_preferences(user_id,interests,area_name,radius_km,adult_confirmed,terms_version,birth_date,gender,latitude,longitude)
    SELECT id,ARRAY['coffee'],'Test area',5,true,'community-v1','1995-06-15'::date,'male',12.9719,77.6412 FROM users ON CONFLICT(user_id) DO NOTHING`)
    if err!=nil {t.Fatal(err)}
}
