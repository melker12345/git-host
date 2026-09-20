// Package syncer backs up local git repositories to a git-host server.
package syncer

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Mode decides what a sync does to refs that exist on the host but not locally.
type Mode string

const (
	// ModeMirror makes the host an exact copy: refs deleted locally are
	// deleted on the host too.
	ModeMirror Mode = "mirror"
	// ModeAdditive only ever adds and updates refs, never deletes.
	ModeAdditive Mode = "additive"
)

// Config is the sync configuration, read from sync.conf.
type Config struct {
	// Host is the address of the host device, or "auto" to discover via mDNS.
	Host string
	// Device optionally restricts discovery to one device name.
	Device   string
	Port     int
	Identity string
	Mode     Mode
	// Dirs maps a prefix to a glob of repository directories. The prefix keeps
	// same-named repos from different trees apart on the host.
	Dirs []DirSpec
}

// DirSpec is one configured source of repositories.
type DirSpec struct {
	Prefix string
	Glob   string
}

// DefaultConfig returns the configuration used when no file exists yet.
func DefaultConfig() Config {
	home, _ := os.UserHomeDir()
	return Config{
		Host:     "auto",
		Port:     2222,
		Identity: filepath.Join(home, ".ssh", "githost_ecdsa"),
		Mode:     ModeMirror,
	}
}

const configTemplate = `# git-host sync configuration
#
# host      address of the host device, or "auto" to find it via mDNS
# device    only use the device with this name (optional)
# port      SSH port on the host
# identity  private key to authenticate with (must not be Ed25519)
# mode      mirror   = host is an exact copy; deleting a branch here deletes it there
#           additive = only ever add and update; nothing on the host is removed

host     = auto
port     = 2222
identity = %s
mode     = mirror

# Each entry is  <prefix> = <glob>. A repo at ~/work/foo is pushed as
# work-foo.git, so two repos with the same name never collide.
[dirs]
work  = ~/work/*
tools = ~/code/tools/*
`

// WriteDefault creates a starter config at path.
func WriteDefault(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	def := DefaultConfig()
	return os.WriteFile(path, []byte(fmt.Sprintf(configTemplate, def.Identity)), 0600)
}

// LoadConfig reads the sync configuration from path.
func LoadConfig(path string) (Config, error) {
	cfg := DefaultConfig()

	f, err := os.Open(path)
	if err != nil {
		return cfg, err
	}
	defer f.Close()

	inDirs := false
	scanner := bufio.NewScanner(f)
	line := 0
	for scanner.Scan() {
		line++
		text := strings.TrimSpace(scanner.Text())
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		if strings.HasPrefix(text, "[") && strings.HasSuffix(text, "]") {
			inDirs = strings.EqualFold(text, "[dirs]")
			continue
		}

		key, value, ok := strings.Cut(text, "=")
		if !ok {
			return cfg, fmt.Errorf("%s:%d: expected 'key = value', got %q", path, line, text)
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)

		if inDirs {
			cfg.Dirs = append(cfg.Dirs, DirSpec{Prefix: key, Glob: expandHome(value)})
			continue
		}

		switch key {
		case "host":
			cfg.Host = value
		case "device":
			cfg.Device = value
		case "identity":
			cfg.Identity = expandHome(value)
		case "port":
			n, err := strconv.Atoi(value)
			if err != nil {
				return cfg, fmt.Errorf("%s:%d: port must be a number, got %q", path, line, value)
			}
			cfg.Port = n
		case "mode":
			switch Mode(value) {
			case ModeMirror, ModeAdditive:
				cfg.Mode = Mode(value)
			default:
				return cfg, fmt.Errorf("%s:%d: mode must be \"mirror\" or \"additive\", got %q", path, line, value)
			}
		default:
			return cfg, fmt.Errorf("%s:%d: unknown setting %q", path, line, key)
		}
	}
	if err := scanner.Err(); err != nil {
		return cfg, err
	}
	return cfg, cfg.validate()
}

func (c Config) validate() error {
	if len(c.Dirs) == 0 {
		return fmt.Errorf("no directories configured — add entries under [dirs]")
	}
	seen := map[string]string{}
	for _, d := range c.Dirs {
		if d.Prefix == "" {
			return fmt.Errorf("directory entry %q has an empty prefix", d.Glob)
		}
		if prev, dup := seen[d.Prefix]; dup {
			return fmt.Errorf("prefix %q is used twice (%s and %s) — prefixes must be unique",
				d.Prefix, prev, d.Glob)
		}
		seen[d.Prefix] = d.Glob
	}
	return nil
}

func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") {
		home, err := os.UserHomeDir()
		if err == nil {
			return filepath.Join(home, strings.TrimPrefix(path, "~"))
		}
	}
	return path
}

// Repo is a local repository selected for syncing.
type Repo struct {
	// Path is the local working directory or bare repo.
	Path string
	// Name is what it is called on the host, without the .git suffix.
	Name string
}

// Discover expands the configured globs into the repositories to sync.
//
// It returns an error on a name collision rather than letting one repo silently
// overwrite another on the host — in mirror mode that would destroy history.
func (c Config) Discover() ([]Repo, error) {
	var repos []Repo
	byName := map[string]string{}

	for _, spec := range c.Dirs {
		matches, err := filepath.Glob(spec.Glob)
		if err != nil {
			return nil, fmt.Errorf("bad pattern %q: %w", spec.Glob, err)
		}
		for _, path := range matches {
			if !isGitRepo(path) {
				continue
			}
			name := spec.Prefix + "-" + filepath.Base(path)
			if prev, dup := byName[name]; dup {
				return nil, fmt.Errorf("two repositories would both be pushed as %s.git:\n  %s\n  %s\n"+
					"change one of the prefixes under [dirs]", name, prev, path)
			}
			byName[name] = path
			repos = append(repos, Repo{Path: path, Name: name})
		}
	}

	sort.Slice(repos, func(i, j int) bool { return repos[i].Name < repos[j].Name })
	return repos, nil
}

func isGitRepo(path string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return false
	}
	if st, err := os.Stat(filepath.Join(path, ".git")); err == nil && st.IsDir() {
		return true
	}
	// A bare repository has these at the top level.
	if _, err := os.Stat(filepath.Join(path, "HEAD")); err == nil {
		if _, err := os.Stat(filepath.Join(path, "objects")); err == nil {
			return true
		}
	}
	return false
}

// DirReport describes what one [dirs] entry matched, for diagnosing a config
// that finds nothing.
type DirReport struct {
	Spec DirSpec
	// Matched is how many paths the glob expanded to.
	Matched int
	// NotRepos are matched paths that are not git repositories.
	NotRepos []string
	// Repos is how many of the matches were usable.
	Repos int
}

// Diagnose reports what each configured entry matched. It exists so that
// "nothing to sync" can explain itself rather than leaving the user guessing
// whether the glob, the path, or the repo detection was wrong.
func (c Config) Diagnose() []DirReport {
	reports := make([]DirReport, 0, len(c.Dirs))
	for _, spec := range c.Dirs {
		r := DirReport{Spec: spec}
		matches, err := filepath.Glob(spec.Glob)
		if err != nil {
			reports = append(reports, r)
			continue
		}
		r.Matched = len(matches)
		for _, path := range matches {
			if isGitRepo(path) {
				r.Repos++
			} else if info, err := os.Stat(path); err == nil && info.IsDir() {
				r.NotRepos = append(r.NotRepos, filepath.Base(path))
			}
		}
		reports = append(reports, r)
	}
	return reports
}
