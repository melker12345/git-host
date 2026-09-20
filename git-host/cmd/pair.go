package cmd

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/melker/git-host/internal/discovery"
	"github.com/melker/git-host/internal/pairing"
	"github.com/spf13/cobra"
	gossh "golang.org/x/crypto/ssh"
)

var pairCmd = &cobra.Command{
	Use:   "pair",
	Short: "Pair this machine with a git-host server",
	Long: `Finds a git-host server on the local network, sends this machine's SSH
public key over an encrypted channel, and writes an SSH config entry — so git
commands work immediately afterwards.

On the host device, open the pairing screen ("Add device" -> "Generate Pairing
Code"). This command waits for that, then shows the same 6-digit code so you can
check the two screens match. Nothing to type in.

With --qr no network pairing happens at all: this prints a QR code holding
this machine's public key, and the host device scans it ("Add device" ->
"Scan QR code") and approves it on screen. Use this when discovery is
unreliable, or when the two devices are not on the same network yet.

Examples:
  git-host pair                          # discover the host automatically
  git-host pair --qr                     # show a QR code for the phone to scan
  git-host pair --host 192.168.1.153     # skip discovery
  git-host pair --pin 482951             # type the code instead of comparing it`,
	// join was the old name, and took the PIN as a positional argument.
	Aliases: []string{"join"},
	Args:    cobra.MaximumNArgs(1),
	RunE:    runPair,
}

func runPair(cmd *cobra.Command, args []string) error {
	hostAddr, _ := cmd.Flags().GetString("host")
	deviceName, _ := cmd.Flags().GetString("name")
	typedPIN, _ := cmd.Flags().GetString("pin")
	if len(args) == 1 && typedPIN == "" {
		typedPIN = args[0] // git-host join <PIN>
	}
	identityFile, _ := cmd.Flags().GetString("identity")
	timeout, _ := cmd.Flags().GetDuration("timeout")
	useQR, _ := cmd.Flags().GetBool("qr")

	if useQR {
		return runQRPair(cmd, hostAddr, deviceName, identityFile)
	}

	if typedPIN != "" {
		typedPIN = strings.ReplaceAll(typedPIN, " ", "")
		if len(typedPIN) != 6 {
			return fmt.Errorf("PIN must be 6 digits, got %q", typedPIN)
		}
	} else if !interactive() {
		return fmt.Errorf("not running on a terminal, so the code cannot be compared visually\n" +
			"  pass the code explicitly:  git-host pair --pin <code>")
	}

	// Locate the host device.
	sshPort := port
	if hostAddr == "" {
		fmt.Println("Looking for devices...")
		d, err := discovery.Find(context.Background(), cfgDir, deviceName, 5*time.Second)
		if err != nil {
			return err
		}
		hostAddr = d.Addr()
		sshPort = d.Port
		if cmd.Flags().Changed("port") {
			sshPort = port
		}
		where := "found"
		if d.Cached {
			where = "using last known address for"
		}
		fmt.Printf("%s %s (%s)\n", where, d.Name, hostAddr)
	}

	pubkeyPath, err := selectPubkey(identityFile)
	if err != nil {
		return err
	}
	fmt.Printf("Using key:  %s\n", pubkeyPath)
	if fp, err := fingerprintOf(pubkeyPath); err == nil {
		fmt.Printf("Fingerprint: %s\n", fp)
		fmt.Println("(the host shows this fingerprint on its approval screen)")
	}

	pairingPort := sshPort + 1
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	fmt.Printf("\nOn the host device: Add device -> Generate Pairing Code\n")
	fmt.Printf("Waiting (up to %s)...\n", timeout)

	if err := joinWhenReady(ctx, hostAddr, pubkeyPath, pairingPort, confirmPIN(typedPIN)); err != nil {
		return err
	}

	alias, cfgErr := writeSSHConfig(hostAddr, sshPort, strings.TrimSuffix(pubkeyPath, ".pub"))
	if cfgErr != nil {
		fmt.Fprintf(os.Stderr, "\nwarning: could not write SSH config: %v\n", cfgErr)
		fmt.Fprintf(os.Stderr, "add this to ~/.ssh/config manually:\n%s\n",
			sshConfigBlock(alias, hostAddr, sshPort, strings.TrimSuffix(pubkeyPath, ".pub")))
	}

	fmt.Printf("\nPaired.\n\n")
	fmt.Printf("Push a repo:\n  git remote add phone ssh://%s:%d/<repo>.git\n  git push -u phone main\n\n", alias, sshPort)
	fmt.Printf("Or back up everything at once:\n  git-host sync\n")
	return nil
}

