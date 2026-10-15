package cmd

import (
	"context"
	"fmt"
	"time"

	"github.com/melker/git-host/internal/discovery"
	"github.com/melker/git-host/internal/syncer"
	"github.com/spf13/cobra"
)

// resolveHost decides which device sync and restore talk to.
//
// With host = auto it browses mDNS (falling back to the last known address);
// with an explicit host it uses that, enriched from the device cache when the
// address is known there. A discovered device also supplies the port it
// advertises, unless --port was given explicitly. The chosen port is written
// back into cfg.
//
// Only a device whose SSH host key was pinned at pairing is accepted:
// anything on the LAN can advertise the service, and a mirror push would
// hand it every repository. --trust-new pins the key the host presents now.
func resolveHost(cmd *cobra.Command, cfg *syncer.Config, trustNew bool) (discovery.Device, error) {
	var d discovery.Device
	if cfg.Host != "" && cfg.Host != "auto" {
		var ok bool
		if d, ok = discovery.Lookup(cfgDir, cfg.Host, cfg.Port); !ok {
			d = discovery.Device{Name: cfg.Host, Host: cfg.Host}
		}
		d.Port = cfg.Port
	} else {
		var err error
		d, err = discovery.Find(context.Background(), cfgDir, cfg.Device, 5*time.Second)
		if err != nil {
			return d, err
		}
		if d.Cached {
			fmt.Printf("Discovery found nothing; using last known address for %s\n", d.Name)
		}
		if d.Port != 0 && !cmd.Flags().Changed("port") {
			cfg.Port = d.Port
		}
		d.Port = cfg.Port
	}

	if !d.Pinned() {
		if !trustNew {
			return d, fmt.Errorf("%s has no pinned SSH host key, so it cannot be trusted with a push\n"+
				"  pair with it first:  git-host pair\n"+
				"  or trust the key it presents right now:  --trust-new", d.Name)
		}
		key, err := discovery.FetchHostKey(d.Addr(), d.Port, 5*time.Second)
		if err != nil {
			return d, fmt.Errorf("fetch host key from %s: %w", d.Name, err)
		}
		d.HostKey = discovery.MarshalHostKey(key)
		if err := discovery.Pin(cfgDir, d); err != nil {
			return d, err
		}
		fmt.Printf("Pinned host key for %s: %s\n", d.Name, d.HostKeyFingerprint())
	}
	return d, nil
}

// describeDevice names a device for output: "SM-S938B (192.168.1.153:2222)",
// or just the address when the device has no name of its own.
func describeDevice(d discovery.Device, addr string) string {
	where := fmt.Sprintf("%s:%d", addr, d.Port)
	if d.Name == "" || d.Name == addr {
		return where
	}
	return fmt.Sprintf("%s (%s)", d.Name, where)
}
