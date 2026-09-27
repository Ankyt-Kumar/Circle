package httpapi

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/circles"
	"circle.local/backend/internal/community"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

func routes(store circles.Store, users accounts.Store, features ...*community.Service) http.Handler {
	mux := http.NewServeMux()
	accountRoutes(mux, users)
	for _, feature := range features {
		if feature != nil {
			communityRoutes(mux, feature, store)
		}
	}
	if safety, ok := store.(circles.SafetyStore); ok {
		safetyRoutes(mux, safety)
	}
	mux.HandleFunc("POST /v1/circles", func(w http.ResponseWriter, r *http.Request) {
		if !requireOnboarding(w, r) {
			return
		}
		var body circles.CreateInput
		r.Body = http.MaxBytesReader(w, r.Body, 8192)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil {
			fail(w, 400, "invalid request body")
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			fail(w, 400, "provide exactly one JSON object")
			return
		}
		c, err := store.Create(r.Context(), body, currentAccount(r).ID)
		if err != nil {
			storeError(w, err)
			return
		}
		w.Header().Set("Location", "/v1/circles/"+c.ID)
		write(w, http.StatusCreated, c)
	})
	mux.HandleFunc("POST /v1/circles/search", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Latitude  *float64 `json:"latitude"`
			Longitude *float64 `json:"longitude"`
			RadiusKM  *float64 `json:"radius_km"`
			Category  string   `json:"category"`
			PageSize  int      `json:"page_size"`
			Cursor    string   `json:"cursor"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 4096)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&body); err != nil {
			fail(w, 400, "invalid request body")
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			fail(w, 400, "provide exactly one JSON object")
			return
		}
		if body.Latitude == nil || body.Longitude == nil || body.RadiusKM == nil {
			fail(w, 400, "latitude, longitude, and radius_km are required")
			return
		}
		q := circles.Query{Latitude: *body.Latitude, Longitude: *body.Longitude, RadiusKM: *body.RadiusKM, Category: body.Category}
		if !q.Valid() {
			fail(w, 400, "use valid coordinates, radius 1–10 km, and a supported category")
			return
		}
		if !savedSearchLocation(w,r,&q) { return }
        q, err := circles.WithPage(q, currentAccount(r).ID, body.Cursor, body.PageSize)
		if err != nil {
			fail(w, 400, "invalid or expired page cursor; refresh the feed")
			return
		}
		list, err := store.Search(r.Context(), q, currentAccount(r).ID)
		if err != nil {
			storeError(w, err)
			return
		}
		write(w, 200, circles.MakePage(q, currentAccount(r).ID, list))
	})
	mux.HandleFunc("GET /v1/me/circles", func(w http.ResponseWriter, r *http.Request) {
		list, err := store.Mine(r.Context(), currentAccount(r).ID)
		if err != nil {
			storeError(w, err)
			return
		}
		write(w, 200, map[string]any{"circles": list})
	})
	mux.HandleFunc("GET /v1/circles/{id}", func(w http.ResponseWriter, r *http.Request) {
		c, err := store.Get(r.Context(), r.PathValue("id"), currentAccount(r).ID)
		if err != nil {
			storeError(w, err)
			return
		}
		write(w, 200, c)
	})
	for _, action := range []string{"join", "leave"} {
		mux.HandleFunc("POST /v1/circles/{id}/"+action, func(w http.ResponseWriter, r *http.Request) {
			if action == "join" && !requireOnboarding(w, r) {
				return
			}
			err := store.SetJoined(r.Context(), r.PathValue("id"), currentAccount(r).ID, action == "join")
			if err != nil {
				storeError(w, err)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		})
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		mux.ServeHTTP(w, r)
	})
}

func write(w http.ResponseWriter, code int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}
func fail(w http.ResponseWriter, code int, message string) {
	write(w, code, map[string]string{"error": message})
}
func storeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, circles.ErrEligibility):
        fail(w, 422, err.Error())
	case errors.Is(err, circles.ErrSafetyInput):
		fail(w, 400, err.Error())
	case errors.Is(err, circles.ErrBlocked):
		fail(w, 423, err.Error())
	case errors.Is(err, circles.ErrInvalid):
		fail(w, 400, err.Error())
	case errors.Is(err, circles.ErrRequestConflict):
		fail(w, 409, err.Error())
	case errors.Is(err, circles.ErrNotFound):
		fail(w, 404, err.Error())
	case errors.Is(err, circles.ErrDeleted):
		fail(w, 410, err.Error())
	case errors.Is(err, circles.ErrFull), errors.Is(err, circles.ErrClosed):
		fail(w, 409, err.Error())
	default:
		fail(w, 500, "request could not be completed")
	}
}

func requireOnboarding(w http.ResponseWriter, r *http.Request) bool {
	a := currentAccount(r)
	if a.AuthProvider == "demo" || a.OnboardingComplete {
		return true
	}
	// 403 is reserved for suspension; incomplete onboarding must not sign out.
	fail(w, http.StatusPreconditionRequired, "complete onboarding before creating or joining circles")
	return false
}

func savedSearchLocation(w http.ResponseWriter,r *http.Request,q *circles.Query) bool {
    account := currentAccount(r)
    if account.AuthProvider == "demo" { return true }
    if !account.OnboardingComplete || account.Preferences == nil || !accounts.ValidCoordinates(account.Preferences.Latitude,account.Preferences.Longitude) {
        fail(w,428,"complete your age, gender and saved location first"); return false
    }
    q.Latitude=*account.Preferences.Latitude; q.Longitude=*account.Preferences.Longitude
    return true
}
