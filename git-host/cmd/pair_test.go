package cmd

import (
	"encoding/base64"
	"net/url"
	"testing"

	gossh "golang.org/x/crypto/ssh"
)

func TestPairURI(t *testing.T) {
	uri, err := pairURI("testdata/id_ed25519.pub", "my laptop")
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(uri)
	if err != nil {
		t.Fatal(err)
	}
	if u.Scheme != "githost" || u.Host != "pair" {
		t.Errorf("unexpected uri %s", uri)
	}
	if u.Query().Get("label") != "my laptop" {
		t.Errorf("label not round-tripped: %s", uri)
	}
	raw, err := base64.StdEncoding.DecodeString(u.Query().Get("key"))
	if err != nil {
		t.Fatalf("key is not base64: %v", err)
	}
	if _, err := gossh.ParsePublicKey(raw); err != nil {
		t.Fatalf("key is not an SSH wire key: %v", err)
	}
}
