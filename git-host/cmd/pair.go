package cmd

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/melker/git-host/internal/discovery"
	"github.com/spf13/cobra"
	gossh "golang.org/x/crypto/ssh"
)

var pairCmd = &cobra.Command{
	Use:   "pair",
	Short: "Pair this machine with a git-host device by QR code",
	Long: `Shows this machine's SSH public key as a QR code. On the host device open
Devices -> + -> Scan QR code, check the fingerprint, and approve. The key
never crosses the network, so nothing on the LAN can intercept or replace it.

The host is then located on the network to record its SSH host key and
write an SSH config entry, so git commands work immediately. If the host
cannot be found the code is still shown; rerun with --host afterwards using
the address on the host's Repositories screen.

Examples:
  git-host pair                          # discover the host automatically
  git-host pair --host 192.168.1.153     # skip discovery
  git-host pair --name SM-S938B          # when several hosts are on the network`,
	Aliases: []string{"join"},
	RunE:    runPair,
}

// runPair shows the pairing QR code, then locates the host to pin its SSH
// host key and write an SSH config entry. Failing to find the host is not
// an error: the QR code has already done the part that matters.
func runPair(cmd *cobra.Command, args []string) error {
	hostAddr, _ := cmd.Flags().GetString("host")
	deviceName, _ := cmd.Flags().GetString("name")
	identityFile, _ := cmd.Flags().GetString("identity")

	pubkeyPath, err := selectPubkey(identityFile)
	if err != nil {
		return err
	}
	label, _ := cmd.Flags().GetString("label")
	if label == "" {
		label, _ = os.Hostname()
	}
	if label == "" {
		label = "pc"
	}

	uri, err := pairURI(pubkeyPath, label)
	if err != nil {
		return err
	}
	fmt.Printf("Using key:   %s\n", pubkeyPath)
	if fp, err := fingerprintOf(pubkeyPath); err == nil {
		fmt.Printf("Fingerprint: %s\n", fp)
	}
	fmt.Printf("Label:       %s\n\n", label)
	fmt.Println("On the host device: Devices -> + -> Scan QR code")
	fmt.Println()
	if err := writeQR(os.Stdout, uri); err != nil {
		return err
	}
	fmt.Println()
	fmt.Println("The phone shows the fingerprint above; approve it there.")
	fmt.Println()

	// Locate the host so its SSH host key can be pinned and git configured.
	d, err := locateHost(cmd, hostAddr, deviceName)
	if err != nil {
		fmt.Println(err)
		fmt.Println()
		fmt.Println("No SSH config was written. Once approved, run this with the address shown")
		fmt.Println("on the host's Repositories screen:")
		fmt.Println("  git-host pair --host <address> --port <port>")
		return nil
	}
	fmt.Printf("Host: %s (%s:%d)\n", d.Name, d.Addr(), d.Port)

	if err := pinHostKey(d); err != nil {
		fmt.Printf("Could not record the host's SSH key: %v\n", err)
		fmt.Println("Is the server running on the host? Start it, then run  git-host sync --trust-new")
	}

	identity := strings.TrimSuffix(pubkeyPath, ".pub")
	alias, cfgErr := writeSSHConfig(d.Addr(), d.Port, identity)
	if cfgErr != nil {
		fmt.Fprintf(os.Stderr, "\nwarning: could not write SSH config: %v\n", cfgErr)
		fmt.Fprintf(os.Stderr, "add this to ~/.ssh/config manually:\n%s\n",
			sshConfigBlock(alias, d.Addr(), d.Port, identity))
	}
	fmt.Printf("\nOnce approved, push a repo:\n  git remote add phone ssh://%s:%d/<repo>.git\n  git push -u phone main\n\n", alias, d.Port)
	fmt.Printf("Or back up everything at once:\n  git-host sync\n")
	return nil
}

// locateHost finds the device to configure, from --host or discovery.
func locateHost(cmd *cobra.Command, hostAddr, deviceName string) (discovery.Device, error) {
	if hostAddr != "" {
		want := 0
		if cmd.Flags().Changed("port") {
			want = port
		}
		d, ok := discovery.Lookup(cfgDir, hostAddr, want)
		if !ok {
			d = discovery.Device{Name: hostAddr, Host: hostAddr, Port: port}
		}
		if want != 0 {
			d.Port = want
		}
		return d, nil
	}
	fmt.Println("Looking for the host on the network...")
	d, err := discovery.Find(context.Background(), cfgDir, deviceName, 4*time.Second)
	if err != nil {
		return discovery.Device{}, err
	}
	if cmd.Flags().Changed("port") {
		d.Port = port
	}
	return d, nil
}

// pinHostKey records the SSH host key the device presents, so that sync can
// refuse anything else claiming to be it later.
func pinHostKey(d discovery.Device) error {
	key, err := discovery.FetchHostKey(d.Addr(), d.Port, 5*time.Second)
	if err != nil {
		return err
	}
	if d.HostKey != "" && d.HostKey != discovery.MarshalHostKey(key) {
		return fmt.Errorf("the host's SSH key CHANGED (was %s, now %s)\n"+
			"  if you did not reinstall the app on the host, something else is answering as it\n"+
			"  to accept the new key: git-host sync --trust-new",
			d.HostKeyFingerprint(), gossh.FingerprintSHA256(key))
	}
	d.HostKey = discovery.MarshalHostKey(key)
	if err := discovery.Pin(cfgDir, d); err != nil {
		return err
	}
	fmt.Printf("Host key:    %s (pinned)\n", gossh.FingerprintSHA256(key))
	return nil
}

