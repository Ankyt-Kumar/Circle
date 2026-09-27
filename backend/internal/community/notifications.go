package community

import (
	"context"
	"database/sql"
	"errors"
	"strconv"
	"time"
)

type Notification struct {
	ID        int64     `json:"id"`
	CircleID  string    `json:"circle_id"`
	Kind      string    `json:"kind"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	CreatedAt time.Time `json:"created_at"`
	Read      bool      `json:"read"`
}
type NotificationPreferences struct {
	Reminders   bool `json:"reminders"`
	Changes     bool `json:"changes"`
	PushEnabled bool `json:"push_enabled"`
}

func (s *Service) Inbox(ctx context.Context, user string) ([]Notification, error) {
	rows, e := s.DB.QueryContext(ctx, `SELECT id,COALESCE(circle_id,''),kind,title,body,created_at,read_at IS NOT NULL FROM notification_outbox WHERE user_id=$1::uuid ORDER BY id DESC LIMIT 100`, user)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Notification{}
	for rows.Next() {
		var n Notification
		if e = rows.Scan(&n.ID, &n.CircleID, &n.Kind, &n.Title, &n.Body, &n.CreatedAt, &n.Read); e != nil {
			return nil, e
		}
		out = append(out, n)
	}
	return out, rows.Err()
}
func (s *Service) ReadNotification(ctx context.Context, user string, id int64) error {
	_, e := s.DB.ExecContext(ctx, `UPDATE notification_outbox SET read_at=now() WHERE user_id=$1::uuid AND id=$2`, user, id)
	return e
}
func (s *Service) NotificationPrefs(ctx context.Context, user string) (NotificationPreferences, error) {
	p := NotificationPreferences{Reminders: true, Changes: true}
	e := s.DB.QueryRowContext(ctx, `SELECT reminders,changes,push_enabled FROM notification_preferences WHERE user_id=$1::uuid`, user).Scan(&p.Reminders, &p.Changes, &p.PushEnabled)
	if errors.Is(e, sql.ErrNoRows) {
		e = nil
	}
	return p, e
}
func (s *Service) SaveNotificationPrefs(ctx context.Context, user string, p NotificationPreferences) error {
	_, e := s.DB.ExecContext(ctx, `INSERT INTO notification_preferences(user_id,reminders,changes,push_enabled) VALUES($1::uuid,$2,$3,$4) ON CONFLICT(user_id) DO UPDATE SET reminders=$2,changes=$3,push_enabled=$4`, user, p.Reminders, p.Changes, p.PushEnabled)
	return e
}
func (s *Service) DeviceToken(ctx context.Context, user, token string, remove bool) error {
	if !bounded(token, 20, 4096) {
		return ErrInvalid
	}
	if remove {
		_, e := s.DB.ExecContext(ctx, `DELETE FROM device_tokens WHERE token=$1 AND user_id=$2::uuid`, token, user)
		return e
	}
	_, e := s.DB.ExecContext(ctx, `INSERT INTO device_tokens(token,user_id) VALUES($1,$2::uuid) ON CONFLICT(token) DO UPDATE SET user_id=$2::uuid,updated_at=now()`, token, user)
	return e
}
func (s *Service) Reminders(ctx context.Context) error {
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	_, e = tx.ExecContext(ctx, `INSERT INTO notification_outbox(user_id,circle_id,kind,event_key,title,body)
 SELECT m.user_id,c.id,'reminder',c.id||':reminder:'||c.revision::text||':'||m.user_id::text,'Your circle starts soon','Open Circle to check the time and public meeting spot.'
 FROM circles c JOIN circle_members m ON m.circle_id=c.id JOIN users u ON u.id=m.user_id LEFT JOIN notification_preferences p ON p.user_id=u.id
 WHERE c.status='published' AND c.starts_at>now() AND c.starts_at<=now()+interval '1 hour' AND u.account_status='active' AND COALESCE(p.reminders,true) ON CONFLICT DO NOTHING`)
	if e != nil {
		return e
	}
	return tx.Commit()
}
func (s *Service) DeliverOne(ctx context.Context) error {
	if s.Push == nil {
		return nil
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var id int64
	var user, circle, kind, body, title string
	e = tx.QueryRowContext(ctx, `SELECT n.id,n.user_id::text,COALESCE(n.circle_id,''),n.kind,n.title,n.body FROM notification_outbox n JOIN users u ON u.id=n.user_id JOIN notification_preferences p ON p.user_id=u.id
 WHERE n.delivered_at IS NULL AND n.attempts<5 AND n.next_attempt_at<=now() AND (n.lease_until IS NULL OR n.lease_until<now()) AND p.push_enabled AND u.account_status='active'
 AND ((n.kind='reminder' AND p.reminders AND EXISTS(SELECT 1 FROM circles c JOIN circle_members m ON m.circle_id=c.id WHERE c.id=n.circle_id AND m.user_id=n.user_id AND c.status='published' AND c.starts_at>now() AND n.event_key=c.id||':reminder:'||c.revision::text||':'||n.user_id::text)) OR (n.kind IN ('changed','cancelled') AND p.changes AND EXISTS(SELECT 1 FROM circle_members m WHERE m.circle_id=n.circle_id AND m.user_id=n.user_id)))
 ORDER BY n.id LIMIT 1 FOR UPDATE OF n SKIP LOCKED`).Scan(&id, &user, &circle, &kind, &title, &body)
	if errors.Is(e, sql.ErrNoRows) {
		return nil
	}
	if e != nil {
		return e
	}
	lease := token()
	if _, e = tx.ExecContext(ctx, `UPDATE notification_outbox SET lease_until=now()+interval '60 seconds',lease_token=$2,attempts=attempts+1 WHERE id=$1`, id, lease); e != nil {
		return e
	}
	rows, e := tx.QueryContext(ctx, `SELECT token FROM device_tokens WHERE user_id=$1::uuid AND updated_at>now()-interval '60 days'`, user)
	if e != nil {
		return e
	}
	tokens := []string{}
	for rows.Next() {
		var t string
		if e = rows.Scan(&t); e != nil {
			rows.Close()
			return e
		}
		tokens = append(tokens, t)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	if e = tx.Commit(); e != nil {
		return e
	}
	success := len(tokens) > 0
	for _, t := range tokens {
		if e = s.Push.Send(ctx, t, user, circle, tokenKey(id)); e != nil {
			success = false
		}
	}
	_, e = s.DB.ExecContext(ctx, `UPDATE notification_outbox SET delivered_at=CASE WHEN $3 THEN now() ELSE NULL END,lease_until=NULL,next_attempt_at=now()+interval '5 minutes' WHERE id=$1 AND lease_token=$2`, id, lease, success)
	return e
}

func tokenKey(id int64) string { return strconv.FormatInt(id, 10) }
