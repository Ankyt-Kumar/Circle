package httpapi

import (
	"circle.local/backend/internal/circles"
	"encoding/json"
	"io"
	"net/http"
)

func safetyBody(w http.ResponseWriter, r *http.Request, body any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(body) != nil || d.Decode(&struct{}{}) != io.EOF {
		fail(w, 400, "provide one valid JSON object")
		return false
	}
	return true
}
func safetyRoutes(mux *http.ServeMux, store circles.SafetyStore) {
	mux.HandleFunc("GET /v1/me/blocks", func(w http.ResponseWriter, r *http.Request) {
		v, e := store.Blocks(r.Context(), currentAccount(r).ID)
		if e != nil {
			storeError(w, e)
			return
		}
		write(w, 200, map[string]any{"users": v})
	})
	mux.HandleFunc("PUT /v1/me/blocks/{userId}", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			CircleID string `json:"circle_id"`
		}
		if !safetyBody(w, r, &b) {
			return
		}
		if e := store.Block(r.Context(), b.CircleID, r.PathValue("userId"), currentAccount(r).ID); e != nil {
			storeError(w, e)
			return
		}
		write(w, 200, map[string]bool{"blocked": true})
	})
	mux.HandleFunc("DELETE /v1/me/blocks/{userId}", func(w http.ResponseWriter, r *http.Request) {
		if e := store.Unblock(r.Context(), r.PathValue("userId"), currentAccount(r).ID); e != nil {
			storeError(w, e)
			return
		}
		write(w, 200, map[string]bool{"blocked": false})
	})
}
