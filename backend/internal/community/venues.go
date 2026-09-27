package community

import (
    "circle.local/backend/internal/accounts"
    "context"
    "crypto/sha256"
    "database/sql"
    "errors"
    "fmt"
    "strings"
)

// Public-place confirmation is supplied by the host, not identity or venue verification.
type VenueInput struct {
    RequestID string `json:"request_id"`
    Name string `json:"name"`
    Address string `json:"address"`
    Neighborhood string `json:"neighborhood"`
    Latitude *float64 `json:"latitude"`
    Longitude *float64 `json:"longitude"`
    PublicConfirmed bool `json:"public_confirmed"`
}
func (v VenueInput) Valid() bool {
    return bounded(v.RequestID,16,80) && bounded(v.Name,3,100) && bounded(v.Address,8,300) &&
        bounded(v.Neighborhood,1,100) && v.PublicConfirmed && accounts.ValidCoordinates(v.Latitude,v.Longitude)
}
func (s *Service) CreateVenue(ctx context.Context,user string,input VenueInput) (Venue,error) {
    var v Venue
    input.Name=strings.TrimSpace(input.Name); input.Address=strings.TrimSpace(input.Address); input.Neighborhood=strings.TrimSpace(input.Neighborhood)
    if !input.Valid() { return v,ErrInvalid }
    id:=fmt.Sprintf("venue-%x",sha256.Sum256([]byte(user+":"+input.RequestID)))
    tx,e:=s.begin(ctx); if e!=nil { return v,e }; defer tx.Rollback()
    e=tx.QueryRowContext(ctx,`SELECT id,name,neighborhood,latitude,longitude,fictional,address FROM public_venues WHERE id=$1`,id).Scan(&v.ID,&v.Name,&v.Neighborhood,&v.Latitude,&v.Longitude,&v.Fictional,&v.Address)
    if e==nil {
        if v.Name!=input.Name || v.Address!=input.Address || v.Neighborhood!=input.Neighborhood || v.Latitude!=*input.Latitude || v.Longitude!=*input.Longitude { return v,ErrConflict }
        return v,tx.Commit()
    }
    if !errors.Is(e,sql.ErrNoRows) { return v,e }
    var recent int
    if e=tx.QueryRowContext(ctx,`SELECT count(*) FROM public_venues WHERE created_by=$1::uuid AND created_at>now()-interval '24 hours'`,user).Scan(&recent);e!=nil { return v,e }
    if recent>=20 { return v,ErrLimit }
    _,e=tx.ExecContext(ctx,`INSERT INTO public_venues(id,name,neighborhood,latitude,longitude,fictional,verified_public,active,address,confirmed_public,created_by)
    VALUES($1,$2,$3,$4,$5,false,false,true,$6,true,$7::uuid)`,id,input.Name,input.Neighborhood,*input.Latitude,*input.Longitude,input.Address,user)
    if e!=nil { return v,e }
    v=Venue{ID:id,Name:input.Name,Neighborhood:input.Neighborhood,Address:input.Address,Latitude:*input.Latitude,Longitude:*input.Longitude}
    return v,tx.Commit()
}

func (s *Service) Categories(ctx context.Context) ([]string,error) {
    rows,e:=s.DB.QueryContext(ctx,`SELECT category FROM circles WHERE status='published' AND starts_at>now() GROUP BY category ORDER BY lower(category) LIMIT 200`)
    if e!=nil { return nil,e }; defer rows.Close()
    out:=[]string{"coffee","outdoors","games","fitness"}; seen:=map[string]bool{"coffee":true,"outdoors":true,"games":true,"fitness":true}
    for rows.Next() { var c string; if e=rows.Scan(&c);e!=nil { return nil,e }; if !seen[strings.ToLower(c)] { out=append(out,c);seen[strings.ToLower(c)]=true } }
    return out,rows.Err()
}
