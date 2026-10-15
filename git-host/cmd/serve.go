package cmd

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/melker/git-host/internal/config"
	"github.com/melker/git-host/internal/mdns"
	"github.com/melker/git-host/internal/server"
	"github.com/melker/git-host/internal/whitelist"
	"github.com/spf13/cobra"
)

// serveCmd runs this machine as a git-host server, the same role the Android
// app plays: an SSH server accepting only whitelisted keys, advertised over
// mDNS so that sync and pair on other machines can find it.
var serveCmd = &cobra.Command{
	Use:   "serve",
	Short: "Start the git-host SSH server",
	RunE: func(cmd *cobra.Command, args []string) error {
		if err := os.MkdirAll(cfgDir, 0700); err != nil {
			return fmt.Errorf("create config dir: %w", err)
		}
		if err := os.MkdirAll(baseDir, 0755); err != nil {
			return fmt.Errorf("create base dir: %w", err)
		}

		hostKeyPath := filepath.Join(cfgDir, "host_key")
		signer, err := config.LoadOrGenerateHostKey(hostKeyPath)
		if err != nil {
			return fmt.Errorf("host key: %w", err)
		}

		wlPath := filepath.Join(cfgDir, "whitelist")
		wl, err := whitelist.Load(wlPath)
		if err != nil {
			return fmt.Errorf("whitelist: %w", err)
		}

		mdnsServer, err := mdns.Advertise(port)
		if err != nil {
			log.Printf("warn: mDNS unavailable: %v", err)
		} else {
			defer mdnsServer.Shutdown()
			log.Printf("mDNS: advertising _localgithub._tcp on port %d", port)
		}

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		quit := make(chan os.Signal, 1)
		signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
		go func() {
			<-quit
			log.Println("Shutting down...")
			cancel()
		}()

		log.Printf("base-dir: %s", baseDir)
		return server.Run(ctx, server.Config{
			Port:    port,
			BaseDir: baseDir,
			Signer:  signer,
			WL:      wl,
		})
	},
}

func init() {
	rootCmd.AddCommand(serveCmd)
}
