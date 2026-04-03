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

type KeyType string
type Permission string

const (
	KeyTypeDevice KeyType    = "device"
	KeyTypeFriend KeyType    = "friend"
	PermRW        Permission = "rw"
	PermRO        Permission = "ro"
)

type Entry struct {
	PublicKey gossh.PublicKey
	RawKey    string
	Label     string
	Expiry    int64
	Type      KeyType
	Repos     []string
	Perm      Permission
}

type Whitelist struct {
	path string
}

func Load(path string) (*Whitelist, error) {
	return &Whitelist{path: path}, nil
}

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

	e := Entry{
		PublicKey: pubkey,
		RawKey:    rawKey,
		Label:     parts[1],
		Type:      KeyTypeDevice,
		Repos:     []string{"*"},
		Perm:      PermRW,
	}

	idx := 2
	if idx < len(parts) {
		if ts, err := strconv.ParseInt(parts[idx], 10, 64); err == nil {
			e.Expiry = ts
			idx++
		}
	}
	for ; idx < len(parts); idx++ {
		token := parts[idx]
		switch {
		case strings.HasPrefix(token, "type="):
			if strings.TrimPrefix(token, "type=") == "friend" {
				e.Type = KeyTypeFriend
			}
		case strings.HasPrefix(token, "repos="):
			e.Repos = strings.Split(strings.TrimPrefix(token, "repos="), ",")
		case strings.HasPrefix(token, "perm="):
			if strings.TrimPrefix(token, "perm=") == "ro" {
				e.Perm = PermRO
			}
		}
	}
	return e, nil
}

func entryToLine(e Entry) string {
	var sb strings.Builder
	sb.WriteString(e.RawKey)
	sb.WriteString(" ")
	sb.WriteString(e.Label)

	isDefaultType  := e.Type == KeyTypeDevice || e.Type == ""
	isDefaultRepos := len(e.Repos) == 1 && e.Repos[0] == "*"
	isDefaultPerm  := e.Perm == PermRW || e.Perm == ""
	hasExtended := !isDefaultType || !isDefaultRepos || !isDefaultPerm

	if e.Expiry != 0 || hasExtended {
		if e.Expiry != 0 {
			sb.WriteString(fmt.Sprintf(" %d", e.Expiry))
		} else {
			sb.WriteString(" 0")
		}
	}
	if hasExtended {
		t := e.Type
		if t == "" {
			t = KeyTypeDevice
		}
		sb.WriteString(fmt.Sprintf(" type=%s", t))
		if !isDefaultRepos {
			sb.WriteString(fmt.Sprintf(" repos=%s", strings.Join(e.Repos, ",")))
		}
		if !isDefaultPerm {
			sb.WriteString(" perm=ro")
		}
	}
	return sb.String()
}

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

func (w *Whitelist) AddKey(pubkey gossh.PublicKey, label string, expiry int64) (string, error) {
	rawKey := base64.StdEncoding.EncodeToString(pubkey.Marshal())
	fp := gossh.FingerprintSHA256(pubkey)
	e := Entry{RawKey: rawKey, Label: label, Expiry: expiry,
		Type: KeyTypeDevice, Repos: []string{"*"}, Perm: PermRW}
	line := entryToLine(e) + "\n"
	f, err := os.OpenFile(w.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	defer f.Close()
	_, err = f.WriteString(line)
	return fp, err
}

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

func (w *Whitelist) List() ([]Entry, error) {
	return w.entries()
}

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
