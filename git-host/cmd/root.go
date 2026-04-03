package cmd

import (
	"os"
	"path/filepath"

	"github.com/spf13/cobra"
)

var (
	cfgDir  string
	baseDir string
	port    int
)

var rootCmd = &cobra.Command{
	Use:   "git-host",
	Short: "LocalGitHub — self-hosted git server over SSH",
	Long:  "git-host turns your device into a private git hosting endpoint accessible over SSH on your local network.",
}

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
