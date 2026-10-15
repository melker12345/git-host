package syncer

import (
	"testing"
	"time"
)

func TestStateRoundTrip(t *testing.T) {
	dir := t.TempDir()
	if got := LoadState(dir); !got.LastRun.IsZero() {
		t.Fatalf("missing state file should load as zero, got %+v", got)
	}

	now := time.Now().Truncate(time.Second)
	want := State{FirstRun: now, LastRun: now, LastSuccess: now, Device: "SM-S938B"}
	if err := want.Save(dir); err != nil {
		t.Fatal(err)
	}
	got := LoadState(dir)
	if !got.LastSuccess.Equal(want.LastSuccess) || got.Device != want.Device {
		t.Errorf("round trip: got %+v, want %+v", got, want)
	}
}

func TestStateOverdue(t *testing.T) {
	now := time.Now()
	limit := 3 * 24 * time.Hour

	cases := []struct {
		name  string
		state State
		limit time.Duration
		want  bool
	}{
		{"never ran", State{}, limit, false},
		{"recent success", State{FirstRun: now.Add(-30 * 24 * time.Hour), LastSuccess: now.Add(-time.Hour)}, limit, false},
		{"old success", State{FirstRun: now.Add(-30 * 24 * time.Hour), LastSuccess: now.Add(-4 * 24 * time.Hour)}, limit, true},
		{"never succeeded, new install", State{FirstRun: now.Add(-time.Hour)}, limit, false},
		{"never succeeded, old install", State{FirstRun: now.Add(-5 * 24 * time.Hour)}, limit, true},
		{"check disabled", State{FirstRun: now.Add(-30 * 24 * time.Hour)}, 0, false},
	}
	for _, c := range cases {
		if got := c.state.Overdue(now, c.limit); got != c.want {
			t.Errorf("%s: Overdue = %v, want %v", c.name, got, c.want)
		}
	}
}

func TestParseDuration(t *testing.T) {
	good := map[string]time.Duration{
		"0":   0,
		"3d":  72 * time.Hour,
		"12h": 12 * time.Hour,
		"90m": 90 * time.Minute,
	}
	for in, want := range good {
		got, err := parseDuration(in)
		if err != nil || got != want {
			t.Errorf("parseDuration(%q) = %v, %v; want %v", in, got, err, want)
		}
	}
	for _, in := range []string{"", "d", "-1d", "three days", "-5h"} {
		if _, err := parseDuration(in); err == nil {
			t.Errorf("parseDuration(%q) should fail", in)
		}
	}
}
