package authn

import (
	"context"
	"errors"
	"net"
	"os"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
)

var ErrUnauthorized = errors.New("sign in required")
var ErrUnavailable = errors.New("authentication unavailable")

// Identity contains only claims from an Admin SDK verified Firebase ID token.
type Identity struct {
	AuthTime                        int64
	UID, ProjectID, Provider, Email string
	EmailVerified                   bool
}
type Verifier interface {
	Verify(context.Context, string) (Identity, error)
}
type Firebase struct {
	client    *auth.Client
	projectID string
}

// Unsigned emulator tokens must never be accepted by the Firebase cloud mode.
func ValidateMode(mode, project, emulatorHost string) error {
	switch mode {
	case "firebase":
		if project == "" || strings.HasPrefix(project, "demo-") || emulatorHost != "" {
			return errors.New("Firebase mode requires a real project and no FIREBASE_AUTH_EMULATOR_HOST")
		}
	case "emulator":
		host, port, err := net.SplitHostPort(emulatorHost)
		if err != nil || port == "" || project != "demo-circle" || (host != "127.0.0.1" && host != "localhost" && host != "::1") {
			return errors.New("emulator mode requires project demo-circle and a loopback host:port")
		}
	default:
		return errors.New("unsupported authentication mode")
	}
	return nil
}
func NewFirebase(ctx context.Context, mode, project string) (*Firebase, error) {
	if err := ValidateMode(mode, project, os.Getenv("FIREBASE_AUTH_EMULATOR_HOST")); err != nil {
		return nil, err
	}
	app, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: project})
	if err != nil {
		return nil, err
	}
	client, err := app.Auth(ctx)
	if err != nil {
		return nil, err
	}
	return &Firebase{client: client, projectID: project}, nil
}
func (f *Firebase) Verify(ctx context.Context, raw string) (Identity, error) {
	token, err := f.client.VerifyIDTokenAndCheckRevoked(ctx, raw)
	if err != nil {
		if auth.IsIDTokenInvalid(err) || auth.IsIDTokenExpired(err) || auth.IsIDTokenRevoked(err) || auth.IsUserDisabled(err) || auth.IsUserNotFound(err) {
			return Identity{}, ErrUnauthorized
		}
		return Identity{}, ErrUnavailable
	}
	return identityFromToken(token, f.projectID)
}

// This policy runs only after signature, audience, expiry and revocation checks.
func identityFromToken(token *auth.Token, project string) (Identity, error) {
	email, _ := token.Claims["email"].(string)
	verified, _ := token.Claims["email_verified"].(bool)
	provider := token.Firebase.SignInProvider
	if token.UID == "" || project == "" || (provider != "password" && provider != "google.com") || strings.TrimSpace(email) == "" {
		return Identity{}, ErrUnauthorized
	}
	return Identity{AuthTime: token.AuthTime, UID: token.UID, ProjectID: project, Provider: provider, Email: email, EmailVerified: verified}, nil
}

func (f *Firebase) DeleteAccount(ctx context.Context, uid string) error {
	e := f.client.DeleteUser(ctx, uid)
	if auth.IsUserNotFound(e) {
		return nil
	}
	return e
}
