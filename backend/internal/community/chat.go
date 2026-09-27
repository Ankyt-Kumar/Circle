package community

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

type Message struct {
	ID        int64     `json:"id"`
	ClientID  string    `json:"client_id"`
	AuthorID  string    `json:"author_id"`
	Name      string    `json:"name"`
	Body      string    `json:"body"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"created_at"`
}
type Chat struct {
	Messages  []Message `json:"messages"`
	Archived  bool      `json:"archived"`
	PrivateAI bool      `json:"private_ai"`
	HasOlder  bool      `json:"has_older"`
}

func (s *Service) Messages(ctx context.Context, id, user string, before int64) (Chat, error) {
	out := Chat{Messages: []Message{}} // private_ai remains false for wire compatibility.
	tx, e := s.begin(ctx)
	if e != nil {
		return out, e
	}
	defer tx.Rollback()
	end, e := member(ctx, tx, id, user, false)
	if e != nil {
		return out, e
	}
	out.Archived = !end.After(time.Now())
	var status string
	if e = tx.QueryRowContext(ctx, `SELECT status FROM circles WHERE id=$1`, id).Scan(&status); e != nil {
		return out, e
	}
	out.Archived = out.Archived || status != "published"
	rows, e := tx.QueryContext(ctx, `SELECT m.id,m.client_id,m.author_id::text,u.first_name,m.body,m.status,m.created_at FROM chat_messages m JOIN users u ON u.id=m.author_id
 WHERE m.circle_id=$1 AND (m.status='allowed' OR m.author_id=$2::uuid) AND ($3::bigint=0 OR m.id<$3)
 ORDER BY m.id DESC LIMIT 101`, id, user, before)
	if e != nil {
		return out, e
	}
	for rows.Next() {
		var m Message
		if e = rows.Scan(&m.ID, &m.ClientID, &m.AuthorID, &m.Name, &m.Body, &m.Status, &m.CreatedAt); e != nil {
			rows.Close()
			return out, e
		}
		out.Messages = append(out.Messages, m)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return out, e
	}
	out.HasOlder = len(out.Messages) > 100
	if out.HasOlder {
		out.Messages = out.Messages[:100]
	}
	for i, j := 0, len(out.Messages)-1; i < j; i, j = i+1, j-1 {
		out.Messages[i], out.Messages[j] = out.Messages[j], out.Messages[i]
	}
	return out, tx.Commit()
}
func (s *Service) Send(ctx context.Context, id, user, clientID, body string) (Message, error) {
	var m Message
	body = strings.TrimSpace(body)
	if !bounded(clientID, 16, 80) || !bounded(body, 1, 1000) {
		return m, ErrInvalid
	}
	tx, e := s.begin(ctx)
	if e != nil {
		return m, e
	}
	defer tx.Rollback()
	if _, e = member(ctx, tx, id, user, true); e != nil {
		return m, e
	}
	e = tx.QueryRowContext(ctx, `SELECT m.id,m.client_id,m.author_id::text,u.first_name,m.body,m.status,m.created_at FROM chat_messages m JOIN users u ON u.id=m.author_id WHERE m.circle_id=$1 AND m.author_id=$2::uuid AND m.client_id=$3`, id, user, clientID).Scan(&m.ID, &m.ClientID, &m.AuthorID, &m.Name, &m.Body, &m.Status, &m.CreatedAt)
	if e == nil {
		if m.Body != body {
			return m, ErrConflict
		}
		return m, tx.Commit()
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return m, e
	}
	var count int
	if e = tx.QueryRowContext(ctx, `SELECT count(*) FROM chat_messages WHERE author_id=$1::uuid AND created_at>now()-interval '1 minute'`, user).Scan(&count); e != nil {
		return m, e
	}
	if count >= 10 {
		return m, ErrLimit
	}
	e = tx.QueryRowContext(ctx, `INSERT INTO chat_messages(circle_id,author_id,client_id,body,status,review_required,reason) VALUES($1,$2::uuid,$3,$4,'allowed',false,'posted') RETURNING id,created_at`, id, user, clientID, body).Scan(&m.ID, &m.CreatedAt)
	if e != nil {
		return m, e
	}
	m.AuthorID = user
	m.ClientID = clientID
	m.Body = body
	m.Status = "allowed"
	if e = tx.QueryRowContext(ctx, `SELECT first_name FROM users WHERE id=$1::uuid`, user).Scan(&m.Name); e != nil {
		return m, e
	}
	return m, tx.Commit()
}
