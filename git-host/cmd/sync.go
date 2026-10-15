package cmd

import (
	"errors"
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

If the host is not reachable (the app is closed, or you are on a different
network) this says so and exits without error, so it is safe to run from a
timer. Missed backups are still noticed: once no sync has reached the host
for longer than warn_after (3 days by default), sync exits with an error.`,
	RunE: runSync,
}

// errUnreachable marks a run where the host could not be reached. It is not
// a failure by itself; runSync decides whether it has gone on for too long.
var errUnreachable = errors.New("host not reachable")

// runSync loads sync.conf (creating a starter one on first use), pushes the
// selected repositories, and records the outcome for overdue detection.
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

	device, err := pushAll(cmd, &cfg, repos, dryRun)
	if dryRun {
		if errors.Is(err, errUnreachable) {
			return nil
		}
		return err
	}
	// A run limited by --only is not a full backup, so it must not reset the
	// clock that detects missed backups.
	return recordOutcome(cfg, device, err, only == "")
}

// pushAll pushes repos to the configured host and returns the device it
// chose. It returns errUnreachable when the host cannot be reached, and an
// ordinary error when the host cannot be chosen or any push fails.
func pushAll(cmd *cobra.Command, cfg *syncer.Config, repos []syncer.Repo, dryRun bool) (string, error) {
	trustNew, _ := cmd.Flags().GetBool("trust-new")
	dev, err := resolveHost(cmd, cfg, trustNew)
	if err != nil {
		return dev.Name, err
	}
	cfg.KnownHosts, err = discovery.WriteKnownHosts(cfgDir)
	if err != nil {
		return dev.Name, fmt.Errorf("write known_hosts: %w", err)
	}

	host := dev.Addr()
	target := describeDevice(dev, host)

	// The server stops itself when idle, so an unreachable host is the normal
	// case for a timer. Say so once instead of one SSH error per repository.
	if !discovery.Reachable(host, cfg.Port, 5*time.Second) {
		fmt.Printf("%s is not reachable — nothing synced.\n", target)
		fmt.Println("Is the git-host app open on the device, and are you on the same network?")
		return dev.Name, errUnreachable
	}

	verb := "Syncing"
	if dryRun {
		verb = "Dry run:"
	}
	fmt.Printf("%s %d repositories to %s, %s mode\n\n", verb, len(repos), target, cfg.Mode)

	start := time.Now()
	results := make([]syncer.Result, 0, len(repos))
	var failed []string
	for _, repo := range repos {
		fmt.Printf("  %-40s ", repo.Name)
		res := syncer.Sync(repo, *cfg, host, dryRun)
		results = append(results, res)
		switch res.Status {
		case syncer.StatusPushed:
			fmt.Println("ok")
		case syncer.StatusSkipped:
			fmt.Printf("skipped (%s)\n", res.Reason)
		case syncer.StatusFailed:
			fmt.Printf("FAILED\n      %s\n", res.Reason)
			failed = append(failed, repo.Name)
		}
	}

	s := syncer.Summarize(results, time.Since(start))
	fmt.Printf("\n%d pushed, %d skipped, %d failed in %s\n",
		s.Pushed, s.Skipped, s.Failed, s.Elapsed.Round(time.Millisecond))

	if len(failed) > 0 {
		return dev.Name, fmt.Errorf("%d of %d repositories failed to sync to %s: %s",
			len(failed), len(repos), dev.Name, strings.Join(failed, ", "))
	}
	return dev.Name, nil
}

// recordOutcome saves what this run achieved and turns it into the exit
// status: nil for a successful sync or a tolerable unreachable host, an error
// for failed pushes or a backup that is overdue. full says whether every
// configured repository was attempted.
func recordOutcome(cfg syncer.Config, device string, runErr error, full bool) error {
	now := time.Now()
	state := syncer.LoadState(cfgDir)
	if state.FirstRun.IsZero() {
		state.FirstRun = now
	}
	state.LastRun = now

	var err error
	switch {
	case runErr == nil:
		if full {
			state.LastSuccess = now
			state.Device = device
		}
		state.LastError = ""
	case errors.Is(runErr, errUnreachable):
		if state.Overdue(now, cfg.WarnAfter) {
			err = fmt.Errorf("no backup has reached %s since %s (%s ago)",
				orUnknown(device), state.Since().Format("Mon 2 Jan 15:04"), ago(now.Sub(state.Since())))
		} else if !state.LastSuccess.IsZero() {
			fmt.Printf("Last successful sync: %s ago.\n", ago(now.Sub(state.LastSuccess)))
		}
	default:
		err = runErr
	}

	if err != nil {
		state.LastError = err.Error()
	}

	if saveErr := state.Save(cfgDir); saveErr != nil {
		fmt.Fprintf(os.Stderr, "warning: could not save sync state: %v\n", saveErr)
	}
	return err
}

// ago renders a duration coarsely: "40 minutes", "5 hours", "3 days".
func ago(d time.Duration) string {
	switch {
	case d < time.Minute:
		return "less than a minute"
	case d < time.Hour:
		return plural(int(d/time.Minute), "minute")
	case d < 48*time.Hour:
		return plural(int(d/time.Hour), "hour")
	default:
		return plural(int(d/(24*time.Hour)), "day")
	}
}

// plural renders a count with its unit, e.g. "1 hour", "3 hours".
func plural(n int, unit string) string {
	if n == 1 {
		return "1 " + unit
	}
	return fmt.Sprintf("%d %ss", n, unit)
}

// orUnknown names a device for messages, when sync never learned its name.
func orUnknown(s string) string {
	if s == "" {
		return "the host"
	}
	return s
}

// filterRepos keeps the repositories --only refers to. It accepts the name
// on the host ("code-foo"), the directory name ("foo"), or the part after
// the prefix, so the user need not remember which prefix a repo got.
func filterRepos(repos []syncer.Repo, want string) []syncer.Repo {
	var out []syncer.Repo
	for _, r := range repos {
		if r.Name == want || filepath.Base(r.Path) == want || strings.HasSuffix(r.Name, "-"+want) {
			out = append(out, r)
		}
	}
	return out
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

func init() {
	syncCmd.Flags().String("config", "", "path to sync.conf (default: <config-dir>/sync.conf)")
	syncCmd.Flags().String("host", "", "override the host address from the config")
	syncCmd.Flags().String("mode", "", "override the sync mode: mirror or additive")
	syncCmd.Flags().String("only", "", "sync just the repository with this name")
	syncCmd.Flags().Bool("dry-run", false, "list what would be pushed without pushing")
	syncCmd.Flags().Bool("trust-new", false, "pin the SSH host key the host presents now (normally done by pair)")
	rootCmd.AddCommand(syncCmd)
}
