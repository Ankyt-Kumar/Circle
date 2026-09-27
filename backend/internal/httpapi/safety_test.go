package httpapi

import (
	"circle.local/backend/internal/circles"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSafetyAuthenticationIsolationAndPrivateQueue(t *testing.T) {
	h := NewAuthenticated(circles.NewMemory(time.Now()), &testVerifier{}, &testAccounts{names: map[string]string{"a": "Ankit", "b": "Maya"}}, "firebase")
	call := func(token, method, path, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		req.Header.Set("X-User-ID", "b")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		return w
	}
	if w := call("", "GET", "/v1/me/blocks", ""); w.Code != 401 {
		t.Fatal("anonymous access")
	}
	for _, path := range []string{"/v1/reports", "/v1/circles/coffee-01/message-reports"} {
		if w := call("token-a", "POST", path, `{}`); w.Code != 404 && w.Code != 405 {
			t.Fatal("report endpoint enabled", w.Code)
		}
	}
	if w := call("token-a", "PUT", "/v1/me/blocks/coffee-01a", `{"circle_id":"coffee-01","blocker_id":"b"}`); w.Code != 400 {
		t.Fatal("blocker spoof")
	}
	if w := call("token-a", "PUT", "/v1/me/blocks/coffee-01a", `{"circle_id":"coffee-01"}`); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := call("token-a", "GET", "/v1/circles/coffee-01", ""); w.Code != 404 {
		t.Fatal("blocked detail")
	}
	if w := call("token-b", "GET", "/v1/circles/coffee-01", ""); w.Code != 200 {
		t.Fatal("wrong viewer blocked")
	}
	if w := call("token-b", "GET", "/v1/me/blocks", ""); strings.Contains(w.Body.String(), "coffee-01a") {
		t.Fatal("private blocks exposed")
	}
	for _, path := range []string{"/v1/reports", "/v1/admin/review", "/v1/circles/coffee-01/approve"} {
		if w := call("token-a", "GET", path, ""); w.Code == 200 {
			t.Fatal("public review endpoint")
		}
	}
	if w := call("token-a", "DELETE", "/v1/me/blocks/coffee-01a", ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
}
