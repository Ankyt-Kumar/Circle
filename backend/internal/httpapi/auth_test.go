package httpapi

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/authn"
	"circle.local/backend/internal/circles"
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type testVerifier struct{ revoked bool }

func (v *testVerifier) Verify(_ context.Context, token string) (authn.Identity, error) {
	if v.revoked || (token != "token-a" && token != "token-b") {
		return authn.Identity{}, authn.ErrUnauthorized
	}
	provider := "password"
	if token == "token-b" {
		provider = "google.com"
	}
	return authn.Identity{ProjectID: "test-project", UID: strings.TrimPrefix(token, "token-"), Provider: provider, Email: token + "@example.test", EmailVerified: token == "token-b"}, nil
}

type testAccounts struct {
	names       map[string]string
	preferences map[string]accounts.Preferences
	suspended   bool
}

func (s *testAccounts) Resolve(_ context.Context, project, uid string) (accounts.Account, error) {
	if s.suspended {
		return accounts.Account{}, accounts.ErrSuspended
	}
	name := s.names[uid]
	if name == "" {
		name = "Member"
	}
	a := accounts.Account{ID: uid, FirstName: name, ProfileComplete: s.names[uid] != ""}
	if p, ok := s.preferences[uid]; ok {
		a.Preferences = &p
		a.OnboardingComplete = a.ProfileComplete && p.Valid()
	}
	return a, nil
}
func (s *testAccounts) SavePreferences(ctx context.Context, id string, input accounts.PreferencesInput) (accounts.Account, error) {
	if !accounts.ValidName(input.FirstName) || !input.Preferences.Valid() {
		return accounts.Account{}, accounts.ErrInvalidPreferences
	}
	if s.preferences == nil {
		s.preferences = make(map[string]accounts.Preferences)
	}
	s.names[id] = input.FirstName
	s.preferences[id] = input.Preferences
	return s.Resolve(ctx, "test-project", id)
}
func (s *testAccounts) UpdateName(ctx context.Context, id, name string) (accounts.Account, error) {
	if !accounts.ValidName(name) {
		return accounts.Account{}, accounts.ErrInvalidName
	}
	s.names[id] = strings.TrimSpace(name)
	return s.Resolve(ctx, "test-project", id)
}
func (s *testAccounts) SaveLocation(ctx context.Context, id string, input accounts.LocationInput) (accounts.Account, error) {
	if !input.Valid() { return accounts.Account{}, accounts.ErrInvalidPreferences }
	a, err := s.Resolve(ctx, "test-project", id)
	if err != nil { return accounts.Account{}, err }
	if !a.OnboardingComplete { return accounts.Account{}, accounts.ErrOnboardingRequired }
	p := *a.Preferences
	p.AreaName, p.Latitude, p.Longitude, p.RadiusKM = strings.TrimSpace(input.AreaName), input.Latitude, input.Longitude, input.RadiusKM
	s.preferences[id] = p
	return s.Resolve(ctx, "test-project", id)
}
func TestAuthenticatedAccountsAndIsolation(t *testing.T) {
	verifier := &testVerifier{}
	users := &testAccounts{names: map[string]string{}}
	handler := NewAuthenticated(circles.NewMemory(time.Now()), verifier, users, "firebase")
	call := func(token, method, path, body string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		// The caller cannot select another user's identity.
		r.Header.Set("X-User-ID", "a")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	for _, path := range []string{"/v1/me", "/v1/me/circles", "/v1/circles/coffee-01"} {
		if w := call("", "GET", path, ""); w.Code != 401 {
			t.Fatalf("anonymous %s: %d", path, w.Code)
		}
	}
	if w := call("", "GET", "/healthz", ""); w.Code != 200 {
		t.Fatal("health must be public")
	}
	if w := call("forged", "GET", "/v1/me", ""); w.Code != 401 {
		t.Fatal("forged token accepted")
	}
	if w := call("token-a", "PUT", "/v1/me", `{"first_name":"Ankit","id":"b"}`); w.Code != 400 {
		t.Fatal("profile identity injection accepted")
	}
	if w := call("token-a", "PUT", "/v1/me", `{"first_name":"Ankit"}`); w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	for _, token := range []string{"token-a", "token-b"} {
		w := call(token, "GET", "/v1/me", "")
		var a accounts.Account
		if err := json.Unmarshal(w.Body.Bytes(), &a); err != nil || a.Email != token+"@example.test" || a.AuthProvider == "" || a.EmailVerified != (token == "token-b") {
			t.Fatal("private verified claims missing", w.Body.String())
		}
	}
	if w := call("token-b", "PUT", "/v1/me", `{"first_name":"Maya"}`); !strings.Contains(w.Body.String(), `"auth_provider":"google.com"`) || !strings.Contains(w.Body.String(), `"email_verified":true`) {
		t.Fatal("update erased trusted claims", w.Body.String())
	}
	if w := call("token-a", "PUT", "/v1/me", `{"first_name":"Ankit","auth_provider":"google.com","email_verified":true}`); w.Code != 400 {
		t.Fatal("provider injection accepted")
	}
	if w := call("token-b", "GET", "/v1/me", ""); strings.Contains(w.Body.String(), "Ankit") {
		t.Fatal("profile crossed accounts")
	}
	for _, token := range []string{"token-a", "token-b"} {
		if w := call(token, "POST", "/v1/circles/coffee-01/join", ""); w.Code != 428 {
			t.Fatal("name alone bypassed onboarding", w.Code)
		}
		body := `{"first_name":"Ankit","interests":["coffee"],"area_name":"Indiranagar","radius_km":3,"adult_confirmed":true,"terms_version":"community-v1","birth_date":"1995-06-15","gender":"male","latitude":12.9719,"longitude":77.6412}`
		if token == "token-b" {
			body = strings.Replace(body, "Ankit", "Maya", 1)
		}
		if w := call(token, "PUT", "/v1/me/preferences", body); w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	if w := call("token-a", "POST", "/v1/circles/coffee-01/join", ""); w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	if w := call("token-b", "GET", "/v1/me/circles", ""); !strings.Contains(w.Body.String(), `"circles":[]`) {
		t.Fatal("B inherited A's memberships")
	}
	if w := call("token-b", "POST", "/v1/circles/coffee-01/leave", ""); w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	if w := call("token-a", "GET", "/v1/me/circles", ""); !strings.Contains(w.Body.String(), "coffee-01") {
		t.Fatal("B removed A's membership")
	}
	start := time.Now().UTC().Add(time.Hour)
	draft := circles.CreateInput{RequestID: "same-request-two-accounts", Title: "Coffee with company", Description: "A sample public meetup.", Category: "coffee", VenueID: "koramangala-cafe", StartsAt: start, EndsAt: start.Add(time.Hour), Capacity: 6}
	body, _ := json.Marshal(draft)
	a, b := call("token-a", "POST", "/v1/circles", string(body)), call("token-b", "POST", "/v1/circles", string(body))
	if a.Code != 201 || b.Code != 201 || a.Header().Get("Location") == b.Header().Get("Location") {
		t.Fatal("creation request IDs must be scoped to each account")
	}
	w := call("token-a", "GET", "/v1/circles/coffee-01", "")
	for _, private := range []string{"@example.test", "email_verified", "auth_provider", "firebase_uid", "phone_verified"} {
		if strings.Contains(w.Body.String(), private) {
			t.Fatal("private account fields leaked to attendees")
		}
	}
	verifier.revoked = true
	if w := call("token-a", "GET", "/v1/me", ""); w.Code != 401 {
		t.Fatal("revoked token accepted")
	}
	verifier.revoked = false
	users.suspended = true
	if w := call("token-a", "GET", "/v1/me", ""); w.Code != 403 {
		t.Fatal("suspended account accepted")
	}
	users.suspended = false
	r := httptest.NewRequest("GET", "/v1/me", nil)
	r.Header.Add("Authorization", "Bearer token-a")
	r.Header.Add("Authorization", "Bearer token-b")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("ambiguous authorization accepted")
	}
}
