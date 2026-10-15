package syncer

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

// State is what sync remembers between runs, so that a backup which has
// quietly stopped happening gets noticed.
//
// The host is often legitimately unreachable (the phone app stops its server
// when idle, or the laptop is on another network), so a single unreachable
// run is not an error. What matters is how long it has been since the last
// run that actually reached the host and pushed everything.
type State struct {
	// FirstRun is when sync first ran on this machine. Until a sync has
	// succeeded, staleness is measured from here.
	FirstRun time.Time `json:"first_run"`
	// LastRun is when sync last ran, whatever the outcome.
	LastRun time.Time `json:"last_run"`
	// LastSuccess is when sync last reached the host with no failed pushes.
	LastSuccess time.Time `json:"last_success,omitzero"`
	// Device names the host the last successful sync went to.
	Device string `json:"device,omitempty"`
	// LastError describes the most recent failure; cleared on success.
	LastError string `json:"last_error,omitempty"`
}

// statePath is where State is saved.
func statePath(cfgDir string) string { return filepath.Join(cfgDir, "state.json") }

// LoadState reads the saved state. A missing or unreadable file yields the
// zero State, which reads as "never synced".
func LoadState(cfgDir string) State {
	var s State
	data, err := os.ReadFile(statePath(cfgDir))
	if err == nil {
		_ = json.Unmarshal(data, &s)
	}
	return s
}

// Save writes the state atomically, so a sync killed mid-write cannot leave
// a truncated file behind.
func (s State) Save(cfgDir string) error {
	if err := os.MkdirAll(cfgDir, 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := statePath(cfgDir) + ".tmp"
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, statePath(cfgDir))
}

// Since returns the time staleness is measured from: the last successful
// sync, or the first run if there has never been one.
func (s State) Since() time.Time {
	if s.LastSuccess.IsZero() {
		return s.FirstRun
	}
	return s.LastSuccess
}

// Overdue reports whether more than limit has passed since the last
// successful sync. A limit of zero disables the check.
func (s State) Overdue(now time.Time, limit time.Duration) bool {
	since := s.Since()
	return limit > 0 && !since.IsZero() && now.Sub(since) > limit
}
