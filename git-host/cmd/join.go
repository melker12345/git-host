package cmd

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/melker/git-host/internal/pairing"
	"github.com/spf13/cobra"
)

var joinCmd = &cobra.Command{
	Use:   "join <PIN>",
	Short: "Pair this machine with a git-host and configure SSH automatically",
	Long: `Pairs this machine with a git-host server using the 6-digit PIN shown
on the host screen. Generates an SSH key if needed, sends it securely, and
writes an SSH config entry — so git commands work immediately after.

Example:
  git-host join 482951 --host 192.168.1.154`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		rawPIN := strings.ReplaceAll(args[0], " ", "")
		if len(rawPIN) != 6 {
			return fmt.Errorf("PIN must be 6 digits, got %q", args[0])
		}

		hostAddr, _ := cmd.Flags().GetString("host")
		if hostAddr == "" {
			return fmt.Errorf("--host is required (e.g. --host 192.168.1.154)")
		}

		identityFile, _ := cmd.Flags().GetString("identity")
		if identityFile == "" {
			found, err := ensurePubkey()
			if err != nil {
				return err
			}
			identityFile = found
		}

		pairingPort := port + 1
		fmt.Printf("Using key:    %s\n", identityFile)
		fmt.Printf("Connecting to %s...\n", hostAddr)

		if err := pairing.FriendJoin(context.Background(), hostAddr, rawPIN, identityFile, pairingPort); err != nil {
			return err
		}

		privateKeyPath := strings.TrimSuffix(identityFile, ".pub")
		alias, cfgErr := writeSSHConfig(hostAddr, port, privateKeyPath)
		if cfgErr != nil {
			fmt.Fprintf(os.Stderr, "⚠  Could not write SSH config: %v\n", cfgErr)
			fmt.Fprintf(os.Stderr, "   Add manually to ~/.ssh/config:\n")
			fmt.Fprintf(os.Stderr, "   Host %s\n     HostName %s\n     Port %d\n     User git\n     IdentityFile %s\n     StrictHostKeyChecking no\n",
				alias, hostAddr, port, privateKeyPath)
		}

		fmt.Printf("\n✓  Paired successfully!\n\n")
		fmt.Printf("Clone a repo:\n  git clone ssh://%s:%d/<repo>.git\n\n", alias, port)
		fmt.Printf("Push a new repo:\n  git remote add phone ssh://%s:%d/<repo>.git\n  git push -u phone main\n", alias, port)
		return nil
	},
}

// ensurePubkey returns an existing ECDSA/RSA pubkey path, or auto-generates
// a new ECDSA key at ~/.ssh/id_ecdsa if none exists.
func ensurePubkey() (string, error) {
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(home, ".ssh", "id_ecdsa.pub"),
		filepath.Join(home, ".ssh", "id_rsa.pub"),
		filepath.Join(home, ".ssh", "id_ed25519.pub"),
	}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}

	// No key found — generate one automatically.
	keyPath := filepath.Join(home, ".ssh", "id_ecdsa")
	fmt.Println("No SSH key found — generating ~/.ssh/id_ecdsa...")
	if err := os.MkdirAll(filepath.Join(home, ".ssh"), 0700); err != nil {
		return "", fmt.Errorf("could not create ~/.ssh: %w", err)
	}
	out, err := exec.Command("ssh-keygen", "-t", "ecdsa", "-b", "256",
		"-f", keyPath, "-N", "", "-C", "githost").CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("ssh-keygen failed: %w\n%s", err, out)
	}
	fmt.Println("Generated ~/.ssh/id_ecdsa")
	return keyPath + ".pub", nil
}

// writeSSHConfig writes a Host block for hostAddr:sshPort using identityFile.
// Uses the raw IP as the alias so it resolves without DNS.
// Updates HostName if alias already exists.
func writeSSHConfig(hostAddr string, sshPort int, identityFile string) (string, error) {
	home, _ := os.UserHomeDir()
	cfgPath := filepath.Join(home, ".ssh", "config")
	alias := hostAddr // use raw IP — always resolvable

	existing, _ := os.ReadFile(cfgPath)
	content := string(existing)

	// If this alias block already exists, update IdentityFile line only.
	hostLine := "Host " + alias
	scanner := bufio.NewScanner(strings.NewReader(content))
	for scanner.Scan() {
		if strings.TrimSpace(scanner.Text()) == hostLine {
			return alias, nil // already configured, leave it alone
		}
	}

	entry := fmt.Sprintf(
		"\nHost %s\n  HostName %s\n  Port %d\n  User git\n  IdentityFile %s\n  IdentitiesOnly yes\n  StrictHostKeyChecking no\n",
		alias, hostAddr, sshPort, identityFile,
	)

	f, err := os.OpenFile(cfgPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return alias, err
	}
	defer f.Close()
	_, err = f.WriteString(entry)
	return alias, err
}

func init() {
	joinCmd.Flags().String("host", "", "IP or hostname of the git-host server (required)")
	joinCmd.Flags().String("identity", "", "path to SSH public key (default: auto-detect or generate)")
	rootCmd.AddCommand(joinCmd)
}
