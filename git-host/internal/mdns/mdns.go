package mdns

import (
	"fmt"
	"os"
	"strings"

	"github.com/grandcat/zeroconf"
)

type Server struct {
	inner *zeroconf.Server
}

func (s *Server) Shutdown() {
	if s.inner != nil {
		s.inner.Shutdown()
	}
}

func Advertise(port int) (*Server, error) {
	hostname, err := os.Hostname()
	if err != nil {
		hostname = "git-host"
	}
	instance := strings.Split(hostname, ".")[0]

	inner, err := zeroconf.Register(
		instance,
		"_localgithub._tcp",
		"local.",
		port,
		[]string{fmt.Sprintf("version=1"), fmt.Sprintf("port=%d", port)},
		nil,
	)
	if err != nil {
		return nil, err
	}
	return &Server{inner: inner}, nil
}
