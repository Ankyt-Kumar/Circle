package circles

import "testing"

func TestEligibilityAndCustomCategories(t *testing.T) {
    for _,category:=range []string{"coffee","Book club","Photography","Chess & coffee","Women's running","日本語"} {
        if !ValidCategory(category) {t.Errorf("valid custom category rejected: %s",category)}
    }
    for _,category:=range []string{"","  "," Chess","Chess/coffee","very long category exceeding forty letters here"} {
        if ValidCategory(category) {t.Errorf("invalid category accepted: %s",category)}
    }
    for _,audience:=range []string{"male","female","everyone"} {
        if !ValidEligibility(18,100,audience) || !ValidEligibility(30,30,audience) {t.Fatal("inclusive range rejected")}
    }
    for _,v:=range []struct{min,max int;audience string}{{17,30,"everyone"},{35,30,"male"},{18,101,"female"},{18,30,"unknown"}} {
        if ValidEligibility(v.min,v.max,v.audience) {t.Fatalf("bad eligibility accepted: %+v",v)}
    }
}
