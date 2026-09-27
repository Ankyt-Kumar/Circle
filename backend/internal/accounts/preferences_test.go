package accounts

import ("testing"; "time"; "math")

func prefCoordinate(value float64) *float64 { return &value }

func validPreferences() Preferences {
	return Preferences{BirthDate:"1995-06-15",Gender:"male",Latitude:prefCoordinate(12.9719),Longitude:prefCoordinate(77.6412),Interests: []string{"coffee", "games"}, AreaName: "Indiranagar", RadiusKM: 3, AdultConfirmed: true, TermsVersion: TermsVersion}
}

func TestPreferencesValidation(t *testing.T) {
	if !validPreferences().Valid() {
		t.Fatal("valid preferences rejected")
	}
	cases := map[string]func(*Preferences){
		"no adult confirmation": func(p *Preferences) { p.AdultConfirmed = false },
		"old guidelines":        func(p *Preferences) { p.TermsVersion = "old" },
		"no interests":          func(p *Preferences) { p.Interests = nil },
		"duplicate interests":   func(p *Preferences) { p.Interests = []string{"coffee", "coffee"} },
		"unknown category":      func(p *Preferences) { p.Interests = []string{"unknown"} },
		"empty area": func(p *Preferences) { p.AreaName = " " },
        "missing birthday": func(p *Preferences) { p.BirthDate = "" },
        "missing gender": func(p *Preferences) { p.Gender = "" },
        "missing location": func(p *Preferences) { p.Latitude = nil },
        "NaN location": func(p *Preferences) { p.Longitude = prefCoordinate(math.NaN()) },
		"radius zero":           func(p *Preferences) { p.RadiusKM = 0 },
		"radius too large":      func(p *Preferences) { p.RadiusKM = 11 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			p := validPreferences()
			mutate(&p)
			if p.Valid() {
				t.Fatal("invalid preferences accepted")
			}
		})
	}
}

func TestBirthdayBoundaries(t *testing.T) {
    day:=time.Date(2026,9,19,0,0,0,0,time.UTC)
    for birth,want:=range map[string]bool{"2008-09-19":true,"2008-09-20":false,"1925-09-20":true,"1925-09-19":false,"2027-01-01":false,"2000-02-30":false} {
        if got:=ValidBirthDate(birth,day);got!=want {t.Errorf("%s: got %v want %v",birth,got,want)}
    }
}
