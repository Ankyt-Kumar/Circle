package httpapi

import (
	"circle.local/backend/internal/circles"
	"circle.local/backend/internal/community"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"time"
)

func featureBody(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if d.Decode(v) != nil || d.Decode(&struct{}{}) != io.EOF {
		fail(w, 400, "provide one valid JSON object")
		return false
	}
	return true
}
func featureError(w http.ResponseWriter, e error) {
	switch {
	case errors.Is(e, community.ErrForbidden):
		fail(w, 423, e.Error())
	case errors.Is(e, community.ErrInvalid):
		fail(w, 400, e.Error())
	case errors.Is(e, community.ErrConflict):
		fail(w, 409, e.Error())
	case errors.Is(e, community.ErrLimit):
		fail(w, 429, e.Error())
	case errors.Is(e, community.ErrUnavailable):
		fail(w, 503, e.Error())
	default:
		storeError(w, e)
	}
}
func communityRoutes(mux *http.ServeMux, s *community.Service, store circles.Store) {
	mux.HandleFunc("GET /v1/features", func(w http.ResponseWriter, r *http.Request) {
		write(w, 200, map[string]bool{"ai_suggestions": s.AI != nil && s.DailyLimit > 0})
	})
	mux.HandleFunc("POST /v1/venues",func(w http.ResponseWriter,r *http.Request) {
        if !requireOnboarding(w,r) { return }
        var input community.VenueInput
        if !featureBody(w,r,&input) { return }
        v,e:=s.CreateVenue(r.Context(),currentAccount(r).ID,input)
        if e!=nil { featureError(w,e);return }; write(w,201,v)
    })
    mux.HandleFunc("GET /v1/categories",func(w http.ResponseWriter,r *http.Request) {
        values,e:=s.Categories(r.Context());if e!=nil { featureError(w,e);return }
        write(w,200,map[string]any{"categories":values})
    })
    mux.HandleFunc("GET /v1/venues", func(w http.ResponseWriter, r *http.Request) {
		v, e := s.Venues(r.Context(), r.URL.Query().Get("q"))
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, map[string]any{"venues": v})
	})
	mux.HandleFunc("GET /v1/circles/{id}/messages", func(w http.ResponseWriter, r *http.Request) {
		var before int64
		var e error
		if raw := r.URL.Query().Get("before"); raw != "" {
			before, e = strconv.ParseInt(raw, 10, 64)
			if e != nil || before < 0 {
				fail(w, 400, "invalid cursor")
				return
			}
		}
		v, e := s.Messages(r.Context(), r.PathValue("id"), currentAccount(r).ID, before)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, v)
	})
	mux.HandleFunc("POST /v1/circles/{id}/messages", func(w http.ResponseWriter, r *http.Request) {
		if !requireOnboarding(w, r) {
			return
		}
		var b struct {
			ClientID string `json:"client_id"`
			Body     string `json:"body"`
		}
		if !featureBody(w, r, &b) {
			return
		}
		v, e := s.Send(r.Context(), r.PathValue("id"), currentAccount(r).ID, b.ClientID, b.Body)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 201, v)
	})
	mux.HandleFunc("GET /v1/circles/{id}/conversation-guide", func(w http.ResponseWriter, r *http.Request) {
		v, e := s.ConversationGuide(r.Context(), r.PathValue("id"), currentAccount(r).ID)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, v)
	})
	mux.HandleFunc("PUT /v1/circles/{id}", func(w http.ResponseWriter, r *http.Request) {
		var b community.EventInput
		if !featureBody(w, r, &b) {
			return
		}
		if e := s.ChangeEvent(r.Context(), r.PathValue("id"), currentAccount(r).ID, b, false); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("POST /v1/circles/{id}/cancel", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Revision int `json:"revision"`
		}
		if !featureBody(w, r, &b) {
			return
		}
		if e := s.ChangeEvent(r.Context(), r.PathValue("id"), currentAccount(r).ID, community.EventInput{Revision: b.Revision}, true); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("POST /v1/circles/{id}/feedback", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Attended bool   `json:"attended"`
			Rating   string `json:"rating"`
		}
		if !featureBody(w, r, &b) {
			return
		}
		if e := s.Feedback(r.Context(), r.PathValue("id"), currentAccount(r).ID, b.Attended, b.Rating); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("GET /v1/me/stats", func(w http.ResponseWriter, r *http.Request) {
		v, e := s.Stats(r.Context(), currentAccount(r).ID)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, v)
	})
	mux.HandleFunc("GET /v1/me/notifications", func(w http.ResponseWriter, r *http.Request) {
		v, e := s.Inbox(r.Context(), currentAccount(r).ID)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, map[string]any{"notifications": v})
	})
	mux.HandleFunc("POST /v1/me/notifications/{id}/read", func(w http.ResponseWriter, r *http.Request) {
		id, e := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if e != nil {
			fail(w, 400, "invalid notification")
			return
		}
		if e = s.ReadNotification(r.Context(), currentAccount(r).ID, id); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("GET /v1/me/notification-preferences", func(w http.ResponseWriter, r *http.Request) {
		v, e := s.NotificationPrefs(r.Context(), currentAccount(r).ID)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, v)
	})
	mux.HandleFunc("PUT /v1/me/notification-preferences", func(w http.ResponseWriter, r *http.Request) {
		var b community.NotificationPreferences
		if !featureBody(w, r, &b) {
			return
		}
		if e := s.SaveNotificationPrefs(r.Context(), currentAccount(r).ID, b); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(204)
	})
	for _, method := range []string{"PUT", "DELETE"} {
		mux.HandleFunc(method+" /v1/me/device-token", func(w http.ResponseWriter, r *http.Request) {
			var b struct {
				Token string `json:"token"`
			}
			if !featureBody(w, r, &b) {
				return
			}
			if e := s.DeviceToken(r.Context(), currentAccount(r).ID, b.Token, r.Method == "DELETE"); e != nil {
				featureError(w, e)
				return
			}
			w.WriteHeader(204)
		})
	}
	mux.HandleFunc("DELETE /v1/me", func(w http.ResponseWriter, r *http.Request) {
		a := currentAccount(r)
		if a.AuthTime <= 0 || time.Now().Unix()-a.AuthTime > 300 {
			fail(w, 409, "sign out and sign in again before deleting your account")
			return
		}
		if e := s.DeleteAccount(r.Context(), a.ID); e != nil {
			featureError(w, e)
			return
		}
		w.WriteHeader(202)
	})
	mux.HandleFunc("POST /v1/ai/draft", func(w http.ResponseWriter, r *http.Request) {
		if !requireOnboarding(w, r) {
			return
		}
		var b struct {
			Prompt string `json:"prompt"`
		}
		if !featureBody(w, r, &b) {
			return
		}
		v, e := s.Draft(r.Context(), currentAccount(r).ID, b.Prompt)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, v)
	})
	mux.HandleFunc("POST /v1/circles/recommended", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Latitude  *float64 `json:"latitude"`
			Longitude *float64 `json:"longitude"`
			Radius    *float64 `json:"radius_km"`
			Category  string   `json:"category"`
		}
		if !featureBody(w, r, &b) {
			return
		}
		if b.Latitude == nil || b.Longitude == nil || b.Radius == nil {
			fail(w, 400, "coordinates required")
			return
		}
		q := circles.Query{Latitude: *b.Latitude, Longitude: *b.Longitude, RadiusKM: *b.Radius, Category: b.Category}
		if !q.Valid() {
			fail(w, 400, "invalid radius or location")
			return
		}
		if !savedSearchLocation(w,r,&q) { return };
		v, basis, e := s.Recommend(r.Context(), store, currentAccount(r).ID, q)
		if e != nil {
			featureError(w, e)
			return
		}
		write(w, 200, map[string]any{"circles": v, "basis": basis})
	})
}
