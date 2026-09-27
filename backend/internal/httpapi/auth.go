package httpapi

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/authn"
	"circle.local/backend/internal/circles"
	"circle.local/backend/internal/community"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

type accountKey struct{}

func currentAccount(r *http.Request) accounts.Account {
	return r.Context().Value(accountKey{}).(accounts.Account)
}

// NewDemo is explicitly a local shared-account demonstration, without account authentication.
func NewDemo(store circles.Store, features ...*community.Service) http.Handler {
	return withAccount(routes(store, nil, features...), "demo", func(context.Context, *http.Request) (accounts.Account, error) {
		return accounts.Account{ID: circles.DemoUser, FirstName: "You (demo)", ProfileComplete: true, AuthProvider: "demo"}, nil
	})
}
func NewAuthenticated(store circles.Store, verifier authn.Verifier, users accounts.Store, mode string, features ...*community.Service) http.Handler {
	if verifier == nil || users == nil {
		panic("authentication dependencies are required")
	}
	return withAccount(routes(store, users, features...), mode, func(ctx context.Context, r *http.Request) (accounts.Account, error) {
		headers := r.Header.Values("Authorization")
		if len(headers) != 1 || len(headers[0]) > 8192 {
			return accounts.Account{}, authn.ErrUnauthorized
		}
		fields := strings.Fields(headers[0])
		if len(fields) != 2 || !strings.EqualFold(fields[0], "Bearer") {
			return accounts.Account{}, authn.ErrUnauthorized
		}
		identity, err := verifier.Verify(ctx, fields[1])
		if err != nil {
			return accounts.Account{}, err
		}
		if identity.UID == "" || identity.ProjectID == "" {
			return accounts.Account{}, authn.ErrUnauthorized
		}
		a, err := users.Resolve(ctx, identity.ProjectID, identity.UID)
		if err != nil {
			return accounts.Account{}, err
		}
		a.AuthTime = identity.AuthTime
		a.AuthProvider, a.Email, a.EmailVerified = identity.Provider, identity.Email, identity.EmailVerified
		return a, nil
	})
}
func withAccount(next http.Handler, mode string, resolve func(context.Context, *http.Request) (accounts.Account, error)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if r.Method == "GET" && r.URL.Path == "/healthz" {
			write(w, 200, map[string]string{"status": "ok", "mode": mode})
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		a, err := resolve(ctx, r)
		cancel()
		if err != nil {
			switch {
			case errors.Is(err, authn.ErrUnauthorized):
				w.Header().Set("WWW-Authenticate", "Bearer")
				fail(w, 401, "sign in required")
			case errors.Is(err, accounts.ErrSuspended):
				fail(w, 403, "account unavailable")
			default:
				fail(w, 503, "authentication unavailable; retry shortly")
			}
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), accountKey{}, a)))
	})
}
func accountRoutes(mux *http.ServeMux, users accounts.Store) {
	mux.HandleFunc("PUT /v1/me/location", func(w http.ResponseWriter, r *http.Request) {
		if users == nil || !currentAccount(r).OnboardingComplete {
			fail(w, 428, "complete onboarding first")
			return
		}
		var input accounts.LocationInput
		if !featureBody(w, r, &input) { return }
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		a, err := users.SaveLocation(ctx, currentAccount(r).ID, input)
		switch {
		case errors.Is(err, accounts.ErrInvalidPreferences):
			fail(w, 400, "choose a location and radius from 1 to 10 km")
		case errors.Is(err, accounts.ErrOnboardingRequired):
			fail(w, 428, "complete onboarding first")
		case errors.Is(err, accounts.ErrSuspended):
			fail(w, 403, "account unavailable")
		case err != nil:
			fail(w, 503, "couldn't save location; retry shortly")
		default:
			current := currentAccount(r)
			a.AuthTime = current.AuthTime
			a.AuthProvider, a.Email, a.EmailVerified = current.AuthProvider, current.Email, current.EmailVerified
			write(w, 200, a)
		}
	})
	mux.HandleFunc("GET /v1/me", func(w http.ResponseWriter, r *http.Request) { write(w, 200, currentAccount(r)) })
	mux.HandleFunc("GET /v1/me/preferences", func(w http.ResponseWriter, r *http.Request) {
		a := currentAccount(r)
		write(w, 200, map[string]any{"preferences": a.Preferences, "onboarding_complete": a.OnboardingComplete})
	})
	mux.HandleFunc("PUT /v1/me/preferences", func(w http.ResponseWriter, r *http.Request) {
		if users == nil {
			fail(w, 409, "preferences require account sign-in mode")
			return
		}
		var input accounts.PreferencesInput
		r.Body = http.MaxBytesReader(w, r.Body, 4096)
		d := json.NewDecoder(r.Body)
		d.DisallowUnknownFields()
		if err := d.Decode(&input); err != nil {
			fail(w, 400, "invalid preferences")
			return
		}
		if err := d.Decode(&struct{}{}); err != io.EOF {
			fail(w, 400, "provide one preferences object")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		a, err := users.SavePreferences(ctx, currentAccount(r).ID, input)
		switch {
		case errors.Is(err, accounts.ErrInvalidPreferences):
			fail(w, 400, err.Error())
		case errors.Is(err, accounts.ErrSuspended):
			fail(w, 403, "account unavailable")
		case err != nil:
			fail(w, 503, "couldn’t save preferences; retry shortly")
		default:
			current := currentAccount(r)
			a.AuthTime = current.AuthTime
			a.AuthProvider, a.Email, a.EmailVerified = current.AuthProvider, current.Email, current.EmailVerified
			write(w, 200, a)
		}
	})
	mux.HandleFunc("PUT /v1/me", func(w http.ResponseWriter, r *http.Request) {
		if users == nil {
			fail(w, 409, "profile editing requires account sign-in mode")
			return
		}
		var body struct {
			FirstName string `json:"first_name"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 1024)
		d := json.NewDecoder(r.Body)
		d.DisallowUnknownFields()
		if err := d.Decode(&body); err != nil {
			fail(w, 400, "invalid profile")
			return
		}
		if err := d.Decode(&struct{}{}); err != io.EOF {
			fail(w, 400, "provide one profile object")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		a, err := users.UpdateName(ctx, currentAccount(r).ID, body.FirstName)
		switch {
		case errors.Is(err, accounts.ErrInvalidName):
			fail(w, 400, "enter a first name of 1–60 letters")
		case errors.Is(err, accounts.ErrSuspended):
			fail(w, 403, "account unavailable")
		case err != nil:
			fail(w, 503, "profile unavailable; retry shortly")
		default:
			// Profile updates cannot set or erase the verified token claims.
			current := currentAccount(r)
			a.AuthTime = current.AuthTime
			a.AuthProvider, a.Email, a.EmailVerified = current.AuthProvider, current.Email, current.EmailVerified
			write(w, 200, a)
		}
	})
}
