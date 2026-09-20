package syncer

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

// Status is the outcome of syncing one repository.
type Status int

const (
	StatusPushed Status = iota
	StatusSkipped
	StatusFailed
)

// Result reports what happened to one repository.
type Result struct {
	Repo   Repo
	Status Status
	// Reason explains a skip, or carries the git error output on failure.
	Reason string
}

// RemoteURL is where a repository is pushed on the host.
func RemoteURL(host string, port int, name string) string {
	return fmt.Sprintf("ssh://git@%s:%d/%s.git", host, port, name)
}

// sshCommand builds the GIT_SSH_COMMAND git uses for this sync.
//
// Only the host keys pinned at pairing time (in git-host's own known_hosts
// file) are accepted, so a device merely advertising itself on the network
// can never receive a push, and a changed key is refused.
func sshCommand(identity string, port int, knownHosts string) string {
	return fmt.Sprintf("ssh -p %d -i %s -o IdentitiesOnly=yes -o UserKnownHostsFile=%s -o StrictHostKeyChecking=yes -o ConnectTimeout=10",
		port, identity, knownHosts)
}

// pushArgs returns the git arguments implementing the configured mode.
func pushArgs(mode Mode, url string) []string {
	if mode == ModeAdditive {
		// --all and --tags cannot be combined, so name both refspecs
		// explicitly. This adds and force-updates, but never deletes.
		return []string{"push", "--force", url,
			"refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*"}
	}
	return []string{"push", "--mirror", url}
}

// Sync pushes one repository to the host.
func Sync(repo Repo, cfg Config, host string, dryRun bool) Result {
	if !hasRefs(repo.Path) {
		return Result{Repo: repo, Status: StatusSkipped, Reason: "no commits yet"}
	}

	url := RemoteURL(host, cfg.Port, repo.Name)
	if dryRun {
		return Result{Repo: repo, Status: StatusSkipped, Reason: "dry run — would push to " + url}
	}

	args := append([]string{"-C", repo.Path}, pushArgs(cfg.Mode, url)...)
	cmd := exec.Command("git", args...)
	cmd.Env = append(os.Environ(), "GIT_SSH_COMMAND="+sshCommand(cfg.Identity, cfg.Port, cfg.KnownHosts))

	out, err := cmd.CombinedOutput()
	if err != nil {
		return Result{Repo: repo, Status: StatusFailed, Reason: cleanGitError(string(out), err)}
	}
	return Result{Repo: repo, Status: StatusPushed}
}

// hasRefs reports whether the repository has anything worth pushing.
func hasRefs(path string) bool {
	out, err := exec.Command("git", "-C", path,
		"for-each-ref", "--count=1", "--format=%(refname)", "refs/").Output()
	return err == nil && len(strings.TrimSpace(string(out))) > 0
}

// cleanGitError trims git's output down to something worth printing.
func cleanGitError(out string, err error) string {
	var kept []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		// OpenSSH 9.x prints a post-quantum advisory on every connection.
		if line == "" || strings.HasPrefix(line, "**") ||
			strings.Contains(line, "post-quantum") ||
			strings.Contains(line, "openssh.com/pq") {
			continue
		}
		kept = append(kept, line)
	}
	if len(kept) == 0 {
		return err.Error()
	}
	return strings.Join(kept, "; ")
}

// Summary counts results by status.
type Summary struct {
	Pushed  int
	Skipped int
	Failed  int
	Elapsed time.Duration
}

func Summarize(results []Result, elapsed time.Duration) Summary {
	s := Summary{Elapsed: elapsed}
	for _, r := range results {
		switch r.Status {
		case StatusPushed:
			s.Pushed++
		case StatusSkipped:
			s.Skipped++
		case StatusFailed:
			s.Failed++
		}
	}
	return s
}
