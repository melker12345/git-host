// Package config manages the SSH host key of a git-host server run with
// git-host serve. (Sync settings live in package syncer.)
package config

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"

	gossh "golang.org/x/crypto/ssh"
)

// LoadOrGenerateHostKey returns the server's SSH host key from path,
// generating and saving a new Ed25519 key on first run. The key must persist:
// clients pin it, and a new one would look like an impostor to them.
func LoadOrGenerateHostKey(path string) (gossh.Signer, error) {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return generateHostKey(path)
	}
	return loadHostKey(path)
}

// generateHostKey creates an Ed25519 key and writes it to path as PKCS#8 PEM.
func generateHostKey(path string) (gossh.Signer, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}

	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}

	privBytes, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, err
	}

	block := &pem.Block{Type: "PRIVATE KEY", Bytes: privBytes}
	if err := os.WriteFile(path, pem.EncodeToMemory(block), 0600); err != nil {
		return nil, err
	}

	return gossh.NewSignerFromKey(priv)
}

// loadHostKey reads a PKCS#8 PEM private key written by generateHostKey.
func loadHostKey(path string) (gossh.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("no PEM block found in %s", path)
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	return gossh.NewSignerFromKey(key)
}
