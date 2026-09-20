package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/melker/git-host/internal/discovery"
	"github.com/melker/git-host/internal/syncer"
	"github.com/spf13/cobra"
)

var syncCmd = &cobra.Command{
	Use:   "sync",
	Short: "Back up local repositories to a paired host",
	Long: `Pushes every repository matched by the [dirs] entries in sync.conf to the
host device, finding it on the network automatically.

If the host is not reachable — the server is not running, or you are on a
different network — this reports that and exits without error, so it is safe to
run from a script.`,
	RunE: runSync,
}

func runSync(cmd *cobra.Command, args []string) error {
	cfgPath, _ := cmd.Flags().GetString("config")
	if cfgPath == "" {
		cfgPath = filepath.Join(cfgDir, "sync.conf")
	}

	cfg, err := syncer.LoadConfig(cfgPath)
	if os.IsNotExist(err) {
		if err := syncer.WriteDefault(cfgPath); err != nil {
			return fmt.Errorf("create %s: %w", cfgPath, err)
		}
		fmt.Printf("Created %s\n\nEdit it to list your repository directories, then run this again.\n", cfgPath)
		return nil
	}
	if err != nil {
		return err
	}

	// Command-line overrides win over the file.
	if v, _ := cmd.Flags().GetString("host"); v != "" {
		cfg.Host = v
	}
	if v, _ := cmd.Flags().GetString("mode"); v != "" {
		cfg.Mode = syncer.Mode(v)
		if cfg.Mode != syncer.ModeMirror && cfg.Mode != syncer.ModeAdditive {
			return fmt.Errorf("--mode must be \"mirror\" or \"additive\", got %q", v)
		}
	}
	if cmd.Flags().Changed("port") {
		cfg.Port = port
	}
	dryRun, _ := cmd.Flags().GetBool("dry-run")
	only, _ := cmd.Flags().GetString("only")

	repos, err := cfg.Discover()
	if err != nil {
		return err
	}
	if only != "" {
		repos = filterRepos(repos, only)
		if len(repos) == 0 {
			return fmt.Errorf("no configured repository matches %q", only)
		}
	}
	if len(repos) == 0 {
		reportEmpty(cfg, cfgPath)
		return nil
	}

	host, err := resolveHost(cmd, &cfg)
	if err != nil {
		return err
	}

	// An unreachable host is an ordinary condition, not a failure: the server
	// stops itself when idle. Say so plainly instead of emitting one SSH error
	// per repository.
	if !dryRun && !discovery.Reachable(host, cfg.Port, 5*time.Second) {
		fmt.Printf("Host %s:%d is not reachable — nothing synced.\n", host, cfg.Port)
		fmt.Println("Is the server running on the host device, and are you on the same network?")
		return nil
	}

	fmt.Printf("Syncing %d repositories to %s (%s)\n\n", len(repos), host, cfg.Mode)

	start := time.Now()
	results := make([]syncer.Result, 0, len(repos))
	for _, repo := range repos {
		fmt.Printf("  %-40s ", repo.Name)
		res := syncer.Sync(repo, cfg, host, dryRun)
		results = append(results, res)
		switch res.Status {
		case syncer.StatusPushed:
			fmt.Println("ok")
		case syncer.StatusSkipped:
			fmt.Printf("skipped (%s)\n", res.Reason)
		case syncer.StatusFailed:
			fmt.Printf("FAILED\n      %s\n", res.Reason)
		}
	}

	s := syncer.Summarize(results, time.Since(start))
	fmt.Printf("\n%d pushed, %d skipped, %d failed in %s\n",
		s.Pushed, s.Skipped, s.Failed, s.Elapsed.Round(time.Millisecond))

	if s.Failed > 0 {
		return fmt.Errorf("%d repositories failed to sync", s.Failed)
	}
	return nil
}

// reportEmpty explains why nothing was found, rather than leaving the user to
// guess whether the path, the glob, or the repo detection was at fault.
func reportEmpty(cfg syncer.Config, cfgPath string) {
	fmt.Printf("No repositories found. What %s looked at:\n\n", cfgPath)
	for _, r := range cfg.Diagnose() {
		fmt.Printf("  %-8s %s\n", r.Spec.Prefix, r.Spec.Glob)
		switch {
		case r.Matched == 0:
			fmt.Printf("           nothing matched this pattern\n")
		case r.Repos == 0:
			fmt.Printf("           %d matched, none are git repositories: %s\n",
				r.Matched, strings.Join(r.NotRepos, ", "))
		default:
			fmt.Printf("           %d repositories\n", r.Repos)
		}
	}
	fmt.Printf("\nA directory only counts if it contains .git — check whether your repos\n")
	fmt.Printf("are one level deeper or shallower than the pattern, and edit [dirs].\n")
}

// resolveHost turns the configured host into an address to dial, discovering it
// on the network when set to "auto". A discovered device also supplies the
// port it advertises, unless --port was given explicitly.
func resolveHost(cmd *cobra.Command, cfg *syncer.Config) (string, error) {
	if cfg.Host != "" && cfg.Host != "auto" {
		return cfg.Host, nil
	}

	d, err := discovery.Find(context.Background(), cfgDir, cfg.Device, 5*time.Second)
	if err != nil {
		return "", err
	}
	if d.Cached {
		fmt.Printf("Discovery found nothing; using last known address for %s\n", d.Name)
	}
	if d.Port != 0 && !cmd.Flags().Changed("port") {
		cfg.Port = d.Port
	}
	return d.Addr(), nil
}

func filterRepos(repos []syncer.Repo, want string) []syncer.Repo {
	var out []syncer.Repo
	for _, r := range repos {
		if r.Name == want || filepath.Base(r.Path) == want || strings.HasSuffix(r.Name, "-"+want) {
			out = append(out, r)
		}
	}
	return out
}

func init() {
	syncCmd.Flags().String("config", "", "path to sync.conf (default: <config-dir>/sync.conf)")
	syncCmd.Flags().String("host", "", "override the host address from the config")
	syncCmd.Flags().String("mode", "", "override the sync mode: mirror or additive")
	syncCmd.Flags().String("only", "", "sync just the repository with this name")
	syncCmd.Flags().Bool("dry-run", false, "list what would be pushed without pushing")
	rootCmd.AddCommand(syncCmd)
}
