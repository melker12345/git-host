package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/melker/git-host/internal/pairing"
	"github.com/melker/git-host/internal/whitelist"
	"github.com/spf13/cobra"
	gossh "golang.org/x/crypto/ssh"
)

var keysCmd = &cobra.Command{
	Use:   "keys",
	Short: "Manage authorized public keys",
}

var keysAddCmd = &cobra.Command{
	Use:   "add <pubkey-file>",
	Short: "Add a public key to the whitelist",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		label, _ := cmd.Flags().GetString("label")
		expiryDays, _ := cmd.Flags().GetInt("expires-days")

		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil {
			return err
		}

		var expiry int64
		if expiryDays > 0 {
			expiry = time.Now().Add(time.Duration(expiryDays) * 24 * time.Hour).Unix()
		}

		fp, err := wl.Add(args[0], label, expiry)
		if err != nil {
			return err
		}
		fmt.Printf("Added: %s  label=%q\n", fp, label)
		return nil
	},
}

var keysListCmd = &cobra.Command{
	Use:   "list",
	Short: "List authorized keys",
	RunE: func(cmd *cobra.Command, args []string) error {
		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil && !os.IsNotExist(err) {
			return err
		}

		entries, err := wl.List()
		if err != nil {
			return err
		}
		if len(entries) == 0 {
			fmt.Println("No authorized keys.")
			return nil
		}

		fmt.Printf("%-50s  %-20s  %s\n", "FINGERPRINT", "LABEL", "EXPIRY")
		fmt.Printf("%-50s  %-20s  %s\n", "---", "---", "---")
		for _, e := range entries {
			fp := gossh.FingerprintSHA256(e.PublicKey)
			expiry := "permanent"
			if e.Expiry != 0 {
				t := time.Unix(e.Expiry, 0)
				if time.Now().After(t) {
					expiry = fmt.Sprintf("EXPIRED (%s)", t.Format("2006-01-02"))
				} else {
					expiry = t.Format("2006-01-02")
				}
			}
			fmt.Printf("%-50s  %-20s  %s\n", fp, e.Label, expiry)
		}
		return nil
	},
}

var keysRemoveCmd = &cobra.Command{
	Use:   "remove <fingerprint>",
	Short: "Remove a key from the whitelist by fingerprint",
	Args:  cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil {
			return err
		}
		if err := wl.Remove(args[0]); err != nil {
			return err
		}
		fmt.Printf("Removed: %s\n", args[0])
		return nil
	},
}

// keysPairCmd is the host side of pairing: this machine shows a code and waits
// for another machine to run `git-host pair` against it. The client-side
// counterpart is the top-level `pair` command.
var keysPairCmd = &cobra.Command{
	Use:   "pair",
	Short: "Add a key by pairing, showing a code for the other machine",
	Long: `Shows a 6-digit pairing code and waits for another machine to connect.

On that machine, run:
  git-host pair --host <this machine's address>

Its key is shown for approval before anything is written to the whitelist.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil {
			return err
		}

		expiryDays, _ := cmd.Flags().GetInt("expires-days")
		pairingPort := port + 1

		ctx, cancel := context.WithTimeout(context.Background(), pairing.SessionTimeout)
		defer cancel()

		req, err := pairing.HostPair(ctx, pairingPort, func(pin string) {
			fmt.Printf("\nPairing code: %s\n", pairing.FormatPIN(pin))
			fmt.Printf("Expires in %s. The other machine should show the same code.\n\n", pairing.SessionTimeout)
			fmt.Println("Waiting for connection...")
		})
		if err != nil {
			return fmt.Errorf("pairing failed: %w", err)
		}

		fmt.Printf("\nDevice connected.\nFingerprint: %s\n\n", gossh.FingerprintSHA256(req.PublicKey))

		label := prompt("Label for this device (no spaces): ")
		approved := label != ""
		if !approved {
			fmt.Println("Aborted (no label provided).")
		} else {
			approved = confirmed("Approve? [y/N]: ")
		}

		if !approved {
			if err := req.Deny(); err != nil {
				return fmt.Errorf("send denial: %w", err)
			}
			fmt.Println("Denied.")
			return nil
		}

		var expiry int64
		if expiryDays > 0 {
			expiry = time.Now().Add(time.Duration(expiryDays) * 24 * time.Hour).Unix()
		}
		if _, err := wl.AddKey(req.PublicKey, label, expiry); err != nil {
			// Tell the peer it did not work rather than leaving it hanging.
			_ = req.Deny()
			return fmt.Errorf("write whitelist: %w", err)
		}
		if err := req.Approve(); err != nil {
			return fmt.Errorf("key was added, but telling the peer failed: %w", err)
		}

		fmt.Printf("\nApproved. Key added with label %q.\n", label)
		return nil
	},
}

func init() {
	keysPairCmd.Flags().Int("expires-days", 0, "days until access expires (0 = permanent)")
	keysAddCmd.Flags().String("label", "", "label for this key, no spaces (required)")
	_ = keysAddCmd.MarkFlagRequired("label")
	keysAddCmd.Flags().Int("expires-days", 0, "days until expiry (0 = permanent)")

	keysCmd.AddCommand(keysAddCmd)
	keysCmd.AddCommand(keysListCmd)
	keysCmd.AddCommand(keysRemoveCmd)
	keysCmd.AddCommand(keysPairCmd)
	rootCmd.AddCommand(keysCmd)
}
