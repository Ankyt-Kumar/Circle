package moderation

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"math"
	"net/http"
)

const EmbeddingModel = "gemini-embedding-001"

func (g *Gemini) Embed(ctx context.Context, text string) ([]float64, error) {
	if g.key == "" || len(text) > 3000 {
		return nil, &ProviderError{"invalid_input", false}
	}
	body, _ := json.Marshal(map[string]any{"model": "models/" + EmbeddingModel, "content": map[string]any{"parts": []map[string]string{{"text": text}}}, "taskType": "SEMANTIC_SIMILARITY", "outputDimensionality": 768})
	req, e := http.NewRequestWithContext(ctx, "POST", "https://generativelanguage.googleapis.com/v1beta/models/"+EmbeddingModel+":embedContent", bytes.NewReader(body))
	if e != nil {
		return nil, &ProviderError{"provider_configuration", false}
	}
	req.Header.Set("x-goog-api-key", g.key)
	req.Header.Set("Content-Type", "application/json")
	res, e := g.client.Do(req)
	if e != nil {
		return nil, &ProviderError{"provider_unavailable", true}
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, &ProviderError{"embedding_unavailable", res.StatusCode == 429 || res.StatusCode >= 500}
	}
	raw, e := io.ReadAll(io.LimitReader(res.Body, 65537))
	if e != nil || len(raw) > 65536 {
		return nil, &ProviderError{"invalid_provider_output", false}
	}
	var out struct {
		Embedding struct {
			Values []float64 `json:"values"`
		} `json:"embedding"`
	}
	if json.Unmarshal(raw, &out) != nil || len(out.Embedding.Values) != 768 {
		return nil, &ProviderError{"invalid_provider_output", false}
	}
	norm := 0.0
	for _, v := range out.Embedding.Values {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return nil, &ProviderError{"invalid_provider_output", false}
		}
		norm += v * v
	}
	if norm == 0 {
		return nil, &ProviderError{"invalid_provider_output", false}
	}
	norm = math.Sqrt(norm)
	for i := range out.Embedding.Values {
		out.Embedding.Values[i] /= norm
	}
	return out.Embedding.Values, nil
}
