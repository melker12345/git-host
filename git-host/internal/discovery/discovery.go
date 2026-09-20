// Package discovery finds git-host servers on the local network via mDNS.
//
// mDNS resolution is not reliable in practice — it fails transiently and may
// answer with IPv4 or IPv6 from one minute to the next. Everything here is
// built around that: discoveries are cached to disk, and a failed browse falls
// back to the last known address rather than giving up.
package discovery

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/grandcat/zeroconf"
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

func trimDot(s string) string {
	for len(s) > 0 && s[len(s)-1] == '.' {
		s = s[:len(s)-1]
	}
	return s
}

// Find returns a single device, preferring a live mDNS browse and falling back
// to the cache when discovery turns up nothing.
//
// If want is non-empty, only a device whose name or host matches it is
// returned. Discovered devices are always written back to the cache.
func Find(ctx context.Context, cfgDir, want string, timeout time.Duration) (Device, error) {
	devices, err := Browse(ctx, timeout)
	if err == nil && len(devices) > 0 {
		saveCache(cfgDir, devices)
		if d, ok := pick(devices, want); ok {
			return d, nil
		}
	}

	cached := loadCache(cfgDir)
	if d, ok := pick(cached, want); ok {
		d.Cached = true
		return d, nil
	}

	if want != "" {
		return Device{}, fmt.Errorf("no device matching %q found on the network or in the cache", want)
	}
	return Device{}, fmt.Errorf("no git-host devices found on the network\n" +
		"  is the server running on the host device, and are you both on the same network?")
}

func pick(devices []Device, want string) (Device, bool) {
	if len(devices) == 0 {
		return Device{}, false
	}
	if want == "" {
		return devices[0], true
	}
	for _, d := range devices {
		if d.Name == want || d.Host == want {
			return d, true
		}
	}
	return Device{}, false
}

func cachePath(cfgDir string) string { return filepath.Join(cfgDir, "devices.json") }

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

// saveCache merges newly discovered devices into the cache. Failure to write is
// not fatal — the cache is an optimisation, never the source of truth.
func saveCache(cfgDir string, devices []Device) {
	merged := map[string]Device{}
	for _, d := range loadCache(cfgDir) {
		merged[d.Name] = d
	}
	for _, d := range devices {
		merged[d.Name] = d
	}

	out := make([]Device, 0, len(merged))
	for _, d := range merged {
		out = append(out, d)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })

	data, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		return
	}
	if err := os.MkdirAll(cfgDir, 0700); err != nil {
		return
	}
	_ = os.WriteFile(cachePath(cfgDir), data, 0600)
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

// NOTE: there is deliberately no "wait until this port opens" helper here.
// The pairing listener accepts exactly one connection and then closes, so a
// TCP probe consumes the pairing session: the host treats the probe as the
// peer, fails on EOF, and shuts the listener down. Callers that need to wait
// for pairing to open must retry the real handshake instead.
