package accounts

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

var ErrSuspended = errors.New("account suspended")
var ErrInvalidName = errors.New("invalid first name")

// Private /v1/me response. Emails are never included in public circle attendees.
type Account struct {
	AuthTime           int64        `json:"-"`
	ID                 string       `json:"id"`
	FirstName          string       `json:"first_name"`
	ProfileComplete    bool         `json:"profile_complete"`
	AuthProvider       string       `json:"auth_provider"`
	Email              string       `json:"email"`
	EmailVerified      bool         `json:"email_verified"`
	OnboardingComplete bool         `json:"onboarding_complete"`
	Preferences        *Preferences `json:"preferences"`
}
type Store interface {
	Resolve(context.Context, string, string) (Account, error)
	UpdateName(context.Context, string, string) (Account, error)
	SavePreferences(context.Context, string, PreferencesInput) (Account, error)
	SaveLocation(context.Context, string, LocationInput) (Account, error)
}
type Postgres struct{ db *sql.DB }

func NewPostgres(db *sql.DB) *Postgres { return &Postgres{db: db} }

func ValidName(name string) bool {
	name = strings.TrimSpace(name)
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 60 {
		return false
	}
	hasLetter := false
	for _, r := range name {
		if unicode.IsLetter(r) {
			hasLetter = true
			continue
		}
		if !unicode.IsMark(r) && r != ' ' && r != '\'' && r != '’' && r != '-' {
			return false
		}
	}
	return hasLetter
}
func newID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
func scanAccount(row *sql.Row) (Account, error) {
	var a Account
	var status string
	var raw []byte
	if err := row.Scan(&a.ID, &a.FirstName, &a.ProfileComplete, &status, &raw); err != nil {
		return a, err
	}
	if status != "active" {
		return Account{}, ErrSuspended
	}
	var err error
	a.Preferences, err = decodePreferences(raw)
	if err != nil {
		return Account{}, err
	}
	a.OnboardingComplete = a.ProfileComplete && a.Preferences != nil && a.Preferences.Valid()
	return a, nil
}
func (s *Postgres) Resolve(ctx context.Context, project, uid string) (Account, error) {
	if project == "" || uid == "" {
		return Account{}, errors.New("missing verified identity")
	}
	// Reading an existing identity must not rewrite/lock its row on every poll.
	a, err := s.byIdentity(ctx, project, uid)
	if !errors.Is(err, sql.ErrNoRows) {
		return a, err
	}
	id, err := newID()
	if err != nil {
		return Account{}, err
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO users
 (id,first_name,firebase_project_id,firebase_uid,profile_complete)
 VALUES($1::uuid,'Member',$2,$3,false)
 ON CONFLICT(firebase_project_id,firebase_uid) DO NOTHING`, id, project, uid)
	if err != nil {
		return Account{}, err
	}
	// A concurrent first request may have created the same identity.
	return s.byIdentity(ctx, project, uid)
}

func (s *Postgres) byIdentity(ctx context.Context, project, uid string) (Account, error) {
	return scanAccount(s.db.QueryRowContext(ctx, `SELECT `+accountFields+`
 FROM users u WHERE u.firebase_project_id=$1 AND u.firebase_uid=$2`, project, uid))
}
func (s *Postgres) UpdateName(ctx context.Context, id, name string) (Account, error) {
	name = strings.TrimSpace(name)
	if !ValidName(name) {
		return Account{}, ErrInvalidName
	}
	var updated string
	err := s.db.QueryRowContext(ctx, `UPDATE users
 SET first_name=$2,profile_complete=true
 WHERE id=$1::uuid AND account_status='active' AND firebase_uid IS NOT NULL
 RETURNING id`, id, name).Scan(&updated)
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrSuspended
	}
	if err != nil {
		return Account{}, err
	}
	return s.get(ctx, updated)
}
