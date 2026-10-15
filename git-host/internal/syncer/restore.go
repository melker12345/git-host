package syncer

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// ListCommand is the one non-git command a git-host server accepts over SSH.
// It prints the names of the repositories it holds, one per line, without
// the .git suffix.
const ListCommand = "git-host-ls"

// ListRemote asks the host which repositories it holds.
func ListRemote(cfg Config, host string) ([]string, error) {
	args := strings.Fields(sshCommand(cfg.Identity, cfg.Port, cfg.KnownHosts))
	args = append(args, "git@"+host, ListCommand)
	out, err := exec.Command(args[0], args[1:]...).CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("list repositories on %s: %s", host, cleanGitError(string(out), err))
	}
	var names []string
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || isSSHNoise(line) {
			continue
		}
		names = append(names, strings.TrimSuffix(line, ".git"))
	}
	sort.Strings(names)
	return names, nil
}

// Clone fetches one repository from the host into dest, with the host as a
// remote named "phone" so a later sync or pull needs no setup.
func Clone(cfg Config, host, name, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0755); err != nil {
		return err
	}
	cmd := exec.Command("git", "clone", "--origin", "phone", RemoteURL(host, cfg.Port, name), dest)
	cmd.Env = append(os.Environ(), "GIT_SSH_COMMAND="+sshCommand(cfg.Identity, cfg.Port, cfg.KnownHosts))
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s", cleanGitError(string(out), err))
	}
	return nil
}

// RestorePath maps a repository name back to a local path using [dirs]:
// the longest matching prefix wins, and the rest of the name becomes a
// directory under that entry's glob base. Returns "" when nothing matches.
func (c Config) RestorePath(name string) string {
	var best DirSpec
	for _, d := range c.Dirs {
		p := d.Prefix + "-"
		if strings.HasPrefix(name, p) && len(p) > len(best.Prefix)+1 {
			best = d
		}
	}
	if best.Prefix == "" {
		return ""
	}
	base := globBase(best.Glob)
	if base == "" {
		return ""
	}
	return filepath.Join(base, strings.TrimPrefix(name, best.Prefix+"-"))
}

// globBase returns the part of a glob before its first wildcard component.
func globBase(glob string) string {
	parts := strings.Split(filepath.Clean(glob), string(filepath.Separator))
	var kept []string
	for _, p := range parts {
		if strings.ContainsAny(p, "*?[") {
			break
		}
		kept = append(kept, p)
	}
	if len(kept) == 0 {
		return ""
	}
	base := filepath.Join(kept...)
	if strings.HasPrefix(glob, string(filepath.Separator)) {
		base = string(filepath.Separator) + base
	}
	return base
}
