package circles

import (
    "errors"
    "strings"
    "unicode"
    "unicode/utf8"
)

var ErrEligibility = errors.New("your age or gender does not meet this circle's requirements")

func ValidCategory(value string) bool {
    if value != strings.TrimSpace(value) || utf8.RuneCountInString(value) < 1 || utf8.RuneCountInString(value) > 40 { return false }
    for _, c := range value { if !unicode.IsLetter(c) && !unicode.IsNumber(c) && !unicode.IsMark(c) && c != ' ' && c != '-' && c != '&' && c != '\'' { return false } }
    return true
}
func ValidEligibility(minimum, maximum int, audience string) bool {
    return minimum >= 18 && maximum <= 100 && minimum <= maximum && (audience == "everyone" || audience == "male" || audience == "female")
}
func normalizeEligibility(minimum, maximum *int, audience *string) {
    if *minimum == 0 { *minimum = 18 }; if *maximum == 0 { *maximum = 100 }; if *audience == "" { *audience = "everyone" }
}
const eligibleTo = `circle_member_eligible($1::uuid,c.minimum_age,c.maximum_age,c.audience,(c.starts_at AT TIME ZONE 'UTC')::date)`
