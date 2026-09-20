package whitelist

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
	"time"

	gossh "golang.org/x/crypto/ssh"
)

const testPub = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg= test"

func newList(t *testing.T) *Whitelist {
	t.Helper()
	wl, err := Load(filepath.Join(t.TempDir(), "whitelist"))
	if err != nil {
		t.Fatal(err)
	}
	return wl
}

func testKey(t *testing.T) gossh.PublicKey {
	t.Helper()
	key, _, _, _, err := gossh.ParseAuthorizedKey([]byte(testPub))
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func TestAddAuthorizeRemove(t *testing.T) {
	wl := newList(t)
	key := testKey(t)

	if _, ok := wl.Authorize(key); ok {
		t.Fatal("empty whitelist authorized a key")
	}

	fp, err := wl.AddKey(key, "Alice's laptop", 0)
	if err != nil {
		t.Fatal(err)
	}
	e, ok := wl.Authorize(key)
	if !ok {
		t.Fatal("added key not authorized")
	}
	if e.Label != "Alice's-laptop" {
		t.Errorf("label not sanitized: %q", e.Label)
	}

	if err := wl.Remove(fp); err != nil {
		t.Fatal(err)
	}
	if _, ok := wl.Authorize(key); ok {
		t.Fatal("removed key still authorized")
	}
}

func TestExpiry(t *testing.T) {
	wl := newList(t)
	key := testKey(t)

	if _, err := wl.AddKey(key, "old", time.Now().Add(-time.Hour).Unix()); err != nil {
		t.Fatal(err)
	}
	if _, ok := wl.Authorize(key); ok {
		t.Fatal("expired key authorized")
	}
	entries, _ := wl.List()
	if len(entries) != 1 {
		t.Fatalf("expired entry should be kept for audit, got %d entries", len(entries))
	}
}

// Lines written by builds that still had friend fields must load, and the
// extra tokens must not be mistaken for anything.
func TestLegacyTokensIgnored(t *testing.T) {
	wl := newList(t)
	key := testKey(t)
	raw := entryToLine(Entry{RawKey: encode(key), Label: "pc"})
	content := raw + " 0 type=friend repos=a.git,b.git perm=ro\n" +
		"# comment\n\n" +
		"garbage line\n"
	if err := os.WriteFile(wl.path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	e, ok := wl.Authorize(key)
	if !ok {
		t.Fatal("legacy line not authorized")
	}
	if e.Label != "pc" || e.Expiry != 0 {
		t.Errorf("unexpected entry %+v", e)
	}
}

func encode(key gossh.PublicKey) string {
	return base64.StdEncoding.EncodeToString(key.Marshal())
}

func TestReAddReplaces(t *testing.T) {
	wl := newList(t)
	key := testKey(t)
	if _, err := wl.AddKey(key, "first", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := wl.AddKey(key, "second", 0); err != nil {
		t.Fatal(err)
	}
	entries, _ := wl.List()
	if len(entries) != 1 || entries[0].Label != "second" {
		t.Fatalf("expected one entry labelled second, got %+v", entries)
	}
}
