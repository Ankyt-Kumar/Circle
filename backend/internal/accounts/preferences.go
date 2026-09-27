package accounts

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"slices"
	"strings"
 "math"
 "time"
 "unicode/utf8"
)

// Version of the in-app community guidelines, not identity/age verification.
const TermsVersion = "community-v1"

var ErrInvalidPreferences = errors.New("complete your name, date of birth, gender, adult confirmation, guidelines, interests, location and radius")

type Preferences struct {
    BirthDate string `json:"birth_date"`
    Gender string `json:"gender"`
    Latitude *float64 `json:"latitude"`
    Longitude *float64 `json:"longitude"`
	Interests      []string `json:"interests"`
	AreaName       string   `json:"area_name"`
	RadiusKM       int      `json:"radius_km"`
	AdultConfirmed bool     `json:"adult_confirmed"`
	TermsVersion   string   `json:"terms_version"`
}

type PreferencesInput struct {
	FirstName string `json:"first_name"`
	Preferences
}

func (p Preferences) Valid() bool {
	if !p.AdultConfirmed || p.TermsVersion != TermsVersion || p.RadiusKM < 1 || p.RadiusKM > 10 ||
		utf8.RuneCountInString(strings.TrimSpace(p.AreaName)) < 1 || utf8.RuneCountInString(p.AreaName) > 120 ||
        !ValidBirthDate(p.BirthDate, time.Now()) || !slices.Contains([]string{"male","female","other","prefer_not_to_say"},p.Gender) ||
        !ValidCoordinates(p.Latitude,p.Longitude) ||
		len(p.Interests) < 1 || len(p.Interests) > 4 {
		return false
	}
	seen := make(map[string]bool)
	for _, interest := range p.Interests {
		if seen[interest] || !slices.Contains([]string{"coffee", "outdoors", "games", "fitness"}, interest) {
			return false
		}
		seen[interest] = true
	}
	return true
}

// Shared projection keeps /me and save responses consistent. All queries below
// read preferences only for the verified account's database ID.
const accountFields = `u.id,u.first_name,u.profile_complete,u.account_status,
 (SELECT jsonb_build_object('interests',p.interests,'area_name',p.area_name,
 'radius_km',p.radius_km,'adult_confirmed',p.adult_confirmed,'terms_version',p.terms_version,
 'birth_date',p.birth_date::text,'gender',p.gender,'latitude',p.latitude,'longitude',p.longitude)
 FROM user_preferences p WHERE p.user_id=u.id)`

func decodePreferences(raw []byte) (*Preferences, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil
	}
	var p Preferences
	if err := json.Unmarshal(raw, &p); err != nil {
		return nil, err
	}
	return &p, nil
}

func (s *Postgres) SavePreferences(ctx context.Context, id string, input PreferencesInput) (Account, error) {
	input.FirstName = strings.TrimSpace(input.FirstName)
	if !ValidName(input.FirstName) || !input.Preferences.Valid() {
		return Account{}, ErrInvalidPreferences
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Account{}, err
	}
	defer tx.Rollback()
    if _, err = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(1128878659)`); err != nil { return Account{}, err }
    // Update and lock the user first; resolves/suspensions use this same row.
	result, err := tx.ExecContext(ctx, `UPDATE users SET first_name=$2,profile_complete=true
      WHERE id=$1::uuid AND account_status='active' AND firebase_uid IS NOT NULL`, id, input.FirstName)
	if err != nil {
		return Account{}, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return Account{}, err
	}
	if count != 1 {
		return Account{}, ErrSuspended
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO user_preferences
      (user_id,interests,area_name,radius_km,adult_confirmed,terms_version,birth_date,gender,latitude,longitude,location_updated_at)
      VALUES($1::uuid,$2::text[],$3,$4,$5,$6,$7::date,$8,$9,$10,now())
      ON CONFLICT(user_id) DO UPDATE SET interests=EXCLUDED.interests,
      area_name=EXCLUDED.area_name,radius_km=EXCLUDED.radius_km,
      adult_confirmed=EXCLUDED.adult_confirmed,terms_version=EXCLUDED.terms_version,
      birth_date=EXCLUDED.birth_date,gender=EXCLUDED.gender,latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,
      location_updated_at=CASE WHEN user_preferences.latitude IS DISTINCT FROM EXCLUDED.latitude OR user_preferences.longitude IS DISTINCT FROM EXCLUDED.longitude THEN now() ELSE user_preferences.location_updated_at END,
      terms_accepted_at=CASE WHEN user_preferences.terms_version=EXCLUDED.terms_version
        THEN user_preferences.terms_accepted_at ELSE now() END,updated_at=now()`,
		id, input.Interests, strings.TrimSpace(input.AreaName), input.RadiusKM, input.AdultConfirmed, input.TermsVersion, input.BirthDate, input.Gender, *input.Latitude, *input.Longitude)
	if err != nil {
		return Account{}, err
	}
	a, err := scanAccount(tx.QueryRowContext(ctx, `SELECT `+accountFields+` FROM users u WHERE u.id=$1::uuid`, id))
	if err != nil {
		return Account{}, err
	}
	if err = tx.Commit(); err != nil {
		return Account{}, err
	}
	return a, nil
}

func (s *Postgres) get(ctx context.Context, id string) (Account, error) {
	a, err := scanAccount(s.db.QueryRowContext(ctx, `SELECT `+accountFields+` FROM users u WHERE u.id=$1::uuid`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrSuspended
	}
	return a, err
}

// Birthday is private; eligibility uses age on the event's UTC calendar date.
func AgeOn(birth time.Time, day time.Time) int {
    day = day.UTC(); birth = birth.UTC()
    years := day.Year()-birth.Year()
    if day.Month() < birth.Month() || day.Month() == birth.Month() && day.Day() < birth.Day() { years-- }
    return years
}
func ValidBirthDate(value string, today time.Time) bool {
    birth, err := time.Parse("2006-01-02",value)
    if err != nil { return false }; age := AgeOn(birth,today)
    return age >= 18 && age <= 100
}
func ValidCoordinates(lat, lon *float64) bool {
    return lat != nil && lon != nil && !math.IsNaN(*lat) && !math.IsNaN(*lon) &&
    !math.IsInf(*lat,0) && !math.IsInf(*lon,0) && *lat >= -90 && *lat <= 90 && *lon >= -180 && *lon <= 180
}
