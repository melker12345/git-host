package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/melker/git-host/internal/pairing"
	"github.com/spf13/cobra"
)

var joinCmd = &cobra.Command{
	Use:   "join <PIN>",
	Short: "Connect to a pairing session and register this device with a host",
	Long: `Connects to the host's pairing session using the 6-digit PIN displayed
on the owner's screen. Sends your SSH public key over an ECDH-secured channel.

Example:
  git-host join 482951 --host melkers-phone.local`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		rawPIN := strings.ReplaceAll(args[0], " ", "")
		if len(rawPIN) != 6 {
			return fmt.Errorf("PIN must be 6 digits, got %q", args[0])
		}

		hostAddr, _ := cmd.Flags().GetString("host")
		if hostAddr == "" {
			return fmt.Errorf("--host is required (e.g. --host melkers-phone.local)")
		}

		identityFile, _ := cmd.Flags().GetString("identity")
		if identityFile == "" {
			found, err := detectPubkey()
			if err != nil {
				return err
			}
			identityFile = found
		}

		pairingPort := port + 1

		fmt.Printf("Using key:    %s\n", identityFile)
		fmt.Printf("Connecting to %s (pairing port %d)...\n", hostAddr, pairingPort)

		if err := pairing.FriendJoin(context.Background(), hostAddr, rawPIN, identityFile, pairingPort); err != nil {
			return err
		}

		fmt.Println("\nApproved by host owner.")
		fmt.Printf("You can now clone with:\n  git clone ssh://git@%s:%d/<repo>.git\n", hostAddr, port)
		return nil
	},
}

// detectPubkey returns the path to the first suitable SSH public key found.
func detectPubkey() (string, error) {
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(home, ".ssh", "id_ed25519.pub"),
		filepath.Join(home, ".ssh", "id_rsa.pub"),
		filepath.Join(home, ".ssh", "id_ecdsa.pub"),
	}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}
	return "", fmt.Errorf("no SSH public key found in ~/.ssh — use --identity to specify one")
}

func init() {
	joinCmd.Flags().String("host", "", "hostname or IP of the git-host server (required)")
	joinCmd.Flags().String("identity", "", "path to SSH public key to register (default: auto-detect)")
	rootCmd.AddCommand(joinCmd)
}
