// Package whitelist stores the SSH public keys a git-host server accepts.
//
// The file is re-read on every check rather than cached, so keys added or
// removed with git-host keys take effect on a running server immediately.
package whitelist

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	gossh "golang.org/x/crypto/ssh"
)

// Entry is one authorized device. Every device has full read and write
// access to every repository; the only restriction is an optional expiry.
type Entry struct {
	PublicKey gossh.PublicKey
	// RawKey is the key as stored: base64 of the SSH wire format.
	RawKey string
	// Label names the device; a single token with no whitespace.
	Label string
	// Expiry is a Unix timestamp after which the key is refused; 0 never expires.
	Expiry int64
}

// Whitelist is the key file at a path. It holds no entries in memory.
type Whitelist struct {
	path string
}

// Load returns the whitelist stored at path. The file need not exist yet;
// it is read on each use, so Load itself never fails.
func Load(path string) (*Whitelist, error) {
	return &Whitelist{path: path}, nil
}

// entries reads every well-formed entry from the file. A missing file is an
// empty whitelist, and malformed lines are reported and skipped.
//
// File format, one entry per line:
//
//	<base64-ssh-wire-pubkey> <label> [expiry-unix-timestamp]
//
// Any further tokens on a line are ignored, so files written by older builds
// (type=, repos=, perm=) still load. The Android app reads and writes the
// same format.
func (w *Whitelist) entries() ([]Entry, error) {
	f, err := os.Open(w.path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var entries []Entry
	scanner := bufio.NewScanner(f)
	lineNum := 0
	for scanner.Scan() {
		lineNum++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		e, err := parseLine(line)
		if err != nil {
			fmt.Fprintf(os.Stderr, "whitelist:%d: skipping malformed line: %v\n", lineNum, err)
			continue
		}
		entries = append(entries, e)
	}
	return entries, scanner.Err()
}

// parseLine parses one non-comment line of the file.
func parseLine(line string) (Entry, error) {
	parts := strings.Fields(line)
	if len(parts) < 2 {
		return Entry{}, fmt.Errorf("expected at least 2 fields")
	}

	rawKey := parts[0]
	keyBytes, err := base64.StdEncoding.DecodeString(rawKey)
	if err != nil {
		return Entry{}, fmt.Errorf("bad base64: %w", err)
	}
	pubkey, err := gossh.ParsePublicKey(keyBytes)
	if err != nil {
		return Entry{}, fmt.Errorf("bad public key: %w", err)
	}

	e := Entry{PublicKey: pubkey, RawKey: rawKey, Label: parts[1]}
	if len(parts) > 2 {
		if ts, err := strconv.ParseInt(parts[2], 10, 64); err == nil {
			e.Expiry = ts
		}
	}
	return e, nil
}

// entryToLine is the inverse of parseLine.
func entryToLine(e Entry) string {
	if e.Expiry != 0 {
		return fmt.Sprintf("%s %s %d", e.RawKey, e.Label, e.Expiry)
	}
	return e.RawKey + " " + e.Label
}

// SanitizeLabel makes a label safe to store: it is one whitespace-delimited
// token in the file, so spaces would be read back as an expiry or dropped.
func SanitizeLabel(label string) string {
	label = strings.Join(strings.Fields(label), "-")
	if label == "" {
		return "unknown"
	}
	return label
}

// Authorize looks key up. It returns the matching entry, and whether the key
// may connect: false when it is unknown, expired, or the file is unreadable.
func (w *Whitelist) Authorize(key gossh.PublicKey) (Entry, bool) {
	entries, err := w.entries()
	if err != nil {
		return Entry{}, false
	}
	now := time.Now().Unix()
	incoming := key.Marshal()
	for _, e := range entries {
		if string(e.PublicKey.Marshal()) == string(incoming) {
			if e.Expiry != 0 && now > e.Expiry {
				return e, false
			}
			return e, true
		}
	}
	return Entry{}, false
}

// AddKey authorizes pubkey under label until expiry (0 for never) and returns
// its SHA256 fingerprint.
func (w *Whitelist) AddKey(pubkey gossh.PublicKey, label string, expiry int64) (string, error) {
	rawKey := base64.StdEncoding.EncodeToString(pubkey.Marshal())
	fp := gossh.FingerprintSHA256(pubkey)
	e := Entry{RawKey: rawKey, Label: SanitizeLabel(label), Expiry: expiry}
	// Re-adding the same key replaces its entry instead of stacking duplicates.
	if err := w.Remove(fp); err == nil {
		fmt.Fprintf(os.Stderr, "replaced existing entry for %s\n", fp)
	}
	line := entryToLine(e) + "\n"
	f, err := os.OpenFile(w.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	defer f.Close()
	_, err = f.WriteString(line)
	return fp, err
}

// Add authorizes the key in an authorized_keys-style public key file, as
// AddKey does.
func (w *Whitelist) Add(pubkeyFile, label string, expiry int64) (string, error) {
	data, err := os.ReadFile(pubkeyFile)
	if err != nil {
		return "", err
	}
	pubkey, _, _, _, err := gossh.ParseAuthorizedKey(data)
	if err != nil {
		return "", fmt.Errorf("invalid public key file: %w", err)
	}
	return w.AddKey(pubkey, label, expiry)
}

// List returns every entry, expired ones included.
func (w *Whitelist) List() ([]Entry, error) {
	return w.entries()
}

// Remove deletes the entry whose key has the given SHA256 fingerprint, and
// fails if there is none.
func (w *Whitelist) Remove(fingerprint string) error {
	entries, err := w.entries()
	if err != nil {
		return err
	}

	var kept []string
	found := false
	for _, e := range entries {
		if gossh.FingerprintSHA256(e.PublicKey) == fingerprint {
			found = true
			continue
		}
		kept = append(kept, entryToLine(e))
	}
	if !found {
		return fmt.Errorf("key not found: %s", fingerprint)
	}

	content := strings.Join(kept, "\n")
	if len(kept) > 0 {
		content += "\n"
	}
	return os.WriteFile(w.path, []byte(content), 0600)
}
