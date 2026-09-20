package pairing_test

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/melker/git-host/internal/pairing"
)

// findFreePort returns a free TCP port on localhost.
func findFreePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("find free port: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

// startHost runs HostPair in the background and returns the PIN it published
// plus a channel carrying the resulting request.
func startHost(t *testing.T, port int) (pin <-chan string, result <-chan hostResult) {
	t.Helper()
	pinCh := make(chan string, 1)
	resCh := make(chan hostResult, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		req, err := pairing.HostPair(ctx, port, func(p string) { pinCh <- p })
		resCh <- hostResult{req: req, err: err}
	}()
	return pinCh, resCh
}

type hostResult struct {
	req *pairing.Request
	err error
}

func TestPairingRoundtripApproved(t *testing.T) {
	port := findFreePort(t)
	pinCh, resCh := startHost(t, port)

	pin := <-pinCh
	t.Logf("PIN: %s", pairing.FormatPIN(pin))

	// The host must approve before FriendJoin can return, so drive it from a
	// goroutine while the join is still in flight.
	go func() {
		res := <-resCh
		if res.err != nil {
			return
		}
		_ = res.req.Approve()
	}()

	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		"testdata/id_ed25519.pub", port, pairing.MatchPIN(pin))
	if err != nil {
		t.Fatalf("FriendJoin failed: %v", err)
	}
}

func TestPairingDenied(t *testing.T) {
	port := findFreePort(t)
	pinCh, resCh := startHost(t, port)
	pin := <-pinCh

	go func() {
		res := <-resCh
		if res.err != nil {
			return
		}
		_ = res.req.Deny()
	}()

	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		"testdata/id_ed25519.pub", port, pairing.MatchPIN(pin))
	if err == nil {
		t.Fatal("expected denial to be reported, got nil")
	}
	if !strings.Contains(err.Error(), "denied") {
		t.Errorf("expected a denial error, got: %v", err)
	}
}

func TestPINDerivation(t *testing.T) {
	testPub := make([]byte, 32)
	for i := range testPub {
		testPub[i] = byte(i)
	}
	pin := pairing.PINFromPubkey(testPub)
	if len(pin) != 6 {
		t.Errorf("expected 6-digit PIN, got %q (len=%d)", pin, len(pin))
	}
	// Derivation must be stable: the host and the client compute it separately
	// and compare, so any drift breaks pairing entirely.
	if again := pairing.PINFromPubkey(testPub); again != pin {
		t.Errorf("PIN derivation is not deterministic: %q then %q", pin, again)
	}
}

func TestMITMDetection(t *testing.T) {
	port := findFreePort(t)
	pinCh, _ := startHost(t, port)
	<-pinCh

	// A wrong PIN means the key on the wire is not the host's — abort.
	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		"testdata/id_ed25519.pub", port, pairing.MatchPIN("000000"))
	if err == nil {
		t.Fatal("expected PIN mismatch error, got nil")
	}
	t.Logf("correctly rejected wrong PIN: %v", err)
}

// TestConfirmAbortSendsNothing checks that refusing at the confirm step stops
// before the key is transmitted.
func TestConfirmAbortSendsNothing(t *testing.T) {
	port := findFreePort(t)
	pinCh, resCh := startHost(t, port)
	<-pinCh

	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		"testdata/id_ed25519.pub", port, func(string) error {
			return errAborted
		})
	if err != errAborted {
		t.Fatalf("expected the confirm error to propagate, got: %v", err)
	}

	res := <-resCh
	if res.err == nil {
		t.Error("host should not have completed a handshake after the client aborted")
	}
}

var errAborted = &abortErr{}

type abortErr struct{}

func (*abortErr) Error() string { return "aborted by user" }
