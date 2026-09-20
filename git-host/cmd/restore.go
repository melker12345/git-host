package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/melker/git-host/internal/discovery"
	"github.com/melker/git-host/internal/syncer"
	"github.com/spf13/cobra"
)

var restoreCmd = &cobra.Command{
	Use:   "restore [repo...]",
	Short: "Clone repositories back from the host onto this machine",
	Long: `The reverse of sync: lists the repositories the host holds and clones the
ones this machine does not have yet, into the directories sync.conf maps
them from. A repository the host calls work-foo.git lands in the directory
that the "work" entry points at, as foo.

Cloned repositories get the host as a remote named "phone". A remote such
as GitHub is not restored; add it again by hand.

Examples:
  git-host restore                 # clone everything that is missing
  git-host restore code-git-host   # just this one
  git-host restore --list          # only show what the host holds`,
	RunE: runRestore,
}

func runRestore(cmd *cobra.Command, args []string) error {
	cfgPath, _ := cmd.Flags().GetString("config")
	if cfgPath == "" {
		cfgPath = filepath.Join(cfgDir, "sync.conf")
	}
	cfg, err := syncer.LoadConfig(cfgPath)
	if err != nil {
		return err
	}
	if v, _ := cmd.Flags().GetString("host"); v != "" {
		cfg.Host = v
	}
	if cmd.Flags().Changed("port") {
		cfg.Port = port
	}
	listOnly, _ := cmd.Flags().GetBool("list")
	into, _ := cmd.Flags().GetString("into")
	trustNew, _ := cmd.Flags().GetBool("trust-new")

	host, err := resolveHost(cmd, &cfg, trustNew)
	if err != nil {
		return err
	}
	cfg.KnownHosts, err = discovery.WriteKnownHosts(cfgDir)
	if err != nil {
		return fmt.Errorf("write known_hosts: %w", err)
	}
	if !discovery.Reachable(host, cfg.Port, 5*time.Second) {
		return fmt.Errorf("host %s:%d is not reachable\n  is the server running on the host device, and are you on the same network?", host, cfg.Port)
	}

	names, err := syncer.ListRemote(cfg, host)
	if err != nil {
		return err
	}
	if len(names) == 0 {
		fmt.Println("The host holds no repositories.")
		return nil
	}

	local := map[string]string{}
	if repos, err := cfg.Discover(); err == nil {
		for _, r := range repos {
			local[r.Name] = r.Path
		}
	}

	if listOnly {
		fmt.Printf("%-40s %s\n", "REPOSITORY", "LOCAL COPY")
		for _, n := range names {
			where := local[n]
			if where == "" {
				where = "-"
			}
			fmt.Printf("%-40s %s\n", n, where)
		}
		return nil
	}

	wanted := names
	if len(args) > 0 {
		wanted = nil
		for _, a := range args {
			a = strings.TrimSuffix(a, ".git")
			found := false
			for _, n := range names {
				if n == a {
					found = true
					wanted = append(wanted, n)
				}
			}
			if !found {
				return fmt.Errorf("the host has no repository named %s (see  git-host restore --list)", a)
			}
		}
	}

	var cloned, skipped, failed int
	for _, name := range wanted {
		fmt.Printf("  %-40s ", name)
		// An explicit --into means "clone it there", even if a copy exists
		// elsewhere; without it, an existing local copy is left alone.
		if p, ok := local[name]; ok && into == "" {
			fmt.Printf("skipped (already at %s)\n", p)
			skipped++
			continue
		}
		dest := into
		if dest == "" {
			dest = cfg.RestorePath(name)
		} else {
			dest = filepath.Join(into, strings.TrimPrefix(name, prefixOf(cfg, name)))
		}
		if dest == "" {
			fmt.Println("skipped (no [dirs] entry matches this name; use --into)")
			skipped++
			continue
		}
		if _, err := os.Stat(dest); err == nil {
			fmt.Printf("skipped (%s exists)\n", dest)
			skipped++
			continue
		}
		if err := syncer.Clone(cfg, host, name, dest); err != nil {
			fmt.Printf("FAILED\n      %s\n", err)
			failed++
			continue
		}
		fmt.Printf("ok -> %s\n", dest)
		cloned++
	}
	fmt.Printf("\n%d cloned, %d skipped, %d failed\n", cloned, skipped, failed)
	if cloned > 0 {
		fmt.Println("Each clone has the host as remote \"phone\". Re-add any other remotes yourself.")
	}
	if failed > 0 {
		return fmt.Errorf("%d repositories failed to restore", failed)
	}
	return nil
}

// prefixOf returns "<prefix>-" for the longest [dirs] prefix that name starts with.
func prefixOf(cfg syncer.Config, name string) string {
	best := ""
	for _, d := range cfg.Dirs {
		p := d.Prefix + "-"
		if strings.HasPrefix(name, p) && len(p) > len(best) {
			best = p
		}
	}
	return best
}

func init() {
	restoreCmd.Flags().String("config", "", "path to sync.conf (default: <config-dir>/sync.conf)")
	restoreCmd.Flags().String("host", "", "override the host address from the config")
	restoreCmd.Flags().String("into", "", "clone into this directory instead of the sync.conf mapping")
	restoreCmd.Flags().Bool("list", false, "list the host's repositories and stop")
	restoreCmd.Flags().Bool("trust-new", false, "pin the SSH host key the host presents now (normally done by pair)")
	rootCmd.AddCommand(restoreCmd)
}
