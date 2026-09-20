package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/spf13/cobra"
)

const timerName = "git-host-sync"

var timerCmd = &cobra.Command{
	Use:   "timer",
	Short: "Run sync automatically on a schedule (systemd user timer)",
}

var timerInstallCmd = &cobra.Command{
	Use:   "install",
	Short: "Install and start a systemd user timer that runs git-host sync",
	Long: `Writes ~/.config/systemd/user/git-host-sync.{service,timer} and enables the
timer. sync exits quietly when the host is not reachable, so the timer can
fire whether or not the phone is around.

  git-host timer install                 # every 30 minutes
  git-host timer install --every 2h
  git-host timer status
  git-host timer remove`,
	RunE: func(cmd *cobra.Command, args []string) error {
		every, _ := cmd.Flags().GetDuration("every")
		self, err := os.Executable()
		if err != nil {
			return err
		}
		self, _ = filepath.EvalSymlinks(self)

		dir, err := systemdUserDir()
		if err != nil {
			return err
		}
		service := fmt.Sprintf(`[Unit]
Description=Back up local git repositories to a git-host device

[Service]
Type=oneshot
ExecStart=%s --config-dir %s sync
`, self, cfgDir)
		timer := fmt.Sprintf(`[Unit]
Description=Run git-host sync every %s

[Timer]
OnBootSec=2min
OnUnitActiveSec=%s
Persistent=true

[Install]
WantedBy=timers.target
`, every, systemdDuration(every))

		if err := os.WriteFile(filepath.Join(dir, timerName+".service"), []byte(service), 0644); err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(dir, timerName+".timer"), []byte(timer), 0644); err != nil {
			return err
		}
		if out, err := exec.Command("systemctl", "--user", "daemon-reload").CombinedOutput(); err != nil {
			return fmt.Errorf("systemctl daemon-reload: %s", out)
		}
		if out, err := exec.Command("systemctl", "--user", "enable", "--now", timerName+".timer").CombinedOutput(); err != nil {
			return fmt.Errorf("systemctl enable: %s", out)
		}
		fmt.Printf("Installed %s.timer: git-host sync runs every %s.\n", timerName, every)
		fmt.Printf("Logs:  journalctl --user -u %s\n", timerName)
		return nil
	},
}

var timerRemoveCmd = &cobra.Command{
	Use:   "remove",
	Short: "Stop and remove the sync timer",
	RunE: func(cmd *cobra.Command, args []string) error {
		_ = exec.Command("systemctl", "--user", "disable", "--now", timerName+".timer").Run()
		dir, err := systemdUserDir()
		if err != nil {
			return err
		}
		_ = os.Remove(filepath.Join(dir, timerName+".service"))
		_ = os.Remove(filepath.Join(dir, timerName+".timer"))
		_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
		fmt.Println("Removed", timerName+".timer")
		return nil
	},
}

var timerStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show whether the sync timer is active and when it last ran",
	RunE: func(cmd *cobra.Command, args []string) error {
		c := exec.Command("systemctl", "--user", "list-timers", timerName+".timer", "--no-pager")
		c.Stdout, c.Stderr = os.Stdout, os.Stderr
		if err := c.Run(); err != nil {
			return err
		}
		c = exec.Command("journalctl", "--user", "-u", timerName, "-n", "12", "--no-pager", "-o", "cat")
		c.Stdout, c.Stderr = os.Stdout, os.Stderr
		return c.Run()
	},
}

func systemdUserDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".config", "systemd", "user")
	return dir, os.MkdirAll(dir, 0755)
}

// systemdDuration renders a duration the way systemd.time expects ("30min", "2h").
func systemdDuration(d time.Duration) string {
	if d%time.Hour == 0 {
		return fmt.Sprintf("%dh", int(d/time.Hour))
	}
	return fmt.Sprintf("%dmin", int(d/time.Minute))
}

func init() {
	timerInstallCmd.Flags().Duration("every", 30*time.Minute, "how often to run sync")
	timerCmd.AddCommand(timerInstallCmd, timerRemoveCmd, timerStatusCmd)
	rootCmd.AddCommand(timerCmd)
}
