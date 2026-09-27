// Package community implements the PostgreSQL-backed member features.
package community

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"strings"
	"time"
)

var ErrForbidden = errors.New("only current members can access this circle")
var ErrConflict = errors.New("circle changed; refresh and try again")
var ErrInvalid = errors.New("check the submitted details")
var ErrUnavailable = errors.New("feature not configured; try again later")
var ErrLimit = errors.New("please try again later")

type AI interface {
	Generate(context.Context, string, any, any) (json.RawMessage, error)
}
type PushSender interface {
	Send(context.Context, string, string, string, string) error
}
type AccountDeleter interface {
	DeleteAccount(context.Context, string) error
}
type Service struct {
	DB         *sql.DB
	AI         AI
	Model      string
	DailyLimit int
	Push       PushSender
	Deleter    AccountDeleter
}

func New(db *sql.DB, ai AI, model string, limit int) *Service {
	return &Service{DB: db, AI: ai, Model: model, DailyLimit: limit}
}
func (s *Service) begin(ctx context.Context) (*sql.Tx, error) {
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return nil, e
	}
	if _, e = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(1128878659)`); e != nil {
		tx.Rollback()
		return nil, e
	}
	return tx, nil
}
func token() string {
	b := make([]byte, 16)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func hash(v string) string { b := sha256.Sum256([]byte(v)); return hex.EncodeToString(b[:]) }

// All mutation authorization is rechecked inside the membership transaction.
func member(ctx context.Context, tx *sql.Tx, id, user string, writing bool) (time.Time, error) {
	var end time.Time
	var status string
	var allowed bool
	e := tx.QueryRowContext(ctx, `SELECT c.ends_at,c.status,
 EXISTS(SELECT 1 FROM circle_members m JOIN users u ON u.id=m.user_id WHERE m.circle_id=c.id AND m.user_id=$2::uuid AND u.account_status='active')
 AND NOT EXISTS(SELECT 1 FROM circle_members m JOIN user_blocks b ON (b.blocker_id=$2::uuid AND b.blocked_id=m.user_id) OR (b.blocked_id=$2::uuid AND b.blocker_id=m.user_id) WHERE m.circle_id=c.id)
 FROM circles c WHERE c.id=$1 FOR UPDATE OF c`, id, user).Scan(&end, &status, &allowed)
	if errors.Is(e, sql.ErrNoRows) {
		return end, ErrForbidden
	}
	if e != nil {
		return end, e
	}
	if !allowed || status == "archived" || status == "rejected" {
		return end, ErrForbidden
	}
	if writing && (status != "published" || !end.After(time.Now())) {
		return end, ErrConflict
	}
	return end, nil
}

// Durable UTC budget for optional Gemini suggestions and embeddings.
func (s *Service) reserve(ctx context.Context, tx *sql.Tx) error {
	if s.AI == nil || s.DailyLimit < 1 {
		return ErrUnavailable
	}
	var used int
	e := tx.QueryRowContext(ctx, `INSERT INTO circle_ai_daily_usage(day,requests) VALUES((now() AT TIME ZONE 'UTC')::date,1)
 ON CONFLICT(day) DO UPDATE SET requests=circle_ai_daily_usage.requests+1 WHERE circle_ai_daily_usage.requests<$1 RETURNING requests`, s.DailyLimit).Scan(&used)
	if errors.Is(e, sql.ErrNoRows) {
		return ErrLimit
	}
	return e
}
func (s *Service) Reserve(ctx context.Context) error {
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if e = s.reserve(ctx, tx); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Service) audit(ctx context.Context, feature, result string) {
	_, _ = s.DB.ExecContext(ctx, `INSERT INTO ai_feature_audit(feature,model,result) VALUES($1,$2,$3)`, feature, s.Model, result)
}
func bounded(s string, min, max int) bool {
	n := len([]rune(strings.TrimSpace(s)))
	return n >= min && n <= max
}
func (s *Service) Check(ctx context.Context) error {
	var n int
	return s.DB.QueryRowContext(ctx, `SELECT count(*) FROM chat_messages WHERE false`).Scan(&n)
}
func (s *Service) Run(ctx context.Context) {
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			for name, f := range map[string]func(context.Context) error{"reminders": s.Reminders, "push": s.DeliverOne, "deletion": s.DeleteOne, "embeddings": s.EmbedOne, "retention": s.Purge} {
				c, cancel := context.WithTimeout(ctx, 20*time.Second)
				if err := f(c); err != nil && !errors.Is(err, ErrLimit) && !errors.Is(err, ErrUnavailable) && ctx.Err() == nil {
					log.Printf("Community worker %s failed; it will retry (details omitted to protect content and credentials)", name)
				}
				cancel()
			}
		}
	}
}
