// Package cmd implements the git-host command line: pairing with a host
// device, syncing repositories to it, restoring them from it, scheduling
// sync with a systemd timer, and running a server of its own.
package cmd

import (
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
)

// Values of the persistent flags shared by every subcommand.
var (
	cfgDir  string // --config-dir: sync.conf, devices.json, known_hosts, state
	baseDir string // --base-dir: where serve keeps bare repositories
	port    int    // --port: SSH port to listen on (serve) or dial (others)
)

// rootCmd is the bare git-host command; subcommands register in their init.
var rootCmd = &cobra.Command{
	Use:   "git-host",
	Short: "LocalGitHub — self-hosted git server over SSH",
	Long: `git-host backs up your local git repositories to a git-host device (such as
the LocalGitHub Android app) over SSH on your local network, and restores
them from it. It can also run a git-host server itself with  git-host serve.`,
	// Errors from RunE are real failures, not usage mistakes; don't bury them
	// under the flag listing. main prints them, so cobra must not as well.
	SilenceUsage:  true,
	SilenceErrors: true,
}

// Execute runs the command line. main prints the returned error.
func Execute() error {
	return rootCmd.Execute()
}

func init() {
	home, _ := os.UserHomeDir()
	defaultCfg := filepath.Join(home, ".config", "git-host")
	defaultRepos := filepath.Join(home, "repos")

	rootCmd.PersistentFlags().StringVar(&cfgDir, "config-dir", defaultCfg, "config directory")
	rootCmd.PersistentFlags().StringVar(&baseDir, "base-dir", defaultRepos, "base directory for bare repos")
	rootCmd.PersistentFlags().IntVar(&port, "port", 2222, "SSH listen port")
}
