// Package discovery finds git-host servers on the local network via mDNS and
// remembers which of them are trusted.
//
// mDNS resolution is not reliable in practice — it fails transiently and may
// answer with IPv4 or IPv6 from one minute to the next. Everything here is
// built around that: discoveries are cached to disk, and a failed browse falls
// back to the last known address rather than giving up.
//
// Discovery alone proves nothing: anything on the LAN can advertise the
// service. Trust comes from pinning a device's SSH host key at pairing time;
// sync only talks to pinned devices and refuses a changed key.
package discovery

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/grandcat/zeroconf"
	gossh "golang.org/x/crypto/ssh"
)

// ServiceType is the mDNS service git-host servers advertise themselves as.
const ServiceType = "_localgithub._tcp"

// Device is a git-host server seen on the network.
type Device struct {
	// Name is the mDNS instance name, e.g. "SM-S938B".
	Name string `json:"name"`
	// Host is the mDNS hostname, e.g. "Android_C7JTWMEY.local". Preferred over
	// Addrs for remotes: it survives the device changing IP.
	Host string `json:"host"`
	Port int    `json:"port"`
	// Addrs are the literal IPs last seen, used when Host will not resolve.
	Addrs []string `json:"addrs,omitempty"`
	// LastSeen is when this device was last discovered, for reporting staleness.
	LastSeen time.Time `json:"last_seen"`
	// HostKey is the device's SSH host key in authorized_keys form, recorded
	// at pairing. Empty means the device has never been paired with.
	HostKey string `json:"host_key,omitempty"`
	// Cached reports that this came from disk rather than a live browse.
	Cached bool `json:"-"`
}

// Addr returns the best address to dial: the mDNS hostname if it resolves,
// otherwise the last known IP.
func (d Device) Addr() string {
	if d.Host != "" && resolves(d.Host) {
		return d.Host
	}
	if len(d.Addrs) > 0 {
		return d.Addrs[0]
	}
	return d.Host
}

// Pinned reports whether the device's SSH host key has been recorded.
func (d Device) Pinned() bool { return d.HostKey != "" }

// HostKeyFingerprint is the SHA256 fingerprint of the pinned key, or "".
func (d Device) HostKeyFingerprint() string {
	key, err := ParseHostKey(d.HostKey)
	if err != nil {
		return ""
	}
	return gossh.FingerprintSHA256(key)
}

// Names lists the hostnames and addresses this device may be dialled as.
func (d Device) Names() []string {
	var out []string
	if d.Host != "" {
		out = append(out, d.Host)
	}
	out = append(out, d.Addrs...)
	if d.Name != "" && d.Host == "" && len(d.Addrs) == 0 {
		out = append(out, d.Name)
	}
	return out
}

// resolves reports whether host currently resolves to any address.
func resolves(host string) bool {
	ips, err := net.LookupHost(host)
	return err == nil && len(ips) > 0
}

// Browse looks for git-host servers on the LAN for up to timeout.
func Browse(ctx context.Context, timeout time.Duration) ([]Device, error) {
	resolver, err := zeroconf.NewResolver(nil)
	if err != nil {
		return nil, fmt.Errorf("mDNS resolver: %w", err)
	}

	entries := make(chan *zeroconf.ServiceEntry)
	found := map[string]Device{}
	done := make(chan struct{})

	go func() {
		defer close(done)
		for e := range entries {
			d := Device{
				Name:     e.Instance,
				Host:     trimDot(e.HostName),
				Port:     e.Port,
				LastSeen: time.Now(),
			}
			for _, ip := range e.AddrIPv4 {
				d.Addrs = append(d.Addrs, ip.String())
			}
			for _, ip := range e.AddrIPv6 {
				d.Addrs = append(d.Addrs, ip.String())
			}
			found[d.Name] = d
		}
	}()

	browseCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if err := resolver.Browse(browseCtx, ServiceType, "local.", entries); err != nil {
		return nil, fmt.Errorf("mDNS browse: %w", err)
	}
	<-browseCtx.Done()
	<-done

	devices := make([]Device, 0, len(found))
	for _, d := range found {
		devices = append(devices, d)
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Name < devices[j].Name })
	return devices, nil
}

