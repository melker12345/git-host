package syncer

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// makeRepo creates a git repository at dir, with a commit unless empty is set.
func makeRepo(t *testing.T, dir string, empty bool) {
	t.Helper()
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	run := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", append([]string{"-C", dir}, args...)...)
		cmd.Env = append(os.Environ(), "GIT_AUTHOR_NAME=t", "GIT_AUTHOR_EMAIL=t@t",
			"GIT_COMMITTER_NAME=t", "GIT_COMMITTER_EMAIL=t@t")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}
	run("init", "-q", "-b", "main")
	if empty {
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "f.txt"), []byte("x"), 0644); err != nil {
		t.Fatal(err)
	}
	run("add", "-A")
	run("commit", "-qm", "init")
}

func TestDiscoverFindsReposAndPrefixes(t *testing.T) {
	root := t.TempDir()
	makeRepo(t, filepath.Join(root, "work", "alpha"), false)
	makeRepo(t, filepath.Join(root, "code", "beta"), false)
	// A plain directory that is not a repository must be ignored.
	if err := os.MkdirAll(filepath.Join(root, "work", "notarepo"), 0755); err != nil {
		t.Fatal(err)
	}

	cfg := DefaultConfig()
	cfg.Dirs = []DirSpec{
		{Prefix: "work", Glob: filepath.Join(root, "work", "*")},
		{Prefix: "code", Glob: filepath.Join(root, "code", "*")},
	}

	repos, err := cfg.Discover()
	if err != nil {
		t.Fatalf("Discover: %v", err)
	}
	if len(repos) != 2 {
		t.Fatalf("expected 2 repos, got %d: %+v", len(repos), repos)
	}
	want := map[string]bool{"work-alpha": true, "code-beta": true}
	for _, r := range repos {
		if !want[r.Name] {
			t.Errorf("unexpected repo name %q", r.Name)
		}
	}
}

// The prefix exists to stop two same-named repos from colliding on the host.
// In mirror mode a collision would overwrite one repo's history with another's,
// so this must be an error rather than a silent overwrite.
func TestDiscoverRejectsCollisions(t *testing.T) {
	root := t.TempDir()
	makeRepo(t, filepath.Join(root, "a", "same"), false)
	makeRepo(t, filepath.Join(root, "b", "same"), false)

	cfg := DefaultConfig()
	cfg.Dirs = []DirSpec{
		{Prefix: "p", Glob: filepath.Join(root, "a", "*")},
		{Prefix: "p", Glob: filepath.Join(root, "b", "*")},
	}
	// Two entries sharing a prefix are rejected up front.
	if err := cfg.validate(); err == nil {
		t.Fatal("expected duplicate prefixes to be rejected")
	}

	// Even with distinct config, identical resulting names must not pass.
	cfg.Dirs = []DirSpec{
		{Prefix: "x", Glob: filepath.Join(root, "a", "*")},
		{Prefix: "x", Glob: filepath.Join(root, "b", "*")},
	}
	if _, err := cfg.Discover(); err == nil {
		t.Fatal("expected a collision error, got nil")
	} else if !strings.Contains(err.Error(), "same") {
		t.Errorf("collision error should name the repo, got: %v", err)
	}
}

func TestSyncSkipsEmptyRepo(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "empty")
	makeRepo(t, dir, true)

	res := Sync(Repo{Path: dir, Name: "x-empty"}, DefaultConfig(), "127.0.0.1", false)
	if res.Status != StatusSkipped {
		t.Fatalf("expected empty repo to be skipped, got %v (%s)", res.Status, res.Reason)
	}
}

func TestPushArgsPerMode(t *testing.T) {
	mirror := strings.Join(pushArgs(ModeMirror, "URL"), " ")
	if !strings.Contains(mirror, "--mirror") {
		t.Errorf("mirror mode should use --mirror, got %q", mirror)
	}

	additive := strings.Join(pushArgs(ModeAdditive, "URL"), " ")
	if strings.Contains(additive, "--mirror") {
		t.Error("additive mode must never use --mirror: it would delete refs on the host")
	}
	// git rejects --all together with --tags, so both refspecs are explicit.
	if !strings.Contains(additive, "refs/heads/*") || !strings.Contains(additive, "refs/tags/*") {
		t.Errorf("additive mode should push branches and tags explicitly, got %q", additive)
	}
}

func TestLoadConfigRejectsBadValues(t *testing.T) {
	dir := t.TempDir()
	write := func(body string) string {
		p := filepath.Join(dir, "sync.conf")
		if err := os.WriteFile(p, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
		return p
	}

	if _, err := LoadConfig(write("mode = sideways\n[dirs]\na = /tmp/*\n")); err == nil {
		t.Error("expected an unknown mode to be rejected")
	}
	if _, err := LoadConfig(write("port = abc\n[dirs]\na = /tmp/*\n")); err == nil {
		t.Error("expected a non-numeric port to be rejected")
	}
	if _, err := LoadConfig(write("nonsense = 1\n")); err == nil {
		t.Error("expected an unknown setting to be rejected")
	}
	if _, err := LoadConfig(write("mode = mirror\n")); err == nil {
		t.Error("expected a config with no [dirs] to be rejected")
	}
}

func TestLoadConfigParsesDirs(t *testing.T) {
	p := filepath.Join(t.TempDir(), "sync.conf")
	body := "# comment\nhost = phone.local\nport = 2223\nmode = additive\n\n[dirs]\nwork = /srv/work/*\ntools = /srv/tools/*\n"
	if err := os.WriteFile(p, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(p)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Host != "phone.local" || cfg.Port != 2223 || cfg.Mode != ModeAdditive {
		t.Errorf("unexpected config: %+v", cfg)
	}
	if len(cfg.Dirs) != 2 || cfg.Dirs[0].Prefix != "work" || cfg.Dirs[1].Glob != "/srv/tools/*" {
		t.Errorf("unexpected dirs: %+v", cfg.Dirs)
	}
}

// A config that finds nothing must be able to say why — this is the difference
// between "no repositories found" and a user guessing at their glob.
func TestDiagnoseExplainsEmptyResult(t *testing.T) {
	root := t.TempDir()
	// A directory that exists but holds no repository.
	if err := os.MkdirAll(filepath.Join(root, "plain", "notarepo"), 0755); err != nil {
		t.Fatal(err)
	}
	makeRepo(t, filepath.Join(root, "real", "actual"), false)

	cfg := DefaultConfig()
	cfg.Dirs = []DirSpec{
		{Prefix: "plain", Glob: filepath.Join(root, "plain", "*")},
		{Prefix: "missing", Glob: filepath.Join(root, "nosuchdir", "*")},
		{Prefix: "real", Glob: filepath.Join(root, "real", "*")},
	}

	reports := cfg.Diagnose()
	if len(reports) != 3 {
		t.Fatalf("expected a report per entry, got %d", len(reports))
	}

	if reports[0].Matched != 1 || reports[0].Repos != 0 {
		t.Errorf("a matched non-repo should be reported as such: %+v", reports[0])
	}
	if len(reports[0].NotRepos) != 1 || reports[0].NotRepos[0] != "notarepo" {
		t.Errorf("the offending directory should be named: %+v", reports[0].NotRepos)
	}
	if reports[1].Matched != 0 {
		t.Errorf("a glob matching nothing should report zero matches: %+v", reports[1])
	}
	if reports[2].Repos != 1 {
		t.Errorf("a real repository should be counted: %+v", reports[2])
	}
}
