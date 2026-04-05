package pairing

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
	gossh "golang.org/x/crypto/ssh"
)

const SessionTimeout = 5 * time.Minute

// PINFromPubkey derives the 6-digit pairing PIN from an X25519 public key.
// PIN = SHA256(pub)[0:3] interpreted as big-endian uint24, mod 1_000_000.
func PINFromPubkey(pub []byte) string {
	h := sha256.Sum256(pub)
	n := (uint32(h[0])<<16 | uint32(h[1])<<8 | uint32(h[2])) % 1_000_000
	return fmt.Sprintf("%06d", n)
}

// FormatPIN renders a 6-digit PIN as "XXX XXX" for readability.
func FormatPIN(pin string) string {
	if len(pin) == 6 {
		return pin[:3] + " " + pin[3:]
	}
	return pin
}

// HostPair runs the owner side of the pairing protocol.
//
// onPIN is called immediately with the 6-digit PIN so the caller can display
// it before blocking to wait for a connection.
//
// Returns the peer's SSH public key once a device connects and completes the
// handshake. The caller is responsible for prompting the owner for approval
// and writing the key to the whitelist.
func HostPair(ctx context.Context, pairingPort int, onPIN func(string)) (gossh.PublicKey, error) {
	hostPriv := make([]byte, 32)
	if _, err := rand.Read(hostPriv); err != nil {
		return nil, fmt.Errorf("generate keypair: %w", err)
	}
	hostPub, err := curve25519.X25519(hostPriv, curve25519.Basepoint)
	if err != nil {
		return nil, fmt.Errorf("derive public key: %w", err)
	}

	onPIN(PINFromPubkey(hostPub))

	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", pairingPort))
	if err != nil {
		return nil, fmt.Errorf("listen on :%d: %w", pairingPort, err)
	}
	defer ln.Close()

	connCh := make(chan net.Conn, 1)
	acceptErrCh := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			acceptErrCh <- err
			return
		}
		connCh <- conn
	}()

	var conn net.Conn
	select {
	case conn = <-connCh:
	case err = <-acceptErrCh:
		return nil, fmt.Errorf("accept: %w", err)
	case <-ctx.Done():
		return nil, fmt.Errorf("pairing session expired")
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(SessionTimeout)) //nolint:errcheck

	// Step 1: receive friend's ephemeral X25519 pubkey
	friendPub, err := readMsg(conn)
	if err != nil {
		return nil, fmt.Errorf("read friend pubkey: %w", err)
	}
	if len(friendPub) != 32 {
		return nil, fmt.Errorf("invalid friend pubkey length: %d", len(friendPub))
	}

	// Step 2: ECDH
	sharedSecret, err := curve25519.X25519(hostPriv, friendPub)
	if err != nil {
		return nil, fmt.Errorf("X25519: %w", err)
	}

	// Step 3: send host's ephemeral pubkey so the friend can verify the PIN
	if err := writeMsg(conn, hostPub); err != nil {
		return nil, fmt.Errorf("send host pubkey: %w", err)
	}

	// Step 4: derive session key
	sessionKey, err := deriveSessionKey(sharedSecret)
	if err != nil {
		return nil, err
	}

	// Step 5: receive encrypted SSH pubkey
	encPayload, err := readMsg(conn)
	if err != nil {
		return nil, fmt.Errorf("read encrypted pubkey: %w", err)
	}
	pubkeyWire, err := aesgcmDecrypt(sessionKey, encPayload)
	if err != nil {
		return nil, fmt.Errorf("decrypt failed (possible MITM): %w", err)
	}

	pubkey, err := gossh.ParsePublicKey(pubkeyWire)
	if err != nil {
		return nil, fmt.Errorf("parse received pubkey: %w", err)
	}

	return pubkey, nil
}

