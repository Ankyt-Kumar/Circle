// Local operator catalog. Database access authorizes changes; no mobile admin key.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"flag"
	"fmt"
	_ "github.com/jackc/pgx/v5/stdlib"
	"io"
	"math"
	"os"
	"strings"
	"time"
)

type venue struct {
	ID             string  `json:"id"`
	Name           string  `json:"name"`
	Neighborhood   string  `json:"neighborhood"`
	Latitude       float64 `json:"latitude"`
	Longitude      float64 `json:"longitude"`
	PublicReviewed bool    `json:"public_reviewed"`
	Fictional      bool    `json:"fictional"`
}

func main() {
	if e := run(); e != nil {
		fmt.Fprintln(os.Stderr, "Venue command failed:", e)
		os.Exit(1)
	}
}
func run() error {
	command := flag.String("command", "list", "list, import, retire")
	file := flag.String("file", "", "JSON array of reviewed public venues")
	id := flag.String("id", "", "venue ID for retire")
	actor := flag.String("actor", "", "operator name recorded in the audit")
	flag.Parse()
	if os.Getenv("DATABASE_URL") == "" {
		return fmt.Errorf("set DATABASE_URL to your Circle database")
	}
	db, e := sql.Open("pgx", os.Getenv("DATABASE_URL"))
	if e != nil {
		return fmt.Errorf("invalid database configuration")
	}
	defer db.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if *command == "list" {
		rows, e := db.QueryContext(ctx, `SELECT id,name,neighborhood,latitude,longitude,verified_public,fictional FROM public_venues WHERE active ORDER BY neighborhood,name`)
		if e != nil {
			return fmt.Errorf("check connection and migration 009")
		}
		defer rows.Close()
		out := []venue{}
		for rows.Next() {
			var v venue
			if e = rows.Scan(&v.ID, &v.Name, &v.Neighborhood, &v.Latitude, &v.Longitude, &v.PublicReviewed, &v.Fictional); e != nil {
				return e
			}
			out = append(out, v)
		}
		if e = rows.Err(); e != nil {
			return e
		}
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(out)
	}
	if n := len(strings.TrimSpace(*actor)); n < 2 || n > 100 {
		return fmt.Errorf("supply -actor for the audit record")
	}
	var records []venue
	switch *command {
	case "import":
		f, e := os.Open(*file)
		if e != nil {
			return e
		}
		defer f.Close()
		d := json.NewDecoder(io.LimitReader(f, 128*1024))
		d.DisallowUnknownFields()
		if e = d.Decode(&records); e != nil {
			return fmt.Errorf("invalid venue JSON: %w", e)
		}
		var extra any
		if d.Decode(&extra) != io.EOF {
			return fmt.Errorf("expected one JSON array")
		}
		if len(records) == 0 || len(records) > 100 {
			return fmt.Errorf("import between 1 and 100 public venues")
		}
		seen := map[string]bool{}
		for _, v := range records {
			if !valid(v) || seen[v.ID] {
				return fmt.Errorf("invalid or duplicate venue: %s", v.ID)
			}
			seen[v.ID] = true
		}
	case "retire":
		if strings.TrimSpace(*id) == "" {
			return fmt.Errorf("supply -id from list")
		}
	default:
		return fmt.Errorf("command must be list, import or retire")
	}
	tx, e := db.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(1128878659)`); e != nil {
		return e
	}
	if *command == "retire" {
		result, e := tx.ExecContext(ctx, `UPDATE public_venues SET active=false WHERE id=$1`, *id)
		if e != nil {
			return e
		}
		n, _ := result.RowsAffected()
		if n == 0 {
			return fmt.Errorf("venue not found")
		}
		if _, e = tx.ExecContext(ctx, `INSERT INTO public_venue_audit(venue_id,actor,action) VALUES($1,$2,'retired')`, *id, *actor); e != nil {
			return e
		}
	} else {
		for _, v := range records {
			// IDs are immutable so existing circles retain the venue they selected.
			result, e := tx.ExecContext(ctx, `INSERT INTO public_venues(id,name,neighborhood,latitude,longitude,verified_public,fictional) VALUES($1,$2,$3,$4,$5,true,$6) ON CONFLICT DO NOTHING`, v.ID, v.Name, v.Neighborhood, v.Latitude, v.Longitude, v.Fictional)
			if e != nil {
				return e
			}
			n, _ := result.RowsAffected()
			if n == 0 {
				return fmt.Errorf("venue ID %s already exists; choose a new ID for a changed venue", v.ID)
			}
			if _, e = tx.ExecContext(ctx, `INSERT INTO public_venue_audit(venue_id,actor,action) VALUES($1,$2,'imported')`, v.ID, *actor); e != nil {
				return e
			}
		}
	}
	if e = tx.Commit(); e == nil {
		fmt.Println("Venue catalog updated.")
	}
	return e
}
func valid(v venue) bool {
	if len(v.ID) < 3 || len(v.ID) > 100 || !v.PublicReviewed || len(strings.TrimSpace(v.Name)) < 3 || len(v.Name) > 160 || len(strings.TrimSpace(v.Neighborhood)) < 1 || len(v.Neighborhood) > 100 {
		return false
	}
	for _, r := range v.ID {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-') {
			return false
		}
	}
	return !math.IsNaN(v.Latitude) && !math.IsNaN(v.Longitude) && !math.IsInf(v.Latitude, 0) && !math.IsInf(v.Longitude, 0) && math.Abs(v.Latitude) <= 90 && math.Abs(v.Longitude) <= 180
}
