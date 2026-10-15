# git-host documentation

How git-host works, how to configure it, and where it's going. For
installation and a quick start, see [README.md](README.md).

- [How it fits together](#how-it-fits-together)
- [The command-line tool](#the-command-line-tool)
- [Configuration: sync.conf](#configuration-syncconf)
- [Files git-host writes](#files-git-host-writes)
- [Trust model](#trust-model)
- [Protocol](#protocol)
- [The phone app](#the-phone-app)
- [Building](#building)
- [Code layout](#code-layout)
- [Roadmap](#roadmap)

## How it fits together

```
 computer                                     phone (git-host app)
┌───────────────────────┐   mDNS browse     ┌────────────────────────┐
│ git-host sync         │ ───────────────►  │ _localgithub._tcp      │
│  - reads sync.conf    │                   │                        │
│  - finds the phone    │   ssh (pinned     │ SSH server (MINA SSHD) │
│  - git push --mirror  │ ───host key)───►  │  - whitelist of keys   │
│    per repository     │                   │  - JGit receive/upload │
└───────────────────────┘                   │  - bare repos on disk  │
                                            └────────────────────────┘
```

The phone runs an SSH server that speaks the normal git protocol, so plain
`git clone`, `git push` and `git fetch` work against it. The command-line
tool adds what plain git lacks: pairing, finding the phone on the network,
pushing many repositories in one go, and restoring them onto a new machine.

The host can also be a computer: `git-host serve` runs the same server on
Linux or macOS, backed by the system `git`.

## The command-line tool

| Command | Purpose |
|---|---|
| `pair` | Show this computer's SSH key as a QR code for the phone to scan, then find the phone, pin its host key, and add an `~/.ssh/config` entry |
| `sync` | Push every repository matched by `sync.conf` to the phone |
| `restore` | Clone the phone's repositories that are missing locally |
| `timer install / status / remove` | Run `sync` on a schedule with a systemd user timer |
| `serve` | Run the SSH git server on this machine |
| `keys add / list / remove` | Manage the whitelist used by `serve` |

Every command takes `--config-dir` (default `~/.config/git-host`) and
`--help`.

### sync

`sync` expands the `[dirs]` globs, keeps the directories that are git
repositories, and runs one `git push` per repository:

- **mirror** mode: `git push --mirror`. The phone becomes an exact copy:
  branches and tags deleted locally are deleted on the phone.
- **additive** mode: `git push --force` with explicit `refs/heads/*` and
  `refs/tags/*` refspecs. Adds and updates refs, never deletes.

Repositories with no commits are skipped. `--only <name>` limits the run to
one repository; it accepts the name on the phone (`code-foo`) or the folder
name (`foo`). `--dry-run` prints what would be pushed, and to which device,
without pushing anything.

**Which phone.** With `host = auto`, sync browses mDNS for five seconds and
only accepts a device whose host key was pinned at pairing. If the browse
finds nothing it falls back to the last known address. When several pinned
devices are seen, it refuses to guess: set `device = <name>` in `sync.conf`.
The chosen device is always printed:

```
Syncing 7 repositories to SM-S938B (192.168.1.153:2222), mirror mode
```

**When things go wrong.** Each repository's result is printed, and git's
error output is trimmed to the useful part. The exit status reports the
outcome:

| Situation | Output | Exit status |
|---|---|---|
| All pushes succeeded | summary line | 0 |
| Some pushes failed | `FAILED` with the reason under each, then an error naming them | 1 |
| Phone not reachable, last success recent | "not reachable — nothing synced", and when the last success was | 0 |
| Phone not reachable for longer than `warn_after` | the above, plus "no backup has reached … since …" | 1 |
| No pinned device, or several to choose from | how to fix it | 1 |

The phone app stops its server when idle, so "not reachable" is normal for a
timer and isn't treated as an error until it has lasted longer than
`warn_after`. Every run is recorded in `state.json`, and
`git-host timer status` shows the last success and the last error. git-host
never shows desktop notifications; it only prints.

### restore

`restore` asks the phone for its repository list (the `git-host-ls` command),
maps each name back to a directory through `[dirs]` (`work-foo` goes to
the directory the `work` entry points at, as `foo`), and clones whatever
isn't there yet. Cloned repositories get the phone as a remote named
`phone`. Other remotes, such as GitHub, are not restored. `--list` only
lists, and `--into <dir>` clones everything into one directory instead.

### timer

`timer install --every 30m` writes `git-host-sync.service` and
`git-host-sync.timer` to `~/.config/systemd/user/` and enables the timer.
Its output goes to the journal: `journalctl --user -u git-host-sync`.

## Configuration: sync.conf

`~/.config/git-host/sync.conf`, created by the first `sync`:

```ini
host       = auto       # address of the phone, or "auto" to find it via mDNS
device     = SM-S938B   # optional: only use the device with this name
port       = 2222       # SSH port; a discovered device supplies its own
identity   = ~/.ssh/githost_ecdsa   # private key to authenticate with
mode       = mirror     # or additive
warn_after = 3d         # fail when no sync has reached the phone for this long
                        # (e.g. 3d, 12h; 0 turns the check off)

# <prefix> = <glob>. A repository at ~/code/foo is pushed as code-foo.git.
[dirs]
code     = ~/code/*
projects = ~/code/projects/*
```

The prefix keeps same-named repositories from different folders apart. If
two repositories would still get the same name, sync refuses to run rather
than let one overwrite the other. A glob only matches one level: `~/code/*`
does not include `~/code/projects/foo`, which needs its own entry. When
nothing matches, sync explains what each pattern found.

## Files git-host writes

On the computer, in `~/.config/git-host/`:

| File | Contents |
|---|---|
| `sync.conf` | The configuration above |
| `devices.json` | Devices seen via mDNS: name, hostname, port, last addresses, and the pinned host key |
| `known_hosts` | Generated from `devices.json`; the only host keys sync and restore accept |
| `state.json` | First run, last run, last successful sync and the device it reached, last error |
| `whitelist`, `host_key` | Used by `serve` only |

`pair` also adds a `Host` block to `~/.ssh/config`, so plain
`git clone ssh://<phone>/<repo>.git` works, and creates
`~/.ssh/githost_ecdsa` if no usable key exists.

On the phone, inside the app's private storage:

| Path | Contents |
|---|---|
| `files/repos/*.git` | Bare repositories |
| `files/config/whitelist` | Paired device keys |
| `files/config/` host key | The server's SSH host key |

The host key and whitelist are excluded from Android cloud backup.

## Trust model

- **Keys, not passwords.** The server authenticates clients by SSH public
  key against its whitelist. Every whitelisted device can read and write
  every repository. There are no per-repository permissions.
- **Pairing happens out of band.** The client's public key goes to the phone
  inside a QR code, never over the network. The phone shows the key's
  fingerprint before you approve it.
- **Hosts are pinned.** `pair` records the phone's SSH host key. `sync` and
  `restore` run ssh with `StrictHostKeyChecking=yes` against git-host's own
  `known_hosts`, so a device that merely advertises the service cannot
  receive a push, and a changed host key is refused. `--trust-new` pins a
  key explicitly.
- **LAN only.** Nothing forwards ports or crosses NAT. Both sides have to be
  on the same network.
- **Key types.** Clients must use ECDSA or RSA keys. MINA SSHD's Ed25519
  support doesn't work on Android, so the CLI refuses Ed25519 keys rather
  than letting a push fail later.

## Protocol

**Discovery.** Hosts advertise the mDNS service `_localgithub._tcp`. The
instance name is the device model (`SM-S938B`), with `-dev` appended for
debug builds.

**SSH commands.** The server accepts exactly three exec commands:

| Command | Behaviour |
|---|---|
| `git-upload-pack '<repo>.git'` | Clone or fetch. A missing repository is an error. |
| `git-receive-pack '<repo>.git'` | Push. A missing repository is created as a bare repo with `HEAD → main`. Non-fast-forward updates and deletions are allowed. |
| `git-host-ls` | Print the repositories held, one per line (phone only, used by `restore`) |

Repository paths are resolved inside the storage directory. Paths containing
`..` or escaping it are rejected.

**Whitelist format**, shared by the app and `serve`. One entry per line:

```
<base64 SSH wire-format public key> <label> [expiry unix timestamp]
```

Extra tokens are ignored, so files from older versions still load. Expired
entries are kept but rejected at login.

**Pairing link**, encoded in the QR code that `pair` shows:

```
githost://pair?key=<base64 SSH wire-format key>&label=<computer name>
```

## The phone app

- **Repos** lists each repository with its last push: which device pushed,
  how many commits, files added/changed/deleted, and the size. Expand a card
  for the README, the last 20 commits, the file tree, the clone URL, and a
  delete button.
- **Devices** lists paired keys with their fingerprints. **+** opens the QR
  scanner.
- **Settings** has the SSH port and an idle timeout (never, 5 min, 30 min,
  1 h, 4 h) after which the server stops itself.

**Notifications.** A push that changes something posts a notification:

```
laptop pushed to code-foo
main · 3 commits · +2 ~5 −1 files · 12.4 KB
```

Pushes that change nothing are silent. Most pushes in a sync are
like that. Notifications are per repository, so repeated pushes replace
each other, and they're grouped.

The server runs as a foreground service while the app is open. It stops on
the idle timeout or when the app is swiped away.

## Building

### Command-line tool

```sh
cd git-host
go test ./...
go build -o ~/.local/bin/git-host .
```

### Android app

The app needs JDK 17, Gradle 8.6 and Android SDK platform 34. A user-space
install of all three can live in `~/.local/opt` and `~/Android/Sdk` (set up
by `~/.local/opt/setup-android-toolchain.sh`, no root needed).

```sh
android/dev-build.sh               # debug build; installs over adb if a phone is attached
cd android
gradle testDebugUnitTest           # JVM unit tests (Markdown parser, push summaries)
gradle assembleRelease             # signed release build
```

Debug builds are a separate app, **git-host2** (package
`com.localgithub.githost.dev`, port 2322, mDNS name `<model>-dev`), so they
install next to the real app and never touch the repositories it holds.

Release builds are signed with the keystore named in
`android/keystore.properties` (gitignored). GitHub Actions
(`.github/workflows/build.yml`) builds the CLI and both APKs on every push,
and attaches them to a release when a `v*` tag is pushed.

## Code layout

```
git-host/                    command-line tool and computer-side server (Go)
  cmd/                       one file per command; host.go picks the device
  internal/syncer            sync.conf, pushes, restore mapping, state.json
  internal/discovery         mDNS browse, device cache, host-key pinning
  internal/server            SSH server that runs the system git
  internal/whitelist         the authorized-keys file
android/app/src/main/kotlin/com/localgithub/githost/
  ssh/                       SSH server, git commands, push summaries
  service/                   foreground service, notifications, last-push record
  whitelist/, pairing/       paired keys and QR pairing links
  mdns/                      service advertisement
  ui/                        Compose screens, Markdown renderer, theme
android/app/src/test/        JVM unit tests
```

## Roadmap

### Next

- **Move to the real app.** The phone's main git-host app is an old July
  build signed with a laptop debug key, so a release build can't install
  over it. Uninstall it, install `app-release.apk`, then pair and sync
  again. Uninstalling deletes its repositories, so sync to the debug app
  first.
- **Ship releases.** Tag `v0.2.0` and add the keystore secrets to GitHub so
  CI publishes signed APKs (see the header of `build.yml`).
- **Never lose a commit on the phone.** Mirror mode lets a local mistake
  (`git branch -D`, a bad force-push) wipe the same commits on the phone at
  the next sync. Before the server deletes or force-moves a ref, it should
  keep the old tip under `refs/git-host/trash/<date>/…` and clean those up
  after a while.
- **Spot stale and renamed repositories.** Names come from folder paths, so
  moving a project leaves the old copy behind on the phone. A
  `git-host status` command could list repositories on the phone with no
  local counterpart, recognise renames by their first commit, and offer to
  prune or rename them.
- **Hostnames in clone URLs.** Show the phone's mDNS hostname instead of its
  IP in the app, so copied remotes survive DHCP changes. Android has no
  direct API for its own NSD hostname; resolving our own service would
  reveal it.
- **Ed25519 keys.** Make MINA's EdDSA signatures work on Android (for
  example through Android's own `KeyPairGenerator("Ed25519")`), or drop the
  ed25519 signature factory so the failure is loud. Most machines default
  to Ed25519.
- **`restore` against `git-host serve`.** The computer-side server doesn't
  answer `git-host-ls` yet, so `restore` only works with the phone.
- **Pull to refresh** on the Repos screen. It currently refreshes on push
  events only.

### Ideas

- **Restore other remotes.** Push a small metadata ref with each
  repository's remote URLs during sync, so `restore` can re-add GitHub and
  friends.
- **Faster syncs.** Push several repositories in parallel. Sequential is
  fine for ten, slow over Wi-Fi for fifty.
- **Several hosts.** Sync to the phone and a laptop from one `sync.conf`.
- **`git-host clone <name>`.** Resolve a repository name against paired
  devices and clone it, with no URL to type.
- **Share a clone URL to a device.** A "share" action on a repository card
  that sends the URL (or a ready `git remote add` line) to a paired
  computer.
- **Keep `~/.ssh/config` current.** Update the `HostName` of a known phone
  when mDNS shows it at a new address.
- **Media sync.** Copy photos from the phone to a computer. The July build
  had a first version with its own protocol.
- **Issues as Markdown files** stored next to a repository.
- **Guided setup.** A first-run guide in the app and the CLI covering keys,
  pairing and the first sync, with commands to copy, so a new user never
  has to read this file.

### Considered and dropped

- **Friends and per-repository permissions.** Earlier designs had a second
  class of "friend" devices with per-repo read/write access, mandatory
  expiry and WAN access. Dropped in favour of one trust level: your own
  devices, on your own network. Sharing with other people is what GitHub
  is for.
- **PIN pairing over the network** (an ECDH channel on port 2223 with a
  6-digit code). Replaced by QR pairing, where the key never travels over
  the network.