// trimDot strips the trailing dot of a fully qualified DNS name.
func trimDot(s string) string {
	for len(s) > 0 && s[len(s)-1] == '.' {
		s = s[:len(s)-1]
	}
	return s
}

// ErrAmbiguous is returned when more than one device could be meant.
var ErrAmbiguous = errors.New("several devices found")

// Find returns a single device, preferring a live mDNS browse and falling back
// to the cache when discovery turns up nothing.
//
// If want is non-empty, only a device whose name or host matches it is
// returned. Otherwise a device that has been paired with (pinned) wins over
// one that has not, and if that still leaves a choice the caller must name
// the device. Discovered devices are always merged into the cache, which
// keeps their pinned keys.
func Find(ctx context.Context, cfgDir, want string, timeout time.Duration) (Device, error) {
	devices, err := Browse(ctx, timeout)
	if err == nil && len(devices) > 0 {
		devices = saveCache(cfgDir, devices)
		if d, err := pick(devices, want); err == nil {
			return d, nil
		} else if errors.Is(err, ErrAmbiguous) {
			return Device{}, err
		}
	}

	cached := loadCache(cfgDir)
	if d, err := pick(cached, want); err == nil {
		d.Cached = true
		return d, nil
	} else if errors.Is(err, ErrAmbiguous) {
		return Device{}, err
	}

	if want != "" {
		return Device{}, fmt.Errorf("no device matching %q found on the network or in the cache", want)
	}
	return Device{}, fmt.Errorf("no git-host devices found on the network\n" +
		"  is the server running on the host device, and are you both on the same network?")
}

// pick chooses one device by the rules described on Find. It wraps
// ErrAmbiguous when the choice is the user's to make.
func pick(devices []Device, want string) (Device, error) {
	if len(devices) == 0 {
		return Device{}, errors.New("no devices")
	}
	if want != "" {
		for _, d := range devices {
			if d.Name == want || d.Host == want {
				return d, nil
			}
		}
		return Device{}, errors.New("no match")
	}
	if len(devices) == 1 {
		return devices[0], nil
	}
	var pinned []Device
	for _, d := range devices {
		if d.Pinned() {
			pinned = append(pinned, d)
		}
	}
	if len(pinned) == 1 {
		return pinned[0], nil
	}
	names := make([]string, 0, len(devices))
	for _, d := range devices {
		names = append(names, d.Name)
	}
	return Device{}, fmt.Errorf("%w: %s\n  pick one with --name, or set  device = <name>  in sync.conf",
		ErrAmbiguous, strings.Join(names, ", "))
}

// Lookup finds a cached device by instance name, or by hostname or address
// on the given port. Two apps on one phone share a hostname and differ only
// by port, so a port of 0 matches only when the name is unique to one entry.
func Lookup(cfgDir, hostOrName string, port int) (Device, bool) {
	needle := strings.ToLower(strings.TrimSuffix(hostOrName, "."))
	var matches []Device
	for _, d := range loadCache(cfgDir) {
		if strings.EqualFold(d.Name, needle) {
			d.Cached = true
			return d, true
		}
		if port != 0 && d.Port != port {
			continue
		}
		if strings.EqualFold(d.Host, needle) {
			matches = append(matches, d)
			continue
		}
		for _, a := range d.Addrs {
			if strings.EqualFold(a, needle) {
				matches = append(matches, d)
				break
			}
		}
	}
	if len(matches) == 1 {
		matches[0].Cached = true
		return matches[0], true
	}
	return Device{}, false
}

// Pin stores d, including its host key, in the cache.
func Pin(cfgDir string, d Device) error {
	if d.HostKey == "" {
		return errors.New("no host key to pin")
	}
	if d.LastSeen.IsZero() {
		d.LastSeen = time.Now()
	}
	merged := mergeCache(loadCache(cfgDir), []Device{d}, true)
	return writeCache(cfgDir, merged)
}

// Pinned returns every cached device with a recorded host key.
func Pinned(cfgDir string) []Device {
	var out []Device
	for _, d := range loadCache(cfgDir) {
		if d.Pinned() {
			out = append(out, d)
		}
	}
	return out
}

// cachePath is where discovered and pinned devices are remembered.
func cachePath(cfgDir string) string { return filepath.Join(cfgDir, "devices.json") }

