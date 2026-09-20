package syncer

import "testing"

func TestRestorePath(t *testing.T) {
	cfg := Config{Dirs: []DirSpec{
		{Prefix: "code", Glob: "/home/me/code/*"},
		{Prefix: "tools", Glob: "/home/me/code/tools/*"},
		{Prefix: "code-x", Glob: "/home/me/x/*"},
	}}
	cases := map[string]string{
		"code-git-host": "/home/me/code/git-host",
		"tools-alpha":   "/home/me/code/tools/alpha",
		"code-x-thing":  "/home/me/x/thing", // longest prefix wins
		"unknown-repo":  "",
	}
	for name, want := range cases {
		if got := cfg.RestorePath(name); got != want {
			t.Errorf("RestorePath(%q) = %q, want %q", name, got, want)
		}
	}
}

func TestGlobBase(t *testing.T) {
	cases := map[string]string{
		"/home/me/code/*":       "/home/me/code",
		"/home/me/work/*/repos": "/home/me/work",
		"relative/*":            "relative",
		"*":                     "",
	}
	for glob, want := range cases {
		if got := globBase(glob); got != want {
			t.Errorf("globBase(%q) = %q, want %q", glob, got, want)
		}
	}
}
