package circles

import (
	"context"
	"database/sql"
	"errors"
)

// Acquire before any circle row lock, including multi-circle block exits.
// This pilot-scale lock serializes membership/block transactions to avoid races.
func lockMemberships(ctx context.Context, tx *sql.Tx) error {
	_, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(1128878659)`)
	return err
}

const visibleTo = `NOT EXISTS(SELECT 1 FROM circle_members bm JOIN user_blocks b ON
 (b.blocker_id=$1::uuid AND b.blocked_id=bm.user_id) OR (b.blocked_id=$1::uuid AND b.blocker_id=bm.user_id) WHERE bm.circle_id=c.id)`

func CheckSchema(ctx context.Context, db *sql.DB) error {
	var n int
	if e := db.QueryRowContext(ctx, "SELECT count(*) FROM circle_schema_updates WHERE name IN ('011_immediate_publishing','012_public_venues','013_profiles_location_eligibility') HAVING count(*)=3").Scan(&n); e != nil {
		return e
	}
	_, err := db.ExecContext(ctx, `SELECT host_id,revision,venue_id,minimum_age,maximum_age,audience FROM circles LIMIT 0; SELECT id FROM chat_messages LIMIT 0; SELECT id,address,confirmed_public FROM public_venues LIMIT 0; SELECT join_order FROM circle_members LIMIT 0;
 SELECT id FROM circle_creation_ids LIMIT 0; SELECT user_id,birth_date,gender,latitude,longitude FROM user_preferences LIMIT 0;
 SELECT id FROM safety_reports LIMIT 0; SELECT blocker_id FROM user_blocks LIMIT 0; SELECT id,creator_id,review_source FROM safety_review_audit LIMIT 0;
 SELECT state,attempts,lease_token FROM circle_review_jobs LIMIT 0; SELECT requests FROM circle_ai_daily_usage LIMIT 0;
 SELECT input_hash FROM circle_ai_audit LIMIT 0; SELECT creator_id FROM circle_creator_history LIMIT 0;`)
	if err != nil {
		return err
	}
	var valid bool
	err = db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='circle_members'::regclass AND tgname='circle_prune_after_leave'
 AND tgfoid=to_regprocedure('transfer_circle_host_after_leave()') AND NOT tgisinternal AND tgenabled<>'D')`).Scan(&valid)
	if err != nil {
		return err
	}
	if !valid {
		return errors.New("host succession trigger missing; reapply migration 006")
	}
	for _, trigger := range []struct{ table, name, function string }{
		{"circles", "circle_enqueue_review", "enqueue_circle_review()"},
		{"circles", "circle_close_review_job", "close_circle_review_job()"},
		{"safety_review_audit", "review_record_creator", "record_review_creator()"},
	} {
		err = db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid=to_regclass($1) AND tgname=$2 AND tgfoid=to_regprocedure($3) AND NOT tgisinternal AND tgenabled<>'D')`, trigger.table, trigger.name, trigger.function).Scan(&valid)
		if err != nil {
			return err
		}
		if !valid {
			return errors.New("history trigger missing; apply migrations through 009 then 011 and 012")
		}
	}
	return nil
}
func (p *Postgres) Block(ctx context.Context, circleID, target, user string) error {
	if target == "" || target == user {
		return ErrSafetyInput
	}
	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = lockMemberships(ctx, tx); err != nil {
		return err
	}
	var exists bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id::text=$1)`, target).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return ErrSafetyInput
	}
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM user_blocks WHERE blocker_id=$1::uuid AND blocked_id=$2::uuid)`, user, target).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return nil
	}
	c, err := scanCircle(tx.QueryRowContext(ctx, `SELECT `+fields+`,0::float8 FROM circles c WHERE c.id=$2
 AND (c.status='published' OR c.host_id=$1::uuid OR (c.status IN ('pending_review','cancelled','archived')
 AND EXISTS(SELECT 1 FROM circle_members m WHERE m.circle_id=c.id AND m.user_id=$1::uuid)))
 AND `+visibleTo, user, circleID))
	if err != nil {
		return err
	}
	if !contains(c, target) {
		return ErrSafetyInput
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO user_blocks(blocker_id,blocked_id) VALUES($1::uuid,$2::uuid) ON CONFLICT DO NOTHING`, user, target); err != nil {
		return err
	}
	rows, err := tx.QueryContext(ctx, `SELECT c.id FROM circles c WHERE EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id AND user_id=$1::uuid)
 AND EXISTS(SELECT 1 FROM circle_members WHERE circle_id=c.id AND user_id=$2::uuid) ORDER BY c.id FOR UPDATE`, user, target)
	if err != nil {
		return err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, id := range ids {
		if _, err = tx.ExecContext(ctx, `DELETE FROM circle_members WHERE circle_id=$1 AND user_id=$2::uuid`, id, user); err != nil {
			return err
		}
	}
	return tx.Commit()
}
func (p *Postgres) Unblock(ctx context.Context, target, user string) error {
	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = lockMemberships(ctx, tx); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM user_blocks WHERE blocker_id=$1::uuid AND blocked_id::text=$2`, user, target); err != nil {
		return err
	}
	return tx.Commit()
}
func (p *Postgres) Blocks(ctx context.Context, user string) ([]Person, error) {
	rows, err := p.db.QueryContext(ctx, `SELECT u.id::text,u.first_name FROM user_blocks b JOIN users u ON u.id=b.blocked_id WHERE b.blocker_id=$1::uuid ORDER BY b.created_at,u.id`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Person{}
	for rows.Next() {
		var person Person
		if err = rows.Scan(&person.ID, &person.Name); err != nil {
			return nil, err
		}
		out = append(out, person)
	}
	return out, rows.Err()
}
