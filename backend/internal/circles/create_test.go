package circles

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestCreateTextLimitsCountCharactersInsteadOfUtf8Bytes(t *testing.T) {
	start := time.Now().Add(time.Hour)
	draft := CreateInput{RequestID:"unicode-draft-12345",Title:strings.Repeat("क",50),Description:strings.Repeat("क",400),
		Category:"coffee",VenueID:"indiranagar-cafe",StartsAt:start,EndsAt:start.Add(time.Hour),Capacity:4}
	if _, err := draft.circle(DemoUser); err != nil { t.Fatal("valid non-Latin text rejected", err) }
	draft.Title = strings.Repeat("क",101)
	if _, err := draft.circle(DemoUser); err != ErrInvalid { t.Fatal("overlong title accepted", err) }
}

func TestConcurrentCreateRetries(t *testing.T) {
	m := NewMemory(time.Now())
	start := time.Now().Add(time.Hour)
	d := CreateInput{RequestID: "same-request-123456", Title: "Coffee together", Description: "A sample public meetup.", Category: "coffee", VenueID: "indiranagar-cafe", StartsAt: start, EndsAt: start.Add(time.Hour), Capacity: 4}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := m.Create(context.Background(), d, DemoUser); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	mine, err := m.Mine(context.Background(), DemoUser)
	if err != nil || len(mine) != 1 || len(mine[0].Attendees) != 1 {
		t.Fatal("concurrent retry duplicated circle or membership")
	}
}
