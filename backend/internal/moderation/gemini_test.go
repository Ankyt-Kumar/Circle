package moderation

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func envelope(value string) string {
	b, _ := json.Marshal(map[string]any{"candidates": []any{map[string]any{"finishReason": "STOP", "content": map[string]any{"parts": []map[string]string{{"text": value}}}}}})
	return string(b)
}
func TestGenerationRequiresCompletedBoundedJSON(t *testing.T) {
	for _, tc := range []struct {
		name, body   string
		code         int
		valid, retry bool
	}{
		{"draft", envelope(`{"title":"Coffee together"}`), 200, true, false},
		{"truncated", `{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{}"}]}}]}`, 200, false, false},
		{"bad-json", envelope(`{"title":`), 200, false, false},
		{"trailing", envelope(`{} trailing`), 200, false, false},
		{"blocked-by-provider", `{"promptFeedback":{"blockReason":"SAFETY"}}`, 200, false, false},
		{"tool-call", `{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"publish"}}]}}]}`, 200, false, false},
		{"oversized", envelope(strings.Repeat("x", 33000)), 200, false, false},
		{"outage", "unavailable", 503, false, true},
		{"quota", "limited", 429, false, true},
		{"bad-key", "unauthorized", 401, false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("x-goog-api-key") != "test" || r.URL.RawQuery != "" {
					t.Error("key transport")
				}
				var payload map[string]any
				if json.NewDecoder(r.Body).Decode(&payload) != nil {
					t.Error("invalid request")
				}
				if _, ok := payload["tools"]; ok {
					t.Error("tools enabled")
				}
				w.WriteHeader(tc.code)
				w.Write([]byte(tc.body))
			}))
			defer server.Close()
			client := NewGemini("test", "test-model")
			client.url = server.URL
			got, err := client.Generate(context.Background(), "Suggest a public activity", map[string]string{"prompt": "Coffee tomorrow"}, map[string]string{"type": "object"})
			if (err == nil) != tc.valid || CanRetry(err) != tc.retry {
				t.Fatalf("%s: %v", got, err)
			}
		})
	}
}
func TestConfigurationCancellationAndRedirectDoNotGenerate(t *testing.T) {
	if _, e := NewGemini("", "").Generate(context.Background(), "", nil, nil); e == nil {
		t.Fatal("missing key")
	}
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { t.Error("redirect followed") }))
	defer target.Close()
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 302) }))
	defer origin.Close()
	client := NewGemini("test", "test-model")
	client.url = origin.URL
	if _, e := client.Generate(context.Background(), "", nil, nil); e == nil {
		t.Fatal("redirect accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e := client.Generate(ctx, "", nil, nil); e == nil {
		t.Fatal("cancelled request accepted")
	}
}
