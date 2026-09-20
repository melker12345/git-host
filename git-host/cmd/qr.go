package cmd

import (
	"encoding/base64"
	"fmt"
	"io"
	"net/url"
	"os"

	qrcode "github.com/skip2/go-qrcode"
	gossh "golang.org/x/crypto/ssh"
)

// pairURI is what the phone scans: the public key to authorize and a label
// for it. The key never crosses the network, so nothing on the LAN can
// intercept or replace it, and no discovery is needed.
//
//	githost://pair?key=<base64 wire key>&label=<name>
func pairURI(pubkeyPath, label string) (string, error) {
	data, err := os.ReadFile(pubkeyPath)
	if err != nil {
		return "", fmt.Errorf("read %s: %w", pubkeyPath, err)
	}
	key, _, _, _, err := gossh.ParseAuthorizedKey(data)
	if err != nil {
		return "", fmt.Errorf("parse %s: %w", pubkeyPath, err)
	}
	q := url.Values{}
	q.Set("key", base64.StdEncoding.EncodeToString(key.Marshal()))
	q.Set("label", label)
	return "githost://pair?" + q.Encode(), nil
}

// writeQR renders text as a QR code using half-height block characters.
// Light modules are drawn as white blocks and dark ones left as the
// terminal background, so on the usual dark terminal it reads as a normal
// dark-on-light code with a quiet zone around it.
func writeQR(w io.Writer, text string) error {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return fmt.Errorf("build QR code: %w", err)
	}
	_, err = io.WriteString(w, q.ToSmallString(false))
	return err
}
