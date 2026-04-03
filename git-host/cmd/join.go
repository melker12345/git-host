package cmd

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/melker/git-host/internal/pairing"
	"github.com/spf13/cobra"
)

var joinCmd = &cobra.Command{
	Use:   "join <PIN>",
	Short: "Connect to a pairing session and register this device with a host",
	Long: `Connects to the host's pairing session using the 6-digit PIN displayed
on the owner's screen. Sends your SSH public key over an ECDH-secured channel,
then writes an SSH config entry so git commands just work.

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
			found, err := detectPubkey()
			if err != nil {
				return err
			}
			identityFile = found
		}

		// Warn if Ed25519 is selected — it may not work for SSH auth on older servers.
		pubKeyData, _ := os.ReadFile(identityFile)
		if strings.HasPrefix(string(pubKeyData), "ssh-ed25519") {
			fmt.Fprintln(os.Stderr, "⚠  Warning: Ed25519 key detected. If SSH auth fails after pairing,")
			fmt.Fprintln(os.Stderr, "   generate an ECDSA key: ssh-keygen -t ecdsa -b 256 -f ~/.ssh/id_ecdsa")
			fmt.Fprintln(os.Stderr, "   then re-run with --identity ~/.ssh/id_ecdsa.pub")
		}

		pairingPort := port + 1

		fmt.Printf("Using key:    %s\n", identityFile)
		fmt.Printf("Connecting to %s (pairing port %d)...\n", hostAddr, pairingPort)

		if err := pairing.FriendJoin(context.Background(), hostAddr, rawPIN, identityFile, pairingPort); err != nil {
			return err
		}

		// Derive private key path from public key path (strip .pub suffix).
		privateKeyPath := strings.TrimSuffix(identityFile, ".pub")

		// Write ~/.ssh/config entry.
		alias, err := writeSSHConfig(hostAddr, port, privateKeyPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "⚠  Could not write SSH config (%v). Add manually:\n", err)
			fmt.Fprintf(os.Stderr, "   Host %s\n     HostName %s\n     Port %d\n     IdentityFile %s\n     User git\n",
				alias, hostAddr, port, privateKeyPath)
		}

		fmt.Printf("\n✓  Approved by host owner.\n")
		fmt.Printf("   SSH alias:  %s\n", alias)
		fmt.Printf("\nClone a repo:\n  git clone ssh://%s/<repo>.git\n", alias)
		fmt.Printf("\nOr add as a remote:\n  git remote add phone ssh://%s/<repo>.git\n", alias)
		return nil
	},
}

// detectPubkey prefers ECDSA/RSA over Ed25519 (Ed25519 auth may not be supported).
func detectPubkey() (string, error) {
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
	return "", fmt.Errorf("no SSH public key found in ~/.ssh — run: ssh-keygen -t ecdsa -b 256 -f ~/.ssh/id_ecdsa")
}

// writeSSHConfig appends a Host entry to ~/.ssh/config if one doesn't already exist.
// Returns the alias used.
func writeSSHConfig(hostAddr string, sshPort int, identityFile string) (string, error) {
	home, _ := os.UserHomeDir()
	cfgPath := filepath.Join(home, ".ssh", "config")

	// Build a safe alias: "githost-192-168-1-154"
	safe := regexp.MustCompile(`[^a-zA-Z0-9]`).ReplaceAllString(hostAddr, "-")
	alias := "githost-" + safe

	// Check if alias already exists in config.
	existing, _ := os.ReadFile(cfgPath)
	scanner := bufio.NewScanner(strings.NewReader(string(existing)))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.EqualFold(line, "Host "+alias) {
			return alias, nil // already configured
		}
	}

	entry := fmt.Sprintf("\nHost %s\n  HostName %s\n  Port %d\n  IdentityFile %s\n  User git\n",
		alias, hostAddr, sshPort, identityFile)

	f, err := os.OpenFile(cfgPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return alias, err
	}
	defer f.Close()
	_, err = f.WriteString(entry)
	return alias, err
}

func init() {
	joinCmd.Flags().String("host", "", "hostname or IP of the git-host server (required)")
	joinCmd.Flags().String("identity", "", "path to SSH public key to register (default: auto-detect, prefers ECDSA)")
	rootCmd.AddCommand(joinCmd)
}
