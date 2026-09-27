// Package moderation contains the optional Gemini generation and embedding transport.
// Circle publication and chat do not use this client.
package moderation

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"time"
)

type ProviderError struct {
	Code      string
	Retryable bool
}

func (e *ProviderError) Error() string { return e.Code }
func CanRetry(err error) bool          { var e *ProviderError; return errors.As(err, &e) && e.Retryable }

const DefaultModel = "gemini-3.5-flash-lite"

type Gemini struct {
	key, model, url string
	client          *http.Client
}

func NewGemini(key, model string) *Gemini {
	if model == "" {
		model = DefaultModel
	}
	return &Gemini{key: key, model: model, url: "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", client: &http.Client{Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

// Generate returns bounded, valid JSON from one completed candidate. No tools,
// URL fetching, redirects, raw provider errors, or automatic model fallback.
func (g *Gemini) Generate(ctx context.Context, system string, input any, schema any) (json.RawMessage, error) {
	fail := func(code string, retry bool) (json.RawMessage, error) { return nil, &ProviderError{code, retry} }
	if g.key == "" || !regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$`).MatchString(g.model) {
		return fail("provider_configuration", false)
	}
	payload, e := json.Marshal(input)
	if e != nil || len(payload) > 12000 {
		return fail("invalid_input", false)
	}
	body, e := json.Marshal(map[string]any{
		"systemInstruction": map[string]any{"parts": []map[string]string{{"text": system}}},
		"contents":          []any{map[string]any{"role": "user", "parts": []map[string]string{{"text": string(payload)}}}},
		"generationConfig":  map[string]any{"responseMimeType": "application/json", "responseJsonSchema": schema, "candidateCount": 1, "maxOutputTokens": 1024},
	})
	if e != nil {
		return fail("invalid_input", false)
	}
	req, e := http.NewRequestWithContext(ctx, http.MethodPost, g.url, bytes.NewReader(body))
	if e != nil {
		return fail("provider_configuration", false)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-goog-api-key", g.key)
	res, e := g.client.Do(req)
	if e != nil {
		return fail("provider_unavailable", true)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return fail(fmt.Sprintf("provider_http_%d", res.StatusCode), res.StatusCode == 429 || res.StatusCode >= 500)
	}
	raw, e := io.ReadAll(io.LimitReader(res.Body, 32769))
	if e != nil {
		return fail("provider_unavailable", true)
	}
	if len(raw) > 32768 {
		return fail("invalid_provider_output", false)
	}
	var env struct {
		PromptFeedback struct {
			BlockReason string `json:"blockReason"`
		} `json:"promptFeedback"`
		Candidates []struct {
			FinishReason string `json:"finishReason"`
			Content      struct {
				Parts []struct {
					Text         string          `json:"text"`
					Thought      bool            `json:"thought"`
					FunctionCall json.RawMessage `json:"functionCall"`
				} `json:"parts"`
			} `json:"content"`
		} `json:"candidates"`
	}
	if json.Unmarshal(raw, &env) != nil || env.PromptFeedback.BlockReason != "" || len(env.Candidates) != 1 || env.Candidates[0].FinishReason != "STOP" {
		return fail("invalid_provider_output", false)
	}
	text := ""
	count := 0
	for _, p := range env.Candidates[0].Content.Parts {
		if len(p.FunctionCall) > 0 {
			return fail("invalid_provider_output", false)
		}
		if p.Thought {
			continue
		}
		text += p.Text
		count++
	}
	if count != 1 || !json.Valid([]byte(text)) {
		return fail("invalid_provider_output", false)
	}
	return json.RawMessage(text), nil
}
