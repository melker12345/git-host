<h1 align="center">git-host</h1>

<p align="center">
  <strong>Your phone as a private git server.</strong><br>
  Back up every repository to your phone over the local network. No cloud, no account.
</p>

<p align="center">
  <a href="https://github.com/melker12345/git-host/actions/workflows/build.yml"><img src="https://github.com/melker12345/git-host/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/melker12345/git-host/releases/latest"><img src="https://img.shields.io/github/v/release/melker12345/git-host?include_prereleases&label=release" alt="release"></a>
  <img src="https://img.shields.io/badge/android-8.0%2B-3ddc84" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/go-1.21%2B-00add8" alt="Go 1.21+">
</p>

---

git-host keeps a copy of every repository on your computer on your phone,
over SSH on your local network. You can browse the copies on the phone, and
clone them all back when a machine dies. Nothing leaves your network.

```
 ┌──────────────┐    git push over SSH (LAN)   ┌──────────────────┐
 │   computer   │ ───────────────────────────▶ │      phone       │
 │  git-host    │                              │  bare repos      │
 │  sync        │ ◀─────────────────────────── │  SSH server      │
 └──────────────┘    git-host restore          │  repo browser    │
                                               └──────────────────┘
```

It has two parts:

- **Android app** (`android/`) stores bare repositories, serves them over
  SSH, announces itself with mDNS, and shows each repository's README,
  commits and files.
- **Command-line tool** (`git-host/`) pairs your computer with the phone and
  syncs or restores your repositories. It can also be the server itself, on
  a laptop or a Raspberry Pi.

## Install

**Phone:** download `git-host.apk` from the
[latest release](https://github.com/melker12345/git-host/releases/latest)
and install it, or [build it yourself](docs.md#building).

**Computer:** download the binary for your platform from the same release,
or build it (needs Go 1.21 or newer):

```sh
cd git-host
go build -o ~/.local/bin/git-host .
```

## Quick start

**1. Pair.** Open the app on the phone, then run:

```sh
git-host pair
```

A QR code with this computer's SSH key appears. On the phone go to
**Devices → + → Scan QR code**, check that the fingerprint matches the one in
the terminal, and tap **Approve**. The key never goes over the network.

**2. Tell it where your repositories are.** The first `git-host sync` creates
`~/.config/git-host/sync.conf`. List your project folders under `[dirs]`:

```ini
[dirs]
code     = ~/code/*
projects = ~/code/projects/*
```

A repository at `~/code/projects/foo` is stored on the phone as
`projects-foo.git`.

**3. Sync.**

```console
$ git-host sync
Syncing 7 repositories to SM-S938B (192.168.1.153:2222), mirror mode

  projects-browser                         ok
  projects-git-host                        ok
  ...
7 pushed, 0 skipped, 0 failed in 1.2s
```

**4. Make it automatic** (optional):

```sh
git-host timer install --every 30m
```

## Commands

| Command | What it does |
|---|---|
| `git-host sync` | Push every configured repository to the phone |
| `git-host sync --dry-run` | Show what would be pushed, and where |
| `git-host sync --only foo` | Push one repository |
| `git-host restore --list` | List the repositories the phone holds |
| `git-host restore` | Clone everything missing back onto this computer |
| `git-host timer status` | When the last sync succeeded, and the last error |
| `git-host serve` | Run the server on this machine instead of a phone |

If the phone can't be reached (the app is closed, or you're on another
network), `sync` says so and does nothing. If no sync has reached the phone
for 3 days, it fails instead, so a backup that has quietly stopped gets
noticed. Change the limit with `warn_after` in `sync.conf`.

## Good to know

- **Use an ECDSA or RSA key.** Ed25519 keys don't work with the Android
  server yet. `pair` creates a suitable key (`~/.ssh/githost_ecdsa`) if you
  don't have one.
- **Every paired device can read and write every repository.** Only pair
  machines you own.
- **Mirror mode copies deletions too.** By default the phone becomes an exact
  copy, so deleting a branch locally deletes it on the phone at the next
  sync. Set `mode = additive` in `sync.conf` to never delete anything.
- **Uninstalling the app deletes the repositories** stored on the phone.

## Documentation

[docs.md](docs.md) covers how it works in depth: configuration, the trust
model, the protocol, the phone app, building from source, and the roadmap.
