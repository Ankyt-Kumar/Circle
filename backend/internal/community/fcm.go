package community

import (
	"context"
	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
)

type FCM struct{ client *messaging.Client }

func NewFCM(ctx context.Context, project string) (*FCM, error) {
	app, e := firebase.NewApp(ctx, &firebase.Config{ProjectID: project})
	if e != nil {
		return nil, e
	}
	c, e := app.Messaging(ctx)
	return &FCM{c}, e
}
func (f *FCM) Send(ctx context.Context, token, user, circle, id string) error {
	// Data-only and generic. Android checks account ID and deduplicates the ID
	// before displaying. No private chat or account names on the lock screen.
	_, e := f.client.Send(ctx, &messaging.Message{Token: token, Data: map[string]string{"account_id": user, "circle_id": circle, "notification_id": id}, Android: &messaging.AndroidConfig{Priority: "normal"}})
	return e
}
