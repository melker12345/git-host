# git-host

Your phone as a private git server. Push every repository on your laptop to
it over SSH on the local network, browse them on the phone, and clone them
back when a machine dies.

Two parts, one protocol:

- **Android app** (`android/`): hosts bare repositories, serves them over SSH
  (Apache MINA SSHD + JGit), advertises itself on the LAN via mDNS, and shows
  each repository's README, commits and file tree.
- **Go CLI** (`git-host/`): `pair`, `sync`, `restore` and a systemd timer for
  the PC side. It can also host (`serve`) on a laptop or Raspberry Pi.

Access is a whitelist of SSH public keys. Every paired device has full read
and write access to every repository; there are no per-repo permissions and
no "friend" accounts.

## Quick start

On the PC:

```
cd git-host && go build -o ~/.local/bin/git-host .
git-host pair
```

`pair` prints a QR code holding this machine's SSH public key (an ECDSA key
is generated in `~/.ssh/githost_ecdsa` if none exists). On the phone, open
git-host, **Devices → + → Scan QR code**, check the fingerprint matches the
one printed next to the code, and approve. The key never crosses the
network. `pair` then finds the phone via mDNS, records its SSH host key, and
writes an SSH config entry.

Back up everything:

```
git-host sync
```

The first run writes `~/.config/git-host/sync.conf`; edit `[dirs]` to list
where your repositories live. A repo at `~/code/foo` is pushed as
`code-foo.git`, so same-named repos from different trees never collide.
`mode = mirror` makes the phone an exact copy; `additive` never deletes.

Run it on a schedule:

```
git-host timer install --every 30m
```

Get everything back on a new machine:

```
git-host pair
git-host restore --list
git-host restore
```

`restore` clones every repository the phone holds into the directory
`[dirs]` maps it from, with the phone as remote `phone`.

## Trust model

- **Keys, not passwords.** The phone authenticates devices by SSH public key
  against `files/config/whitelist`. Ed25519 keys do not work on Android
  (MINA's EdDSA provider is unavailable there); use ECDSA P-256 or RSA.
- **Pairing is out of band.** The public key travels inside a QR code you
  scan, and the phone shows its fingerprint before you approve.
- **Hosts are pinned.** `pair` records the phone's SSH host key in
  `devices.json`. `sync` and `restore` run ssh with strict checking against
  git-host's own `known_hosts`, so a device that merely advertises the
  service on the network cannot receive a push, and a changed key is
  refused. `--trust-new` pins a key explicitly.
- **LAN only.** The app binds to all interfaces but nothing forwards ports
  or traverses NAT. Both sides are Wi-Fi peers.

## Phone app

- **Repos**: cards for each repository with the last push (who, branch,
  files changed, size). Expand a card for README, the last 20 commits with
  change counts, a collapsible file tree, clone URL and a delete button.
- **Devices**: paired keys with fingerprint and optional expiry. Tap to
  remove.
- **Settings**: SSH port and an idle timeout that stops the server when
  nothing has connected for a while. The server also stops when the app is
  swiped away, so `sync` probes reachability and exits quietly when the
  phone is asleep.

Repositories are created on first push. Storage is the app's private
directory; uninstalling the app deletes them.

## Building

The Go CLI needs only Go:

```
cd git-host && go test ./... && go build .
```

The Android app needs JDK 17, Gradle 8.6 and SDK platform 34. A user-space
install of all three lives in `~/.local/opt` and `~/Android/Sdk`, set up by
`~/.local/opt/setup-android-toolchain.sh` (no root). Then:

```
android/dev-build.sh
```

builds the **debug** variant and installs it over adb. Debug builds are a
separate app, `git-host2` (package `com.localgithub.githost.dev`, port
2322, mDNS name `<model>-dev`), so they never replace the app that holds
your repositories.

Release builds are signed with the keystore named in
`android/keystore.properties` (gitignored). GitHub Actions
(`.github/workflows/build.yml`) builds both APKs and the CLI on every push,
and attaches them to a release on a `v*` tag.

## Layout

```
git-host/            Go CLI and server
  cmd/               pair, sync, restore, timer, serve, keys
  internal/discovery mDNS browse, device cache, host-key pinning
  internal/syncer    sync.conf, push, clone, restore mapping
  internal/server    SSH server that execs system git
  internal/whitelist authorized keys file
android/             Kotlin app (Jetpack Compose, MINA SSHD, JGit)
plan.md              original design notes, partly superseded by this file
feature.md           ideas backlog
TODO.md              what is left
```
