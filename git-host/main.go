// Command git-host backs up local git repositories to a git-host device (the
// LocalGitHub Android app) over SSH, and can also act as a git-host server
// itself. See the cmd package for the subcommands.
package main

import (
	"fmt"
	"os"

	"github.com/melker/git-host/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
