package accounts

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
	"os"
	"sync"
	"testing"
	"time"
)

func TestValidName(t *testing.T) {
	for _, name := range []string{"Ankit", " Élodie ", "अंकित", "Anne-Marie", "O’Neil"} {
		if !ValidName(name) {
			t.Errorf("rejected %q", name)
		}
	}
	for _, name := range []string{"", "  ", "---", "123", "A\nB", "@Ankit"} {
		if ValidName(name) {
			t.Errorf("accepted %q", name)
		}
	}
}
func TestPostgresAccountIdentity(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL for the account migration and persistence test")
	}
	ctx := context.Background()
	admin, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("account_test_%d", time.Now().UnixNano())
	if _, err = admin.ExecContext(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.ExecContext(ctx, "DROP SCHEMA "+schema+" CASCADE")
	cfg, err := pgx.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.RuntimeParams["search_path"] = schema + ",public"
	db := stdlib.OpenDB(*cfg)
	defer db.Close()
	if os.Getenv("PGLITE_TEST") == "true" {
		db.SetMaxOpenConns(1)
		if _, err = db.ExecContext(ctx, "SET search_path TO "+schema+",public"); err != nil {
			t.Fatal(err)
		}
	}
    // Apply the complete schema: account projections now include the private location and birthday.
    if _, err = admin.ExecContext(ctx, `CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public`); err != nil { t.Fatal(err) }
    for _, name := range []string{"001_schema.sql","002_demo_seed.sql","003_accounts.sql","004_user_preferences.sql","005_circle_lifecycle.sql","006_host_succession.sql","007_safety.sql","008_automatic_review.sql","009_community.sql","011_immediate_publishing.sql","012_public_venues.sql","013_profiles_location_eligibility.sql"} {
        raw,e:=os.ReadFile("../../db/"+name);if e!=nil {t.Fatal(e)}
        if _,e=db.ExecContext(ctx,string(raw));e!=nil {t.Fatal(name,e)}
    }
	store := NewPostgres(db)
	a, err := store.Resolve(ctx, "project-a", "uid")
	if err != nil {
		t.Fatal(err)
	}
	var versionBefore, versionAfter string
	if err = db.QueryRowContext(ctx, `SELECT xmin::text FROM users WHERE id=$1::uuid`, a.ID).Scan(&versionBefore); err != nil { t.Fatal(err) }
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			same, err := store.Resolve(ctx, "project-a", "uid")
			if err != nil || same.ID != a.ID {
				t.Errorf("unstable account: %+v %v", same, err)
			}
		}()
	}
	wg.Wait()
	if err = db.QueryRowContext(ctx, `SELECT xmin::text FROM users WHERE id=$1::uuid`, a.ID).Scan(&versionAfter); err != nil { t.Fatal(err) }
	if versionAfter != versionBefore { t.Fatal("reading an account rewrote its row") }
	b, err := store.Resolve(ctx, "project-a", "other-uid")
	if err != nil || a.ID == b.ID {
		t.Fatal("UID isolation failed", err)
	}
	c, err := store.Resolve(ctx, "project-b", "uid")
	if err != nil || a.ID == c.ID {
		t.Fatal("project isolation failed", err)
	}
	if _, err = store.UpdateName(ctx, a.ID, "Ankit"); err != nil {
		t.Fatal(err)
	}
	a, err = store.Resolve(ctx, "project-a", "uid")
	if err != nil || a.FirstName != "Ankit" || !a.ProfileComplete {
		t.Fatal("profile was not retained", err)
	}
	if a.OnboardingComplete {
		t.Fatal("name alone completed onboarding")
	}
	input := PreferencesInput{FirstName: "Ankit", Preferences: validPreferences()}
	if a, err = store.SavePreferences(ctx, a.ID, input); err != nil || !a.OnboardingComplete {
		t.Fatal("preferences save", err)
	}
	// A new repository instance must load the saved database values.
	a, err = NewPostgres(db).Resolve(ctx, "project-a", "uid")
	if err != nil || a.Preferences == nil || a.Preferences.RadiusKM != 3 || !a.OnboardingComplete {
		t.Fatal("preferences not persisted", err)
	}
	b, err = store.Resolve(ctx, "project-a", "other-uid")
	if err != nil || b.Preferences != nil || b.OnboardingComplete {
		t.Fatal("preferences crossed accounts", err)
	}
	location := LocationInput{AreaName:" New area ",Latitude:prefCoordinate(13.1),Longitude:prefCoordinate(77.8),RadiusKM:5}
	if _, err = store.SaveLocation(ctx, b.ID, location); !errors.Is(err, ErrOnboardingRequired) { t.Fatal("location bypassed onboarding", err) }
	a, err = store.SaveLocation(ctx, a.ID, location)
	if err != nil || a.Preferences == nil { t.Fatal("location save", err) }
	if a.FirstName != "Ankit" || a.Preferences.BirthDate != input.BirthDate || a.Preferences.Gender != input.Gender ||
		len(a.Preferences.Interests) != len(input.Interests) || a.Preferences.Interests[0] != input.Interests[0] ||
		a.Preferences.RadiusKM != 5 || a.Preferences.AreaName != "New area" || *a.Preferences.Latitude != 13.1 {
		t.Fatal("location update changed unrelated profile data", a)
	}
	if _, err = db.ExecContext(ctx, `UPDATE users SET account_status='suspended' WHERE id=$1::uuid`, a.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = store.Resolve(ctx, "project-a", "uid"); !errors.Is(err, ErrSuspended) {
		t.Fatal("suspension ignored", err)
	}
	if _, err = store.UpdateName(ctx, a.ID, "New"); !errors.Is(err, ErrSuspended) {
		t.Fatal("suspended account edited", err)
	}
	if _, err = store.SavePreferences(ctx, a.ID, input); !errors.Is(err, ErrSuspended) {
		t.Fatal("suspended preferences edit", err)
	}
}