// FriendJoin runs the friend side of the pairing protocol.
//
// Connects to the host at hostAddr:pairingPort, verifies the PIN, and sends
// the SSH public key from pubkeyFile over the ECDH-secured channel.
func FriendJoin(ctx context.Context, hostAddr, pin, pubkeyFile string, pairingPort int) error {
	data, err := os.ReadFile(pubkeyFile)
	if err != nil {
		return fmt.Errorf("read pubkey file %s: %w", pubkeyFile, err)
	}
	pubkey, _, _, _, err := gossh.ParseAuthorizedKey(data)
	if err != nil {
		return fmt.Errorf("parse pubkey: %w", err)
	}

	friendPriv := make([]byte, 32)
	if _, err := rand.Read(friendPriv); err != nil {
		return fmt.Errorf("generate keypair: %w", err)
	}
	friendPub, err := curve25519.X25519(friendPriv, curve25519.Basepoint)
	if err != nil {
		return fmt.Errorf("derive public key: %w", err)
	}

	addr := fmt.Sprintf("%s:%d", hostAddr, pairingPort)
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("connect to %s: %w", addr, err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(SessionTimeout)) //nolint:errcheck

	// Step 1: send our ephemeral pubkey
	if err := writeMsg(conn, friendPub); err != nil {
		return fmt.Errorf("send pubkey: %w", err)
	}

	// Step 2: receive host's ephemeral pubkey
	hostPub, err := readMsg(conn)
	if err != nil {
		return fmt.Errorf("read host pubkey: %w", err)
	}
	if len(hostPub) != 32 {
		return fmt.Errorf("invalid host pubkey length: %d", len(hostPub))
	}

	// Step 3: MITM check — verify the PIN matches the host's public key
	expectedPIN := PINFromPubkey(hostPub)
	if expectedPIN != pin {
		return fmt.Errorf("PIN mismatch — possible MITM attack\n  entered:  %s\n  received: %s",
			FormatPIN(pin), FormatPIN(expectedPIN))
	}

	// Step 4: ECDH + session key
	sharedSecret, err := curve25519.X25519(friendPriv, hostPub)
	if err != nil {
		return fmt.Errorf("X25519: %w", err)
	}
	sessionKey, err := deriveSessionKey(sharedSecret)
	if err != nil {
		return err
	}

	// Step 5: encrypt and send SSH pubkey
	encrypted, err := aesgcmEncrypt(sessionKey, pubkey.Marshal())
	if err != nil {
		return fmt.Errorf("encrypt: %w", err)
	}
	if err := writeMsg(conn, encrypted); err != nil {
		return fmt.Errorf("send encrypted pubkey: %w", err)
	}

	// Step 6: wait for approval signal (phone owner must tap Approve)
	fmt.Println("Waiting for approval on the host device...")
	resp, err := readMsg(conn)
	if err != nil {
		return fmt.Errorf("waiting for approval: %w (did the PIN expire or was it denied?)", err)
	}
	if len(resp) == 0 || resp[0] != 1 {
		return fmt.Errorf("request denied by host owner")
	}

	return nil
}

// --- wire helpers ---

func writeMsg(conn net.Conn, data []byte) error {
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(data)))
	if _, err := conn.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err := conn.Write(data)
	return err
}

func readMsg(conn net.Conn) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(lenBuf[:])
	if n > 1<<20 {
		return nil, fmt.Errorf("message too large: %d bytes", n)
	}
	buf := make([]byte, n)
	_, err := io.ReadFull(conn, buf)
	return buf, err
}

// --- crypto helpers ---

func deriveSessionKey(sharedSecret []byte) ([]byte, error) {
	r := hkdf.New(sha256.New, sharedSecret, nil, []byte("localgithub-pairing"))
	key := make([]byte, 32)
	if _, err := io.ReadFull(r, key); err != nil {
		return nil, fmt.Errorf("HKDF: %w", err)
	}
	return key, nil
}

func aesgcmEncrypt(key, plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return gcm.Seal(nonce, nonce, plaintext, nil), nil
}

func aesgcmDecrypt(key, ciphertext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(ciphertext) < gcm.NonceSize() {
		return nil, fmt.Errorf("ciphertext too short")
	}
	nonce, ct := ciphertext[:gcm.NonceSize()], ciphertext[gcm.NonceSize():]
	return gcm.Open(nil, nonce, ct, nil)
}
