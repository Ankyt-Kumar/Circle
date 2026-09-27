package community

import (
	"context"
	"database/sql"
	"errors"
)

// First revoke Circle access durably, then remove Firebase identity via an
// idempotent job. A failed external call cannot restore access or recreate data.
func (s *Service) DeleteAccount(ctx context.Context, user string) error {
	if s.Deleter == nil {
		return ErrUnavailable
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var uid string
	e = tx.QueryRowContext(ctx, `SELECT firebase_uid FROM users WHERE id=$1::uuid AND account_status='active' AND firebase_uid IS NOT NULL FOR UPDATE`, user).Scan(&uid)
	if errors.Is(e, sql.ErrNoRows) {
		return ErrForbidden
	}
	if e != nil {
		return e
	}
	if _, e = tx.ExecContext(ctx, `UPDATE users SET account_status='deleting',first_name='Deleted member',profile_complete=false WHERE id=$1::uuid`, user); e != nil {
		return e
	}
	// The existing membership trigger transfers hosting or deletes an empty circle.
	for _, q := range []string{`DELETE FROM circle_members WHERE user_id=$1::uuid`, `DELETE FROM feature_requests WHERE user_id=$1::uuid`,
		`DELETE FROM device_tokens WHERE user_id=$1::uuid`, `DELETE FROM notification_outbox WHERE user_id=$1::uuid`, `DELETE FROM notification_preferences WHERE user_id=$1::uuid`, `DELETE FROM chat_messages WHERE author_id=$1::uuid`, `DELETE FROM user_preferences WHERE user_id=$1::uuid`, `DELETE FROM circle_engagement WHERE user_id=$1::uuid`, `DELETE FROM user_blocks WHERE blocker_id=$1::uuid OR blocked_id=$1::uuid`, `DELETE FROM safety_reports WHERE reporter_id=$1::uuid`} {
		if _, e = tx.ExecContext(ctx, q, user); e != nil {
			return e
		}
	}
	// Clear direct identity links in retained operator evidence. Retention purge is bounded.
	if _, e = tx.ExecContext(ctx, `UPDATE safety_reports SET target_user_id=NULL,original_target_id='',snapshot='{}'::jsonb,details='Account deleted; evidence redacted' WHERE target_user_id=$1::uuid`, user); e != nil {
		return e
	}
	if _, e = tx.ExecContext(ctx, `INSERT INTO account_deletion_jobs(user_id,firebase_uid) VALUES($1::uuid,$2) ON CONFLICT DO NOTHING`, user, uid); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Service) DeleteOne(ctx context.Context) error {
	if s.Deleter == nil {
		return nil
	}
	// One transaction lease across API instances; no membership lock during Firebase.
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var id, uid string
	e = tx.QueryRowContext(ctx, `SELECT user_id::text,firebase_uid FROM account_deletion_jobs WHERE next_attempt_at<=now() ORDER BY requested_at LIMIT 1 FOR UPDATE SKIP LOCKED`).Scan(&id, &uid)
	if errors.Is(e, sql.ErrNoRows) {
		return nil
	}
	if e != nil {
		return e
	}
	if e = s.Deleter.DeleteAccount(ctx, uid); e != nil {
		_, e = tx.ExecContext(ctx, `UPDATE account_deletion_jobs SET attempts=attempts+1,next_attempt_at=now()+interval '5 minutes' WHERE user_id=$1::uuid`, id)
		if e != nil {
			return e
		}
		return tx.Commit()
	}
	if _, e = tx.ExecContext(ctx, `DELETE FROM users WHERE id=$1::uuid AND account_status='deleting'`, id); e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Service) Purge(ctx context.Context) error {
	// Application retention: chat/notifications 30 days; reports/audit 90 days.
	// Deployments must document backups and adjust the policy before public launch.
	for _, q := range []string{
		`DELETE FROM chat_messages m USING circles c WHERE m.circle_id=c.id AND c.ends_at<now()-interval '30 days'`,
		`DELETE FROM notification_outbox WHERE created_at<now()-interval '30 days'`,
		`DELETE FROM feature_requests WHERE created_at<now()-interval '1 day'`,
		`DELETE FROM device_tokens WHERE updated_at<now()-interval '60 days'`,
		`DELETE FROM safety_reports WHERE created_at<now()-interval '90 days'`,
		`DELETE FROM chat_review_audit WHERE created_at<now()-interval '90 days'`,
		`DELETE FROM ai_feature_audit WHERE created_at<now()-interval '90 days'`,
		`DELETE FROM circle_ai_audit WHERE created_at<now()-interval '90 days'`,
		`DELETE FROM safety_review_audit WHERE created_at<now()-interval '90 days'`,
	} {
		if _, e := s.DB.ExecContext(ctx, q); e != nil {
			return e
		}
	}
	return nil
}
