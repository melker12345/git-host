# TODO

Status as of 2026-09-20. README.md describes what works.

## Next

- [ ] Promote the dev build to the real package. The app currently on the
      phone (`com.localgithub.githost`, installed 2026-07-10) was signed with
      a laptop debug key, so the signed release from this repo cannot
      install over it: uninstall it, install `app-release.apk`, pair, sync.
- [ ] Tag `v0.2.0` and add the keystore secrets to GitHub so the workflow
      ships signed APKs (see the header of `.github/workflows/build.yml`).
- [ ] Show the mDNS hostname instead of the IP in the phone's clone URLs, so
      copied remotes survive DHCP changes. Android gives no direct API for
      its own NSD hostname; resolve our own service to read it.
- [ ] Ed25519 client keys: either make MINA's EdDSA signature work on
      Android or drop the ed25519 signature factory so the failure is loud.
      The CLI refuses Ed25519 keys for now.
- [ ] Pull-to-refresh on the Repos screen (it refreshes on push events only).

## Ideas (see feature.md)

- Media sync from phone to PC (the July build had a first version).
- Multiple hosts in sync.conf, e.g. phone and laptop.
- Issues as markdown files next to the repository.

## Done

- QR-code pairing on both sides; network PIN pairing removed.
- Host-key pinning: sync and restore refuse unpinned or changed hosts.
- `git-host restore`, `git-host timer`, `git-host-ls` server command.
- GitHub dark theme, repository browser (README, commits, files, info).
- Friends and per-repo permissions removed; whitelist format shared.
- Apache MINA SSHD 2.12.1 (Terrapin fix).
- Backup rules fixed: host key and whitelist really are excluded from
  Android cloud backup now (the old rules used an invalid domain).
- Release signing, launcher icon, CI workflow.
