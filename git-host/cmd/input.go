package cmd

import (
	"bufio"
	"os"
	"strings"
)

// stdin is shared by every prompt in the process.
//
// A fresh bufio.Scanner per prompt cannot be used here: it reads ahead into its
// own buffer, so the first prompt swallows the answers to later ones and they
// see EOF. That is invisible on a terminal and breaks immediately on piped
// input.
var stdin = bufio.NewReader(os.Stdin)

// prompt writes msg and returns the trimmed line the user typed.
func prompt(msg string) string {
	os.Stdout.WriteString(msg)
	line, err := stdin.ReadString('\n')
	if err != nil && line == "" {
		return ""
	}
	return strings.TrimSpace(line)
}

// confirmed reports whether the answer to a [y/N] prompt was yes.
func confirmed(msg string) bool {
	answer := strings.ToLower(prompt(msg))
	return answer == "y" || answer == "yes"
}

// interactive reports whether stdin is a terminal.
func interactive() bool {
	info, err := os.Stdin.Stat()
	return err == nil && (info.Mode()&os.ModeCharDevice) != 0
}
