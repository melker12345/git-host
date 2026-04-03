package pairing_test

import (
	"context"
	"fmt"
	"net"
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

func TestPairingRoundtrip(t *testing.T) {
	pairingPort := findFreePort(t)

	// Host side
	var gotPIN string
	hostErrCh := make(chan error, 1)
	hostPubkeyCh := make(chan string, 1)

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		pubkey, err := pairing.HostPair(ctx, pairingPort, func(pin string) {
			gotPIN = pin
		})
		if err != nil {
			hostErrCh <- err
			return
		}
		hostPubkeyCh <- fmt.Sprintf("%T", pubkey)
		hostErrCh <- nil
	}()

	// Wait for host to start listening
	time.Sleep(100 * time.Millisecond)

	if gotPIN == "" {
		t.Fatal("PIN was not generated")
	}
	t.Logf("PIN: %s (%s)", pairing.FormatPIN(gotPIN), gotPIN)

	// Friend side
	ctx := context.Background()
	joinErr := pairing.FriendJoin(ctx, "127.0.0.1", gotPIN, "testdata/id_ed25519.pub", pairingPort)

	if joinErr != nil {
		t.Fatalf("FriendJoin failed: %v", joinErr)
	}

	if err := <-hostErrCh; err != nil {
		t.Fatalf("HostPair failed: %v", err)
	}
	t.Logf("HostPair returned pubkey type: %s", <-hostPubkeyCh)
}

func TestPINDerivation(t *testing.T) {
	// PIN must be exactly 6 digits
	testPub := make([]byte, 32)
	for i := range testPub {
		testPub[i] = byte(i)
	}
	pin := pairing.PINFromPubkey(testPub)
	if len(pin) != 6 {
		t.Errorf("expected 6-digit PIN, got %q (len=%d)", pin, len(pin))
	}
	t.Logf("PIN for test key: %s", pairing.FormatPIN(pin))
}

func TestMITMDetection(t *testing.T) {
	pairingPort := findFreePort(t)

	hostErrCh := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err := pairing.HostPair(ctx, pairingPort, func(pin string) {})
		hostErrCh <- err
	}()

	time.Sleep(100 * time.Millisecond)

	// Friend connects with WRONG PIN — should fail
	ctx := context.Background()
	err := pairing.FriendJoin(ctx, "127.0.0.1", "000000", "testdata/id_ed25519.pub", pairingPort)
	if err == nil {
		t.Fatal("expected PIN mismatch error, got nil")
	}
	t.Logf("correctly rejected wrong PIN: %v", err)
}