// runQRPair shows this machine's key as a QR code for the host to scan.
//
// The host address is only needed to write the SSH config entry, so
// discovery failing is not fatal here: the code is shown either way, and the
// user is told how to add the host by hand.
func runQRPair(cmd *cobra.Command, hostAddr, deviceName, identityFile string) error {
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
	fmt.Printf("Using key:  %s\n", pubkeyPath)
	if fp, err := fingerprintOf(pubkeyPath); err == nil {
		fmt.Printf("Fingerprint: %s\n", fp)
	}
	fmt.Printf("Label:      %s\n\n", label)
	fmt.Println("On the host device: Add device -> Scan QR code")
	fmt.Println()
	if err := writeQR(os.Stdout, uri); err != nil {
		return err
	}
	fmt.Println()
	fmt.Println("The phone shows the fingerprint above; approve it there.")

	// Best effort: know where the host is so git works right away.
	sshPort := port
	if hostAddr == "" {
		d, err := discovery.Find(context.Background(), cfgDir, deviceName, 3*time.Second)
		if err == nil {
			hostAddr = d.Addr()
			sshPort = d.Port
			if cmd.Flags().Changed("port") {
				sshPort = port
			}
		}
	}
	if hostAddr == "" {
		fmt.Println()
		fmt.Println("Could not find the host on the network, so no SSH config was written.")
		fmt.Println("Once approved, run this with the address shown on the phone's Repositories screen:")
		fmt.Printf("  git-host pair --qr --host <address> --port <port>\n")
		return nil
	}

	alias, cfgErr := writeSSHConfig(hostAddr, sshPort, strings.TrimSuffix(pubkeyPath, ".pub"))
	if cfgErr != nil {
		fmt.Fprintf(os.Stderr, "\nwarning: could not write SSH config: %v\n", cfgErr)
		fmt.Fprintf(os.Stderr, "add this to ~/.ssh/config manually:\n%s\n",
			sshConfigBlock(alias, hostAddr, sshPort, strings.TrimSuffix(pubkeyPath, ".pub")))
	}
	fmt.Printf("\nOnce approved, push a repo:\n  git remote add phone ssh://%s:%d/<repo>.git\n  git push -u phone main\n\n", alias, sshPort)
	fmt.Printf("Or back up everything at once:\n  git-host sync\n")
	return nil
}

// joinWhenReady retries the handshake until the host opens its pairing port.
//
// The port cannot be probed first: the host's listener accepts a single
// connection and then closes, so a probe would be taken for the peer and would
// end the pairing session before it began. Retrying the real handshake is the
// only safe way to wait, since the first connection that succeeds is the one
// that pairs.
func joinWhenReady(ctx context.Context, hostAddr, pubkeyPath string, pairingPort int, confirm func(string) error) error {
	for {
		err := pairing.FriendJoin(ctx, hostAddr, pubkeyPath, pairingPort, confirm)
		if err == nil || !isDialFailure(err) {
			return err
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("timed out waiting for the host to open pairing on port %d\n"+
				"  on the host device: Add device -> Generate Pairing Code", pairingPort)
		case <-time.After(time.Second):
		}
	}
}

// isDialFailure reports whether err is the connection itself failing, as
// opposed to the handshake being rejected. Only the former is worth retrying.
func isDialFailure(err error) bool {
	var opErr *net.OpError
	return errors.As(err, &opErr) && opErr.Op == "dial"
}

// confirmPIN returns the MITM check for FriendJoin. With a typed PIN it
// compares directly; otherwise it shows the derived code for the user to check
// against the host's screen, which is the same guarantee without the typing.
func confirmPIN(typed string) func(string) error {
	if typed != "" {
		return pairing.MatchPIN(typed)
	}
	return func(derived string) error {
		fmt.Printf("\n  The host device should be showing:   %s\n\n", pairing.FormatPIN(derived))
		answer := strings.ToLower(prompt("Does that match? [Y/n]: "))
		if answer != "" && answer != "y" && answer != "yes" {
			return fmt.Errorf("codes did not match — aborted\n" +
				"  if the host is showing a different code, something else answered on the network")
		}
		fmt.Println("\nWaiting for approval on the host device...")
		return nil
	}
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

func sshConfigBlock(alias, hostAddr string, sshPort int, identityFile string) string {
	// accept-new records the host key on first use and refuses if it ever
	// changes, which is what catches something impersonating the host later.
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
	pairCmd.Flags().String("pin", "", "type the code instead of comparing it on screen")
	pairCmd.Flags().String("identity", "", "SSH public key to send (default: auto-detect or generate)")
	pairCmd.Flags().Duration("timeout", 5*time.Minute, "how long to wait for the host to open pairing")
	pairCmd.Flags().Bool("qr", false, "show a QR code for the host device to scan instead of pairing over the network")
	pairCmd.Flags().String("label", "", "name for this machine on the host (default: hostname)")
	rootCmd.AddCommand(pairCmd)
}
