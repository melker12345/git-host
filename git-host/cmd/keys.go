package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

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

func init() {
	keysAddCmd.Flags().String("label", "", "label for this key, no spaces (required)")
	_ = keysAddCmd.MarkFlagRequired("label")
	keysAddCmd.Flags().Int("expires-days", 0, "days until expiry (0 = permanent)")

	keysCmd.AddCommand(keysAddCmd)
	keysCmd.AddCommand(keysListCmd)
	keysCmd.AddCommand(keysRemoveCmd)
	rootCmd.AddCommand(keysCmd)
}
