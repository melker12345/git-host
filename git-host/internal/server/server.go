// Package server is the git-host SSH server run by git-host serve. It speaks
// just enough SSH to let whitelisted keys run git-upload-pack and
// git-receive-pack against bare repositories under one base directory, plus
// the syncer.ListCommand used by restore.
package server

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/gliderlabs/ssh"
	"github.com/melker/git-host/internal/syncer"
	"github.com/melker/git-host/internal/whitelist"
	gossh "golang.org/x/crypto/ssh"
)

// contextKey keys values stored in an SSH session context.
type contextKey string

// labelKey carries the authorized key's whitelist label from authentication
// to the session, for logging.
const labelKey contextKey = "wl_label"

// Config is what Run needs to serve.
type Config struct {
	Port    int                  // TCP port to listen on
	BaseDir string               // directory holding the bare repositories
	Signer  gossh.Signer         // the server's SSH host key
	WL      *whitelist.Whitelist // keys allowed to connect
}

// Run serves until ctx is cancelled. Every authorized key may fetch from and
// push to any repository; a push to one that does not exist creates it.
func Run(ctx context.Context, cfg Config) error {
	srv := &ssh.Server{
		Addr: fmt.Sprintf(":%d", cfg.Port),

		PublicKeyHandler: func(sshCtx ssh.Context, key ssh.PublicKey) bool {
			entry, ok := cfg.WL.Authorize(key)
			if ok {
				sshCtx.SetValue(labelKey, entry.Label)
				log.Printf("AUTH ok   key=%s label=%q addr=%s",
					gossh.FingerprintSHA256(key), entry.Label, sshCtx.RemoteAddr())
			} else {
				log.Printf("AUTH fail key=%s addr=%s",
					gossh.FingerprintSHA256(key), sshCtx.RemoteAddr())
			}
			return ok
		},

		Handler: func(s ssh.Session) {
			label, _ := s.Context().Value(labelKey).(string)

			if s.RawCommand() == "" {
				io.WriteString(s.Stderr(), "git-host: interactive sessions are not supported\n")
				s.Exit(1)
				return
			}

			parts := s.Command()
			if len(parts) == 1 && parts[0] == syncer.ListCommand {
				log.Printf("LIST  label=%q", label)
				for _, name := range listRepos(cfg.BaseDir) {
					fmt.Fprintln(s, name)
				}
				s.Exit(0)
				return
			}
			if len(parts) < 2 {
				io.WriteString(s.Stderr(), "git-host: invalid command\n")
				s.Exit(1)
				return
			}

			gitCmd := parts[0]
			repoArg := parts[1]

			if gitCmd != "git-upload-pack" && gitCmd != "git-receive-pack" {
				fmt.Fprintf(s.Stderr(), "git-host: command not allowed: %s\n", gitCmd)
				s.Exit(1)
				return
			}

			repoPath, err := resolvePath(cfg.BaseDir, repoArg)
			if err != nil {
				fmt.Fprintf(s.Stderr(), "git-host: %v\n", err)
				s.Exit(1)
				return
			}

			if gitCmd == "git-receive-pack" {
				if err := ensureRepo(repoPath); err != nil {
					fmt.Fprintf(s.Stderr(), "git-host: failed to init repository: %v\n", err)
					s.Exit(1)
					return
				}
				log.Printf("PUSH  label=%q repo=%s", label, filepath.Base(repoPath))
			} else {
				if _, err := os.Stat(repoPath); os.IsNotExist(err) {
					fmt.Fprintf(s.Stderr(), "git-host: repository not found: %s\n", repoArg)
					s.Exit(1)
					return
				}
				log.Printf("FETCH label=%q repo=%s", label, filepath.Base(repoPath))
			}

			cmd := exec.CommandContext(s.Context(), gitCmd, repoPath)
			cmd.Stdin = s
			cmd.Stdout = s
			cmd.Stderr = s.Stderr()

			if err := cmd.Run(); err != nil {
				if exitErr, ok := err.(*exec.ExitError); ok {
					s.Exit(exitErr.ExitCode())
				} else {
					s.Exit(1)
				}
				return
			}
			s.Exit(0)
		},
	}

	srv.AddHostKey(cfg.Signer)

	go func() {
		<-ctx.Done()
		srv.Close()
	}()

	log.Printf("Listening on :%d", cfg.Port)
	if err := srv.ListenAndServe(); err != ssh.ErrServerClosed {
		return err
	}
	return nil
}

// resolvePath maps the repository path a client asked for onto baseDir,
// refusing anything that would escape it.
func resolvePath(baseDir, reqPath string) (string, error) {
	clean := filepath.Clean(strings.TrimPrefix(reqPath, "/"))

	if clean == ".." || strings.HasPrefix(clean, "../") {
		return "", fmt.Errorf("invalid path: %s", reqPath)
	}

	absBase, err := filepath.Abs(baseDir)
	if err != nil {
		return "", fmt.Errorf("bad base dir: %w", err)
	}

	resolved := filepath.Join(absBase, clean)

	if resolved != absBase && !strings.HasPrefix(resolved, absBase+string(filepath.Separator)) {
		return "", fmt.Errorf("path traversal detected: %s", reqPath)
	}

	return resolved, nil
}

// ensureRepo creates a bare repository at path unless something exists there.
func ensureRepo(path string) error {
	if _, err := os.Stat(path); err == nil {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	if err := exec.Command("git", "init", "--bare", path).Run(); err != nil {
		return err
	}
	// Set default branch to main (git <2.28 defaults to master).
	// Ignore errors — older git may not support symbolic-ref this way.
	_ = exec.Command("git", "-C", path, "symbolic-ref", "HEAD", "refs/heads/main").Run()
	return nil
}

// listRepos names the bare repositories directly under baseDir.
func listRepos(baseDir string) []string {
	entries, err := os.ReadDir(baseDir)
	if err != nil {
		return nil
	}
	var names []string
	for _, e := range entries {
		if e.IsDir() && strings.HasSuffix(e.Name(), ".git") {
			names = append(names, e.Name())
		}
	}
	return names
}
