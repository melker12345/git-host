// Package mdns advertises a git-host server on the local network, so that
// discovery on other machines can find it. It is the serving side of package
// discovery.
package mdns

import (
	"fmt"
	"os"
	"strings"

	"github.com/grandcat/zeroconf"
	"github.com/melker/git-host/internal/discovery"
)

// Server is a running mDNS advertisement.
type Server struct {
	inner *zeroconf.Server
}

// Shutdown withdraws the advertisement.
func (s *Server) Shutdown() {
	if s.inner != nil {
		s.inner.Shutdown()
	}
}

// Advertise announces a git-host server on port under discovery.ServiceType,
// using the short hostname as the instance name.
func Advertise(port int) (*Server, error) {
	hostname, err := os.Hostname()
	if err != nil {
		hostname = "git-host"
	}
	instance := strings.Split(hostname, ".")[0]

	inner, err := zeroconf.Register(
		instance,
		discovery.ServiceType,
		"local.",
		port,
		[]string{"version=1", fmt.Sprintf("port=%d", port)},
		nil,
	)
	if err != nil {
		return nil, err
	}
	return &Server{inner: inner}, nil
}
