package community

import (
    "circle.local/backend/internal/circles"
    "encoding/json"
    "errors"
    "strings"
    "testing"
    "time"
)

func TestAudienceAgeJoinAndEditEnforcedByDatabase(t *testing.T) {
    f:=setup(t)
    start:=time.Now().UTC().Add(2*time.Hour)
    birthday:=start.AddDate(-30,0,0).Format("2006-01-02")
    f.exec(t,`UPDATE user_preferences SET birth_date=$2::date,gender='female' WHERE user_id=$1::uuid`,f.a,birthday)
    d:=circles.CreateInput{RequestID:token(),Title:"Photography walk",Description:"Practice photos around a public venue.",Category:"Photography",VenueID:"twc-hsr",StartsAt:start,EndsAt:start.Add(time.Hour),Capacity:6,MinimumAge:30,MaximumAge:30,Audience:"female"}
    c,e:=f.c.Create(f.ctx,d,f.a);if e!=nil {t.Fatal("birthday boundary host",e)}
    if e=f.c.SetJoined(f.ctx,c.ID,f.b,true);!errors.Is(e,circles.ErrEligibility) {t.Fatal("male joined female circle",e)}
    f.exec(t,`UPDATE user_preferences SET birth_date=$2::date,gender='female' WHERE user_id=$1::uuid`,f.b,start.AddDate(-30,0,1).Format("2006-01-02"))
    if e=f.c.SetJoined(f.ctx,c.ID,f.b,true);!errors.Is(e,circles.ErrEligibility) {t.Fatal("under-age member joined",e)}
    f.exec(t,`UPDATE user_preferences SET birth_date=$2::date WHERE user_id=$1::uuid`,f.b,birthday)
    if e=f.c.SetJoined(f.ctx,c.ID,f.b,true);e!=nil {t.Fatal("eligible female blocked",e)}
    edit:=EventInput{Revision:1,Title:d.Title,Description:d.Description,Category:d.Category,VenueID:d.VenueID,StartsAt:d.StartsAt,EndsAt:d.EndsAt,Capacity:6,MinimumAge:31,MaximumAge:40,Audience:"female"}
    if e=f.s.ChangeEvent(f.ctx,c.ID,f.a,edit,false);!errors.Is(e,circles.ErrEligibility) {t.Fatal("edit excluded existing members",e)}
    // Existing succession and final-member deletion still hold for restricted circles.
    if e=f.c.SetJoined(f.ctx,c.ID,f.a,false);e!=nil {t.Fatal(e)}
    next,e:=f.c.Get(f.ctx,c.ID,f.b);if e!=nil || !next.IsHost {t.Fatal("host not transferred",e)}
    if e=f.c.SetJoined(f.ctx,c.ID,f.b,false);e!=nil {t.Fatal(e)}
    if _,e=f.c.Get(f.ctx,c.ID,f.b);!errors.Is(e,circles.ErrNotFound) {t.Fatal("empty circle survived",e)}
    // Everyone includes a member choosing not to disclose gender.
    f.exec(t,`UPDATE user_preferences SET gender='prefer_not_to_say' WHERE user_id=$1::uuid`,f.other)
    d.RequestID=token();d.MinimumAge=18;d.MaximumAge=100;d.Audience="everyone"
    c,e=f.c.Create(f.ctx,d,f.a);if e!=nil {t.Fatal(e)}
    if e=f.c.SetJoined(f.ctx,c.ID,f.other,true);e!=nil {t.Fatal("everyone excluded a gender",e)}
    d.RequestID=token();d.Audience="male"
    if _,e=f.c.Create(f.ctx,d,f.a);!errors.Is(e,circles.ErrEligibility) {t.Fatal("ineligible host created circle",e)}
}

func TestMapVenuePersistenceRadiusCustomCategoryAndPrivacy(t *testing.T) {
    f:=setup(t)
    f.exec(t,`UPDATE user_preferences SET latitude=12.97,longitude=77.64 WHERE user_id=$1::uuid`,f.a)
    input:=VenueInput{RequestID:token(),Name:"Test public venue",Address:"Public meeting point, Example Road, Test city",Neighborhood:"Test city",Latitude:coordinate(12.97),Longitude:coordinate(77.67),PublicConfirmed:true}
    v,e:=f.s.CreateVenue(f.ctx,f.a,input);if e!=nil {t.Fatal(e)}
    repeat,e:=f.s.CreateVenue(f.ctx,f.a,input);if e!=nil || repeat.ID!=v.ID {t.Fatal("venue retry duplicated",e)}
    conflict:=input;conflict.Name="Different place"
    if _,e=f.s.CreateVenue(f.ctx,f.a,conflict);!errors.Is(e,ErrConflict) {t.Fatal("venue ID reused",e)}
    invalid:=input;invalid.PublicConfirmed=false
    if _,e=f.s.CreateVenue(f.ctx,f.a,invalid);!errors.Is(e,ErrInvalid) {t.Fatal("unconfirmed venue accepted",e)}
    start:=time.Now().Add(time.Hour)
    makeCircle:=func(venue string) circles.Circle {
        c,e:=f.c.Create(f.ctx,circles.CreateInput{RequestID:token(),Title:"Book club outdoors",Description:"Bring a book and meet at the public venue.",Category:"Book club",VenueID:venue,Capacity:6,StartsAt:start,EndsAt:start.Add(time.Hour)},f.a)
        if e!=nil {t.Fatal(e)};return c
    }
    near:=makeCircle(v.ID)
    input.RequestID=token();input.Longitude=coordinate(77.70)
    far,e:=f.s.CreateVenue(f.ctx,f.a,input);if e!=nil {t.Fatal(e)}
    makeCircle(far.ID)
    list,e:=f.c.Search(f.ctx,circles.Query{Latitude:12.97,Longitude:77.64,RadiusKM:5,Category:"book CLUB"},f.a)
    if e!=nil || len(list)!=1 || list[0].ID!=near.ID || list[0].DistanceM<3000 || list[0].DistanceM>4000 {t.Fatalf("radius/custom filter: %+v %v",list,e)}
    c,e:=f.c.Get(f.ctx,near.ID,f.a)
    if e!=nil || c.VenueAddress!=input.Address || c.VenueLatitude==nil || c.DistanceM<3000 {t.Fatal("venue/address/distance not restored",e)}
    raw,_:=json.Marshal(c)
    for _,private:=range []string{"birth_date","gender","location_updated_at","1995-06-15"} {
        if strings.Contains(string(raw),private) {t.Fatal("private profile leaked",private)}
    }
    categories,e:=f.s.Categories(f.ctx);if e!=nil || !strings.Contains(strings.Join(categories,","),"Book club") {t.Fatal("custom activity missing",e)}
}
