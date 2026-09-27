package main

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/authn"
	"circle.local/backend/internal/circles"
	"circle.local/backend/internal/community"
	"circle.local/backend/internal/httpapi"
	"circle.local/backend/internal/moderation"
	"context"
	"database/sql"
	"errors"
	_ "github.com/jackc/pgx/v5/stdlib"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	key := strings.TrimSpace(os.Getenv("GEMINI_API_KEY"))
	model := strings.TrimSpace(os.Getenv("GEMINI_MODEL"))
	if model == "" {
		model = "gemini-3.5-flash-lite"
	}
	limit := 25
	if raw := os.Getenv("CIRCLE_AI_DAILY_LIMIT"); raw != "" {
		n, e := strconv.Atoi(raw)
		if e != nil || n < 1 || n > 1000 {
			log.Fatal("CIRCLE_AI_DAILY_LIMIT must be 1–1000")
		}
		limit = n
	}
	var store circles.Store = circles.NewMemory(time.Now())
	var db *sql.DB
	if dsn := os.Getenv("DATABASE_URL"); dsn != "" {
		var err error
		db, err = sql.Open("pgx", dsn)
		if err != nil {
			log.Fatal("database configuration failed")
		}
		defer db.Close()
		db.SetMaxOpenConns(10)
		db.SetMaxIdleConns(5)
		db.SetConnMaxLifetime(30 * time.Minute)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		err = db.PingContext(ctx)
		cancel()
		if err != nil {
			log.Fatal("database unavailable; check DATABASE_URL and migrations")
		}
		store = circles.NewPostgres(db)
		// Refuse the previous schema rather than fail during a user's leave/save.
		schemaCtx, schemaCancel := context.WithTimeout(context.Background(), 5*time.Second)
		err = circles.CheckSchema(schemaCtx, db)
		schemaCancel()
		if err != nil {
			log.Fatal("database upgrade required: apply missing migrations through 009, then 011, 012 and 013 in order; see docs/FINAL_SETUP.md")
		}
		log.Print("Using PostgreSQL persistence")
	} else {
		log.Print("Using in-memory demo data; joins reset on restart")
	}
	mode := os.Getenv("AUTH_MODE")
	if mode == "" {
		mode = "firebase"
	}
	var features *community.Service
	if db != nil {
		var ai community.AI
		if key != "" {
			ai = moderation.NewGemini(key, model)
		}
		features = community.New(db, ai, model, limit)
		if e := features.Check(context.Background()); e != nil {
			log.Fatal("Apply migration 009_community.sql before starting this version")
		}
	}
	var handler http.Handler
	switch mode {
	case "demo":
        if os.Getenv("DEMO_MODE") != "true" { log.Fatal("Shared in-memory fixtures require explicit AUTH_MODE=demo and DEMO_MODE=true. Use AUTH_MODE=firebase for the app.") }
		if os.Getenv("FIREBASE_AUTH_EMULATOR_HOST") != "" {
			log.Fatal("unset FIREBASE_AUTH_EMULATOR_HOST for demo mode")
		}
		handler = httpapi.NewDemo(store, features)
	case "emulator", "firebase":
		if db == nil {
			log.Fatal("account sign-in requires DATABASE_URL and db/003_accounts.sql")
		}
		verifier, err := authn.NewFirebase(context.Background(), mode, os.Getenv("FIREBASE_PROJECT_ID"))
		if err != nil {
			log.Fatal("authentication configuration failed; check AUTH_MODE, project and server credentials")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		rows, err := db.QueryContext(ctx, "SELECT firebase_project_id,firebase_uid,account_status,profile_complete FROM users LIMIT 0")
		if rows != nil {
			rows.Close()
		}
		cancel()
		if err != nil {
			log.Fatal("account migration missing; apply db/003_accounts.sql")
		}
		handler = httpapi.NewAuthenticated(store, verifier, accounts.NewPostgres(db), mode, features)
		if features != nil {
			features.Deleter = verifier
			if os.Getenv("CIRCLE_PUSH_ENABLED") == "true" {
				sender, e := community.NewFCM(context.Background(), os.Getenv("FIREBASE_PROJECT_ID"))
				if e != nil {
					log.Fatal("FCM configuration failed")
				}
				features.Push = sender
			}
		}
	default:
		log.Fatal("AUTH_MODE must be demo, emulator, or firebase")
	}
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	srv := &http.Server{Addr: addr, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if features != nil {
		go features.Run(ctx)
	}
	log.Print("Circles and messages publish immediately; member blocking is enabled")
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdown)
	}()
	log.Printf("Circle API (%s) listening on %s", mode, addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
