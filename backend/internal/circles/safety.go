package circles

import (
	"context"
	"errors"
	"sort"
)

var ErrSafetyInput = errors.New("choose a valid member to block")
var ErrBlocked = errors.New("this circle is unavailable because of a user block")

type SafetyStore interface {
	Block(context.Context, string, string, string) error
	Unblock(context.Context, string, string) error
	Blocks(context.Context, string) ([]Person, error)
}

func contains(c Circle, user string) bool {
	for _, p := range c.Attendees {
		if p.ID == user {
			return true
		}
	}
	return false
}
func (m *Memory) blocked(c Circle, user string) bool {
	for _, p := range c.Attendees {
		if _, ok := m.blocks[user][p.ID]; ok {
			return true
		}
		if _, ok := m.blocks[p.ID][user]; ok {
			return true
		}
	}
	return false
}
func (m *Memory) visible(c Circle, user string) bool {
	memberArchive := (c.Status == "cancelled" || c.Status == "archived" || c.Status == "pending_review") && contains(c, user)
	return len(c.Attendees) > 0 && !m.blocked(c, user) && (c.Status == "published" || c.HostID == user || memberArchive)
}

// Caller holds mu. Rejoining appends a new membership at the end.
func (m *Memory) leave(id, user string) {
	c, ok := m.data[id]
	if !ok {
		return
	}
	for i, p := range c.Attendees {
		if p.ID == user {
			c.Attendees = append(c.Attendees[:i], c.Attendees[i+1:]...)
			break
		}
	}
	if len(c.Attendees) == 0 {
		m.retire(id)
		return
	}
	if c.HostID == user || !contains(c, c.HostID) {
		c.HostID = c.Attendees[0].ID
	}
	m.data[id] = c
}
func (m *Memory) Block(_ context.Context, circleID, target, user string) error {
	if target == "" || target == user {
		return ErrSafetyInput
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.blocks[user][target]; ok {
		return nil
	}
	c, ok := m.data[circleID]
	if !ok || !m.visible(c, user) {
		return ErrNotFound
	}
	var person Person
	for _, p := range c.Attendees {
		if p.ID == target {
			person = p
		}
	}
	if person.ID == "" {
		return ErrSafetyInput
	}
	if m.blocks[user] == nil {
		m.blocks[user] = map[string]Person{}
	}
	m.blocks[user][target] = person
	for id, shared := range m.data {
		if contains(shared, user) && contains(shared, target) {
			m.leave(id, user)
		}
	}
	return nil
}
func (m *Memory) Unblock(_ context.Context, target, user string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.blocks[user], target)
	return nil
}
func (m *Memory) Blocks(_ context.Context, user string) ([]Person, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Person{}
	for _, p := range m.blocks[user] {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}
