package cmd

import (
	"context"
	"encoding/base64"
	"net"
	"net/url"
	"testing"
	"time"

	"github.com/melker/git-host/internal/pairing"
	gossh "golang.org/x/crypto/ssh"
)

const testPubkey = "../internal/pairing/testdata/id_ed25519.pub"

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

// A refused connection must be classified as retryable, so that waiting for the
// host to open pairing does not abort immediately.
func TestIsDialFailureOnClosedPort(t *testing.T) {
	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		testPubkey, freePort(t), func(string) error { return nil })
	if err == nil {
		t.Fatal("expected connecting to a closed port to fail")
	}
	if !isDialFailure(err) {
		t.Errorf("a refused connection should be retryable, got: %v", err)
	}
}

// A rejected handshake must NOT be retried — retrying would re-run the pairing
// against a host that already said no.
func TestIsDialFailureIgnoresHandshakeErrors(t *testing.T) {
	port := freePort(t)
	listening := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		// onPIN fires once the listener is up, so this is a safe start signal.
		req, err := pairing.HostPair(ctx, port, func(string) { close(listening) })
		if err == nil {
			_ = req.Deny()
		}
	}()
	<-listening

	err := pairing.FriendJoin(context.Background(), "127.0.0.1",
		testPubkey, port, func(string) error { return nil })
	<-done
	if err == nil {
		t.Fatal("expected the denial to surface")
	}
	if isDialFailure(err) {
		t.Errorf("a denial must not be treated as retryable, got: %v", err)
	}
}

// Regression test: the pairing listener accepts one connection and then closes,
// so the client must wait by retrying the real handshake. An earlier version
// probed the port with a throwaway TCP connection, which the host consumed as
// the pairing peer — killing the session before the client ever connected.
func TestJoinWhenReadyWaitsForHostToOpen(t *testing.T) {
	port := freePort(t)

	hostUp := make(chan struct{})
	go func() {
		// The host opens late, mimicking the user tapping "Generate Pairing
		// Code" after the command has already started.
		time.Sleep(1500 * time.Millisecond)
		close(hostUp)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		req, err := pairing.HostPair(ctx, port, func(string) {})
		if err != nil {
			return
		}
		_ = req.Approve()
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	start := time.Now()
	err := joinWhenReady(ctx, "127.0.0.1", testPubkey, port, func(string) error { return nil })
	if err != nil {
		t.Fatalf("joinWhenReady should have waited for the host: %v", err)
	}
	select {
	case <-hostUp:
	default:
		t.Fatal("paired before the host was even listening")
	}
	if elapsed := time.Since(start); elapsed < time.Second {
		t.Errorf("returned in %s — it cannot have waited for the late host", elapsed)
	}
}

// The wait must give up when the host never opens, rather than hanging forever.
func TestJoinWhenReadyGivesUp(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	err := joinWhenReady(ctx, "127.0.0.1", testPubkey, freePort(t), func(string) error { return nil })
	if err == nil {
		t.Fatal("expected a timeout when the host never opens pairing")
	}
}

func TestPairURI(t *testing.T) {
	uri, err := pairURI("../internal/pairing/testdata/id_ed25519.pub", "my laptop")
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(uri)
	if err != nil {
		t.Fatal(err)
	}
	if u.Scheme != "githost" || u.Host != "pair" {
		t.Errorf("unexpected uri %s", uri)
	}
	if u.Query().Get("label") != "my laptop" {
		t.Errorf("label not round-tripped: %s", uri)
	}
	raw, err := base64.StdEncoding.DecodeString(u.Query().Get("key"))
	if err != nil {
		t.Fatalf("key is not base64: %v", err)
	}
	if _, err := gossh.ParsePublicKey(raw); err != nil {
		t.Fatalf("key is not an SSH wire key: %v", err)
	}
}
