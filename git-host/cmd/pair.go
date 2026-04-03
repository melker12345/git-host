package cmd

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/melker/git-host/internal/pairing"
	"github.com/melker/git-host/internal/whitelist"
	"github.com/spf13/cobra"
	gossh "golang.org/x/crypto/ssh"
)

var pairCmd = &cobra.Command{
	Use:   "pair",
	Short: "Start a pairing session to add an external device",
	Long: `Generates a 6-digit pairing code and waits for a device to connect.

The remote user runs:
  git-host join <CODE> --host <your-hostname>

After they connect, you will be shown their key fingerprint and asked to approve.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil {
			return err
		}

		expiryDays, _ := cmd.Flags().GetInt("expires-days")
		pairingPort := port + 1

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()

		pubkey, err := pairing.HostPair(ctx, pairingPort, func(pin string) {
			fmt.Printf("\nPairing code: %s\n", pairing.FormatPIN(pin))
			fmt.Printf("Expires in 5 minutes. Share this code with the device you want to add.\n\n")
			fmt.Println("Waiting for connection...")
		})
		if err != nil {
			return fmt.Errorf("pairing failed: %w", err)
		}

		fp := gossh.FingerprintSHA256(pubkey)
		fmt.Printf("\nDevice connected.\nFingerprint: %s\n\n", fp)

		label := promptLabel()
		if label == "" {
			fmt.Println("Aborted (no label provided).")
			return nil
		}

		fmt.Print("Approve? [y/N]: ")
		var answer string
		fmt.Scanln(&answer)
		if strings.ToLower(strings.TrimSpace(answer)) != "y" {
			fmt.Println("Denied.")
			return nil
		}

		var expiry int64
		if expiryDays > 0 {
			expiry = time.Now().Add(time.Duration(expiryDays) * 24 * time.Hour).Unix()
		}

		if _, err := wl.AddKey(pubkey, label, expiry); err != nil {
			return fmt.Errorf("write whitelist: %w", err)
		}

		fmt.Printf("\nApproved. Key added with label %q.\n", label)
		fmt.Printf("They can now clone with:\n  git clone ssh://git@<your-host>:%d/<repo>.git\n", port)
		return nil
	},
}

func promptLabel() string {
	fmt.Print("Label for this device (no spaces): ")
	scanner := bufio.NewScanner(os.Stdin)
	if scanner.Scan() {
		return strings.TrimSpace(scanner.Text())
	}
	return ""
}

func init() {
	pairCmd.Flags().Int("expires-days", 0, "days until access expires (0 = permanent)")
	rootCmd.AddCommand(pairCmd)
}