// loadCache reads the device cache; a missing or corrupt file reads as empty.
func loadCache(cfgDir string) []Device {
	data, err := os.ReadFile(cachePath(cfgDir))
	if err != nil {
		return nil
	}
	var devices []Device
	if err := json.Unmarshal(data, &devices); err != nil {
		return nil
	}
	return devices
}

// saveCache merges newly discovered devices into the cache and returns the
// merged view of the discovered ones, so callers see their pinned keys.
// Failure to write is not fatal — the cache is an optimisation, never the
// source of truth for addresses.
func saveCache(cfgDir string, devices []Device) []Device {
	merged := mergeCache(loadCache(cfgDir), devices, false)
	_ = writeCache(cfgDir, merged)

	byName := map[string]Device{}
	for _, d := range merged {
		byName[d.Name] = d
	}
	out := make([]Device, 0, len(devices))
	for _, d := range devices {
		out = append(out, byName[d.Name])
	}
	return out
}

// mergeCache overlays fresh devices onto cached ones by name. A fresh entry
// without a host key keeps the cached key; with replaceKey it replaces it.
func mergeCache(cached, fresh []Device, replaceKey bool) []Device {
	merged := map[string]Device{}
	for _, d := range cached {
		merged[d.Name] = d
	}
	for _, d := range fresh {
		if old, ok := merged[d.Name]; ok && d.HostKey == "" && !replaceKey {
			d.HostKey = old.HostKey
		}
		merged[d.Name] = d
	}
	out := make([]Device, 0, len(merged))
	for _, d := range merged {
		out = append(out, d)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

func writeCache(cfgDir string, devices []Device) error {
	data, err := json.MarshalIndent(devices, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(cfgDir, 0700); err != nil {
		return err
	}
	return os.WriteFile(cachePath(cfgDir), data, 0600)
}

// Reachable reports whether a TCP connection to host:port succeeds.
func Reachable(host string, port int, timeout time.Duration) bool {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, fmt.Sprint(port)), timeout)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

// --- host keys ---------------------------------------------------------------

// errKeyCaptured aborts the SSH handshake in FetchHostKey once the host key
// has been seen; it never reaches callers.
var errKeyCaptured = errors.New("host key captured")

// FetchHostKey connects to host:port and returns the SSH host key the server
// presents. No authentication is attempted; the connection is dropped as
// soon as the key is known.
func FetchHostKey(host string, port int, timeout time.Duration) (gossh.PublicKey, error) {
	addr := net.JoinHostPort(host, fmt.Sprint(port))
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))

	var captured gossh.PublicKey
	cfg := &gossh.ClientConfig{
		User: "git",
		HostKeyCallback: func(_ string, _ net.Addr, key gossh.PublicKey) error {
			captured = key
			return errKeyCaptured
		},
		Timeout: timeout,
	}
	_, _, _, err = gossh.NewClientConn(conn, addr, cfg)
	if captured != nil {
		return captured, nil
	}
	return nil, fmt.Errorf("no host key from %s: %w", addr, err)
}

// MarshalHostKey renders a key in authorized_keys form ("type base64").
func MarshalHostKey(key gossh.PublicKey) string {
	return strings.TrimSpace(string(gossh.MarshalAuthorizedKey(key)))
}

// ParseHostKey is the inverse of MarshalHostKey.
func ParseHostKey(s string) (gossh.PublicKey, error) {
	key, _, _, _, err := gossh.ParseAuthorizedKey([]byte(s))
	return key, err
}

// WriteKnownHosts writes an OpenSSH known_hosts file listing every pinned
// device under each name it may be dialled as, and returns its path. Sync
// points ssh at it with strict checking, so an unpinned or changed host is
// refused rather than trusted on first use.
func WriteKnownHosts(cfgDir string) (string, error) {
	var sb strings.Builder
	sb.WriteString("# Written by git-host from devices.json. Edit that instead.\n")
	for _, d := range Pinned(cfgDir) {
		for _, name := range d.Names() {
			fmt.Fprintf(&sb, "[%s]:%d %s\n", strings.ToLower(name), d.Port, d.HostKey)
		}
	}
	path := filepath.Join(cfgDir, "known_hosts")
	if err := os.MkdirAll(cfgDir, 0700); err != nil {
		return "", err
	}
	if err := os.WriteFile(path, []byte(sb.String()), 0600); err != nil {
		return "", err
	}
	return path, nil
}
