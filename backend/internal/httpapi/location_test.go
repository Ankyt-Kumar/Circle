package httpapi

import (
    "circle.local/backend/internal/circles"
    "context"
    "net/http/httptest"
    "strings"
    "testing"
    "time"
)

type locatedStore struct { circles.Store; query circles.Query }
func(s *locatedStore) Search(_ context.Context,q circles.Query,_ string)([]circles.Circle,error){s.query=q;return []circles.Circle{},nil}
func TestSearchUsesSavedPrivateLocation(t *testing.T) {
    store:=&locatedStore{Store:circles.NewMemory(time.Now())}
    users:=&testAccounts{names:map[string]string{}}
    h:=NewAuthenticated(store,&testVerifier{},users,"firebase")
    call:=func(method,path,body string)*httptest.ResponseRecorder {
        r:=httptest.NewRequest(method,path,strings.NewReader(body));r.Header.Set("Authorization","Bearer token-a")
        w:=httptest.NewRecorder();h.ServeHTTP(w,r);return w
    }
    query:=`{"latitude":0,"longitude":0,"radius_km":5,"category":"Book club"}`
    if w:=call("POST","/v1/circles/search",query);w.Code!=428 {t.Fatal("missing saved location accepted",w.Code)}
    profile:=`{"first_name":"Ankit","interests":["coffee"],"area_name":"My area","radius_km":3,"adult_confirmed":true,"terms_version":"community-v1","birth_date":"1995-06-15","gender":"male","latitude":12.97,"longitude":77.64}`
    if w:=call("PUT","/v1/me/preferences",profile);w.Code!=200 {t.Fatal(w.Body.String())}
    if w:=call("POST","/v1/circles/search",query);w.Code!=200 {t.Fatal(w.Body.String())}
    if store.query.Latitude!=12.97 || store.query.Longitude!=77.64 || store.query.RadiusKM!=5 {t.Fatal("client coordinates overrode private saved location",store.query)}
}
