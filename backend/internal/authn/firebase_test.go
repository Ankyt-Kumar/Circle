package authn

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/auth"
	"google.golang.org/api/option"
	"os"
	"testing"
	"time"
)

func TestModeBoundary(t *testing.T) {
	for _, tc := range []struct {
		mode, project, host string
		valid               bool
	}{
		{"firebase", "circle-real", "", true}, {"firebase", "circle-real", "127.0.0.1:9099", false},
		{"firebase", "demo-circle", "", false}, {"firebase", "", "", false},
		{"emulator", "demo-circle", "127.0.0.1:9099", true}, {"emulator", "circle-real", "127.0.0.1:9099", false},
		{"emulator", "demo-circle", "remote.example:9099", false}, {"emulator", "demo-circle", "", false},
	} {
		if (ValidateMode(tc.mode, tc.project, tc.host) == nil) != tc.valid {
			t.Errorf("mode boundary: %+v", tc)
		}
	}
}
func unsignedToken(project, uid, provider string) string {
	now := time.Now().Unix()
	body, _ := json.Marshal(map[string]any{"aud": project, "iss": "https://securetoken.google.com/" + project, "sub": uid, "user_id": uid, "iat": now, "exp": now + 3600, "auth_time": now, "email": "person@example.test", "email_verified": true, "firebase": map[string]any{"sign_in_provider": provider}})
	return base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`)) + "." + base64.RawURLEncoding.EncodeToString(body) + "."
}
func TestCloudVerifierRejectsUnsignedToken(t *testing.T) {
	t.Setenv("FIREBASE_AUTH_EMULATOR_HOST", "")
	app, err := firebase.NewApp(context.Background(), &firebase.Config{ProjectID: "circle-real"}, option.WithoutAuthentication())
	if err != nil {
		t.Fatal(err)
	}
	client, err := app.Auth(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	verifier := &Firebase{client: client, projectID: "circle-real"}
	if _, err = verifier.Verify(context.Background(), unsignedToken("circle-real", "uid", "password")); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("unsigned cloud token: %v", err)
	}
}
func TestFirebaseEmulatorVerification(t *testing.T) {
	token := os.Getenv("CIRCLE_TEST_FIREBASE_TOKEN")
	if token == "" {
		t.Skip("requires an isolated running Auth emulator and test email/password token")
	}
	t.Setenv("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099")
	ctx := context.Background()
	verifier, err := NewFirebase(ctx, "emulator", "demo-circle")
	if err != nil {
		t.Fatal(err)
	}
	identity, err := verifier.Verify(ctx, token)
	if err != nil || identity.UID == "" {
		t.Fatal("password token rejected", err)
	}
	if identity.Provider != "password" || identity.Email == "" {
		t.Fatal("missing email/provider")
	}
	if google := os.Getenv("CIRCLE_TEST_GOOGLE_TOKEN"); google != "" {
		g, err := verifier.Verify(ctx, google)
		if err != nil || g.Provider != "google.com" || !g.EmailVerified || g.UID == identity.UID {
			t.Fatal("Google identity not verified separately", err)
		}
	}
	if _, err = verifier.Verify(ctx, unsignedToken("demo-circle", identity.UID, "phone")); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("legacy phone token accepted", err)
	}
	if _, err = verifier.Verify(ctx, unsignedToken("other-project", identity.UID, "password")); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("wrong audience accepted", err)
	}
	if _, err = verifier.Verify(ctx, "invalid"); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("malformed token accepted", err)
	}
	if _, err = verifier.Verify(ctx, unsignedToken("demo-circle", identity.UID, "anonymous")); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("unsupported identity accepted", err)
	}
	if _, err = verifier.client.UpdateUser(ctx, identity.UID, (&auth.UserToUpdate{}).Disabled(true)); err != nil {
		t.Fatal(err)
	}
	if _, err = verifier.Verify(ctx, token); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("disabled user accepted", err)
	}
	if _, err = verifier.client.UpdateUser(ctx, identity.UID, (&auth.UserToUpdate{}).Disabled(false)); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1100 * time.Millisecond)
	if err = verifier.client.RevokeRefreshTokens(ctx, identity.UID); err != nil {
		t.Fatal(err)
	}
	if _, err = verifier.Verify(ctx, token); !errors.Is(err, ErrUnauthorized) {
		t.Fatal("revoked token accepted", err)
	}
}

func TestProviderPolicy(t *testing.T) {
	for _, tc := range []struct {
		provider, email, uid string
		valid                bool
	}{
		{"password", "member@example.test", "a", true},
		{"google.com", "member@example.test", "b", true},
		{"phone", "member@example.test", "a", false},
		{"anonymous", "member@example.test", "a", false},
		{"custom", "member@example.test", "a", false},
		{"apple.com", "member@example.test", "a", false},
		{"password", "", "a", false}, {"google.com", " ", "a", false},
		{"password", "member@example.test", "", false},
	} {
		t.Run(tc.provider+"/"+tc.uid+"/"+tc.email, func(t *testing.T) {
			token := &auth.Token{UID: tc.uid, Claims: map[string]any{"email": tc.email, "email_verified": false}}
			token.Firebase.SignInProvider = tc.provider
			identity, err := identityFromToken(token, "circle-test")
			if (err == nil) != tc.valid {
				t.Fatalf("provider policy: %v", err)
			}
			if tc.valid && (identity.EmailVerified || identity.Email != tc.email || identity.ProjectID != "circle-test" || identity.Provider != tc.provider) {
				t.Fatal("claims invented or lost")
			}
		})
	}
}
