# Feature Brainstorming

A running list of UX improvements and future feature ideas. These are not prioritised —
just captured so they don't get lost.

---

## Share Repo Address to a Device

**Problem:** When viewing a repo on the phone (e.g. `Test.git — ssh://git@192.168.1.154:2222/Test.git`),
the user has to manually copy the URL and type it on the other machine. This is
friction-heavy, especially on mobile.

**Idea:** Add a three-dot (⋮) context menu on each repo card in the Android UI with a
"Share clone URL" action. The share sheet shows all paired/trusted devices. Selecting a
device sends the URL to that machine and prints it in the terminal (or copies it to
clipboard) — zero manual typing needed.

**How it could work:**
- The phone pushes the URL over the existing pairing channel (mDNS + TCP, port 2223)
  to the target device's `git-host` daemon.
- `git-host` receives it and prints it to stdout / copies to clipboard.
- The user just runs `git clone <paste>` or it auto-clones if they confirm.

**Variants:**
- Also support sharing a ready-to-paste `git remote add phone ...` command.
- QR code fallback for devices that aren't running `git-host`.

---

## mDNS Hostname in Clone URLs

**Problem:** The app currently shows `ssh://git@<ip>:<port>/repo.git`. If the phone's
IP changes (DHCP), all saved remotes and bookmarks break.

**Idea:** Advertise and display the mDNS hostname (e.g. `samsung-galaxy.local`) instead
of the raw IP. SSH works fine with `.local` hostnames on the same LAN.

---

## Auto-Update SSH Config on IP Change

**Problem:** The `phone-githost` SSH config alias has a hardcoded IP. When DHCP
reassigns the phone a new address, `git push phone main` stops working until the user
edits `~/.ssh/config`.

**Idea:** `git-host` CLI watches mDNS announcements and automatically updates the
`HostName` line in `~/.ssh/config` for any known githost alias when the IP changes.

---

## `git-host clone <repo>` One-Liner

**Problem:** Users still need to know and type the full SSH URL for cloning.

**Idea:** `git-host clone Test` resolves the repo name against paired devices (via mDNS),
builds the URL, and runs `git clone` — no URL needed at all.

---

## Ed25519 Client Key Support

**Problem:** Ed25519 client authentication is currently disabled on the Android SSH
server because MINA SSHD's EdDSA registrar conflicts with Android JCE.

**Idea:** Investigate whether Android 11+ `java.security.KeyPairGenerator("Ed25519")`
can be plumbed into MINA SSHD without re-enabling the BC registrar, enabling Ed25519
auth which is the modern default on most machines.

---
