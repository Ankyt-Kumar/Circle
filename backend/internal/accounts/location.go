package accounts

import (
	"context"
	"errors"
	"strings"
	"unicode/utf8"
)

var ErrOnboardingRequired = errors.New("complete onboarding before updating your location")

// A location change cannot overwrite the member's birthday, gender or interests.
type LocationInput struct {
	AreaName string `json:"area_name"`
	Latitude *float64 `json:"latitude"`
	Longitude *float64 `json:"longitude"`
	RadiusKM int `json:"radius_km"`
}

func (p LocationInput) Valid() bool {
	return utf8.RuneCountInString(strings.TrimSpace(p.AreaName)) >= 1 &&
		utf8.RuneCountInString(p.AreaName) <= 120 &&
		ValidCoordinates(p.Latitude, p.Longitude) && p.RadiusKM >= 1 && p.RadiusKM <= 10
}

func (s *Postgres) SaveLocation(ctx context.Context, id string, input LocationInput) (Account, error) {
	if !input.Valid() {
		return Account{}, ErrInvalidPreferences
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil { return Account{}, err }
	defer tx.Rollback()
	// Location does not affect membership eligibility, so no global membership lock is needed.
	result, err := tx.ExecContext(ctx, `UPDATE user_preferences p SET
 area_name=$2,latitude=$3,longitude=$4,radius_km=$5,updated_at=now(),
 location_updated_at=CASE WHEN p.latitude IS DISTINCT FROM $3::double precision
 OR p.longitude IS DISTINCT FROM $4::double precision THEN now() ELSE p.location_updated_at END
 FROM users u WHERE p.user_id=$1::uuid AND u.id=p.user_id AND u.account_status='active'
 AND u.profile_complete AND u.firebase_uid IS NOT NULL AND p.birth_date IS NOT NULL
 AND p.gender IS NOT NULL AND p.adult_confirmed AND p.terms_version=$6`,
		id, strings.TrimSpace(input.AreaName), *input.Latitude, *input.Longitude, input.RadiusKM, TermsVersion)
	if err != nil { return Account{}, err }
	count, err := result.RowsAffected()
	if err != nil { return Account{}, err }
	if count != 1 { return Account{}, ErrOnboardingRequired }
	a, err := scanAccount(tx.QueryRowContext(ctx, `SELECT `+accountFields+` FROM users u WHERE u.id=$1::uuid`, id))
	if err != nil { return Account{}, err }
	if !a.OnboardingComplete { return Account{}, ErrOnboardingRequired }
	if err = tx.Commit(); err != nil { return Account{}, err }
	return a, nil
}
