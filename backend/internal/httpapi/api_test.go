package httpapi

import (
	"bytes"
	"circle.local/backend/internal/circles"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHTTPContract(t *testing.T) {
	handler := NewDemo(circles.NewMemory(time.Now()))
	call := func(method, path, body string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"GET", "/healthz", "", 200},
		{"POST", "/v1/circles/search", `{"latitude":12.9352,"longitude":77.6245,"radius_km":5}`, 200},
		{"POST", "/v1/circles/search", `{"latitude":12,"longitude":77,"radius_km":100}`, 400},
		{"POST", "/v1/circles/search", `{"radius_km":5,"user_id":"forged"}`, 400},
		{"POST", "/v1/circles/search", `{} {}`, 400},
		{"POST", "/v1/circles/search", `{"radius_km":5}`, 400},
		{"POST", "/v1/circles/search", `{"latitude":null,"longitude":77,"radius_km":5}`, 400},
		{"POST", "/v1/circles/search", strings.Repeat("x", 5000), 400},
		{"GET", "/v1/circles/missing", "", 404},
		{"POST", "/v1/circles/run-01/join", "", 409},
		{"POST", "/v1/circles/coffee-01/join", "", 204},
		{"POST", "/v1/circles/coffee-01/join", "", 204},
		{"GET", "/v1/me/circles", "", 200},
		{"POST", "/v1/circles/coffee-01/leave", "", 204},
		{"GET", "/v1/circles/coffee-01/join", "", 405},
	} {
		w := call(tc.method, tc.path, tc.body)
		if w.Code != tc.status {
			t.Errorf("%s %s: got %d, want %d: %s", tc.method, tc.path, w.Code, tc.status, w.Body.String())
		}
	}
	w := call(http.MethodPost, "/v1/circles/search", `{"latitude":0,"longitude":0,"radius_km":5}`)
	if !bytes.Contains(w.Body.Bytes(), []byte(`"circles":[]`)) {
		t.Fatal("empty list must be an array")
	}
	w = call(http.MethodGet, "/v1/circles/coffee-01", "")
	var data map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &data); err != nil {
		t.Fatal(err)
	}
	for _, private := range []string{"latitude", "longitude", "phone", "location"} {
		if _, ok := data[private]; ok {
			t.Fatalf("private field leaked: %s", private)
		}
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing cache protection")
	}
}