// selectPubkey picks the SSH public key to hand the host.
//
// Ed25519 is rejected: the Android host cannot verify it ("EdDSA provider not
// supported"), and the failure would not surface until the first push. A
// dedicated ECDSA key is generated when nothing usable exists.
func selectPubkey(explicit string) (string, error) {
	if explicit != "" {
		if err := rejectEd25519(explicit); err != nil {
			return "", err
		}
		return explicit, nil
	}

	home, _ := os.UserHomeDir()
	preferred := filepath.Join(home, ".ssh", "githost_ecdsa")
	candidates := []string{
		preferred + ".pub",
		filepath.Join(home, ".ssh", "id_ecdsa.pub"),
		filepath.Join(home, ".ssh", "id_rsa.pub"),
	}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			if rejectEd25519(p) == nil {
				return p, nil
			}
		}
	}

	fmt.Printf("No usable key found — generating %s\n", preferred)
	fmt.Println("(a dedicated key, so pairing never exposes your personal SSH identity)")
	if err := os.MkdirAll(filepath.Join(home, ".ssh"), 0700); err != nil {
		return "", fmt.Errorf("create ~/.ssh: %w", err)
	}
	hostname, _ := os.Hostname()
	out, err := exec.Command("ssh-keygen", "-t", "ecdsa", "-b", "256",
		"-f", preferred, "-N", "", "-C", "githost-"+hostname).CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("ssh-keygen failed: %w\n%s", err, out)
	}
	return preferred + ".pub", nil
}

// fingerprintOf returns the SHA256 fingerprint of the public key at path, so
// the owner can compare it with what the host's approval screen shows.
func fingerprintOf(pubkeyPath string) (string, error) {
	data, err := os.ReadFile(pubkeyPath)
	if err != nil {
		return "", err
	}
	key, _, _, _, err := gossh.ParseAuthorizedKey(data)
	if err != nil {
		return "", err
	}
	return gossh.FingerprintSHA256(key), nil
}

// rejectEd25519 returns an error if the key at path is an Ed25519 key.
func rejectEd25519(pubkeyPath string) error {
	data, err := os.ReadFile(pubkeyPath)
	if err != nil {
		return fmt.Errorf("read %s: %w", pubkeyPath, err)
	}
	key, _, _, _, err := gossh.ParseAuthorizedKey(data)
	if err != nil {
		return fmt.Errorf("parse %s: %w", pubkeyPath, err)
	}
	if key.Type() == gossh.KeyAlgoED25519 {
		return fmt.Errorf("%s is an Ed25519 key, which the host cannot authenticate\n"+
			"  the Android server reports \"EdDSA provider not supported\", and pairing would\n"+
			"  appear to succeed while every push failed with \"Permission denied\"\n"+
			"  use an ECDSA key instead:\n"+
			"    ssh-keygen -t ecdsa -b 256 -f ~/.ssh/githost_ecdsa -N \"\"", pubkeyPath)
	}
	return nil
}

// sshConfigBlock renders the ~/.ssh/config entry pair writes for a host.
//
// Host keys are pinned per device in git-host's own known_hosts, which sync
// uses. Plain git/ssh use gets accept-new: recorded on first use, refused if
// it ever changes.
func sshConfigBlock(alias, hostAddr string, sshPort int, identityFile string) string {
	return fmt.Sprintf(
		"\nHost %s\n  HostName %s\n  Port %d\n  User git\n  IdentityFile %s\n  IdentitiesOnly yes\n  StrictHostKeyChecking accept-new\n",
		alias, hostAddr, sshPort, identityFile,
	)
}

// writeSSHConfig adds a Host block for hostAddr, using it as the alias so the
// printed clone URLs work verbatim. An mDNS hostname is preferred over a literal
// IP by the caller, so the entry survives the host changing address.
func writeSSHConfig(hostAddr string, sshPort int, identityFile string) (string, error) {
	home, _ := os.UserHomeDir()
	cfgPath := filepath.Join(home, ".ssh", "config")
	alias := hostAddr

	existing, _ := os.ReadFile(cfgPath)
	scanner := bufio.NewScanner(strings.NewReader(string(existing)))
	for scanner.Scan() {
		if strings.TrimSpace(scanner.Text()) == "Host "+alias {
			return alias, nil // already configured — leave the user's entry alone
		}
	}

	f, err := os.OpenFile(cfgPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return alias, err
	}
	defer f.Close()
	_, err = f.WriteString(sshConfigBlock(alias, hostAddr, sshPort, identityFile))
	return alias, err
}

func init() {
	pairCmd.Flags().String("host", "", "address of the host device (default: discover via mDNS)")
	pairCmd.Flags().String("name", "", "only pair with the device of this name")
	pairCmd.Flags().String("identity", "", "SSH public key to show (default: auto-detect or generate)")
	pairCmd.Flags().String("label", "", "name for this machine on the host (default: hostname)")
	rootCmd.AddCommand(pairCmd)
}
