package httpapi

import (
	"circle.local/backend/internal/accounts"
	"circle.local/backend/internal/circles"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPreferencesAPIValidationIsolationAndOnboardingGate(t *testing.T) {
	users := &testAccounts{names: map[string]string{}}
	handler := NewAuthenticated(circles.NewMemory(time.Now()), &testVerifier{}, users, "firebase")
	call := func(token, method, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	valid := `{"first_name":"Ankit","interests":["coffee"],"area_name":"Indiranagar","radius_km":3,"adult_confirmed":true,"terms_version":"community-v1","birth_date":"1995-06-15","gender":"male","latitude":12.9719,"longitude":77.6412}`
	for _, body := range []string{
		strings.Replace(valid, `true`, `false`, 1),
		strings.Replace(valid, `"coffee"`, `"coffee","coffee"`, 1),
		strings.Replace(valid, `:3`, `:11`, 1),
		strings.Replace(valid, `community-v1`, `old-version`, 1),
		strings.TrimSuffix(valid, "}") + `,"user_id":"b"}`,
		valid + `{}`,
	} {
		if w := call("token-a", "PUT", "/v1/me/preferences", body); w.Code != 400 {
			t.Fatalf("invalid save: %d %s", w.Code, w.Body.String())
		}
	}
	if w := call("token-a", "POST", "/v1/circles", `{}`); w.Code != 428 {
		t.Fatal("create bypassed onboarding", w.Code)
	}
	if w := call("token-a", "POST", "/v1/circles/coffee-01/leave", ``); w.Code != 204 {
		t.Fatal("incomplete user cannot leave", w.Code)
	}
	w := call("token-a", "PUT", "/v1/me/preferences", valid)
	var a accounts.Account
	if err := json.Unmarshal(w.Body.Bytes(), &a); w.Code != 200 || err != nil || !a.OnboardingComplete || a.Preferences == nil || a.Email != "token-a@example.test" {
		t.Fatal("save failed", w.Body.String())
	}
	if w := call("token-a", "GET", "/v1/me/preferences", ""); !strings.Contains(w.Body.String(), `"radius_km":3`) {
		t.Fatal("save not retained")
	}
	if w := call("token-b", "GET", "/v1/me/preferences", ""); !strings.Contains(w.Body.String(), `"preferences":null`) {
		t.Fatal("preferences leaked between accounts", w.Body.String())
	}
	if w := call("token-b", "POST", "/v1/circles/coffee-01/join", ""); w.Code != 428 {
		t.Fatal("B inherited A's onboarding", w.Code)
	}
	location := `{"area_name":"New area","latitude":13.1,"longitude":77.8,"radius_km":5}`
	if w := call("token-b", "PUT", "/v1/me/location", location); w.Code != 428 {
		t.Fatal("location bypassed onboarding", w.Code)
	}
	for _, invalid := range []string{
		strings.Replace(location, `:5`, `:11`, 1),
		strings.Replace(location, `13.1`, `91.0`, 1),
		strings.TrimSuffix(location, "}") + `,"gender":"female"}`,
		strings.TrimSuffix(location, "}") + `,"user_id":"b"}`,
		location + `{}`,
	} {
		if w := call("token-a", "PUT", "/v1/me/location", invalid); w.Code != 400 {
			t.Fatal("invalid location accepted", w.Code, w.Body.String())
		}
	}
	w = call("token-a", "PUT", "/v1/me/location", location)
	if err := json.Unmarshal(w.Body.Bytes(), &a); w.Code != 200 || err != nil || a.Preferences == nil {
		t.Fatal("location save failed", w.Code, w.Body.String())
	}
	if a.Preferences.RadiusKM != 5 || a.Preferences.AreaName != "New area" ||
		a.FirstName != "Ankit" || a.Preferences.BirthDate != "1995-06-15" || a.Preferences.Gender != "male" ||
		len(a.Preferences.Interests) != 1 || a.Preferences.Interests[0] != "coffee" || a.Email != "token-a@example.test" {
		t.Fatal("location changed unrelated profile fields or dropped trusted claims", w.Body.String())
	}
	if w := call("token-b", "GET", "/v1/me/preferences", ""); !strings.Contains(w.Body.String(), `"preferences":null`) {
		t.Fatal("location crossed accounts", w.Body.String())
	}
	if w := call("token-a", "POST", "/v1/circles/coffee-01/join", ""); w.Code != 204 {
		t.Fatal("completed account cannot join", w.Code)
	}
	w = call("token-b", "GET", "/v1/circles/coffee-01", "")
	for _, field := range []string{"interests", "adult_confirmed", "terms_version", "radius_km", "host_id", "birth_date", "gender", "location_updated_at"} {
		if strings.Contains(w.Body.String(), field) {
			t.Fatal("private preference leaked", field)
		}
	}
}
