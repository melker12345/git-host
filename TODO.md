# TODO — LocalGitHub

## Current status (2026-04-05)

**Pairing protocol fix: DONE ✅**
TCP connection now stays open until owner taps Approve/Deny. CLI waits for response before printing success.

**ReposScreen pairing button: REMOVED ✅**
The stray "+ friend" button on the Repos tab is gone.

**Entry management: DONE ✅**
Tap any entry in Devices/Friends to open edit dialog (permissions, repos, remove).

**BC provider registered: DONE ✅**
BouncyCastle added as Security provider in GitHostApp — should enable Ed25519 SSH auth.

**SSH config alias fix: DONE ✅**
`git-host join` now uses raw IP as alias (always resolvable), auto-generates ECDSA key if none found.

**Idle timeout feature: DONE ✅**
Server auto-stops after configurable idle time (5min-4hr). Does not stop while app is foreground or sessions active. Notification on stop.

**Code quality fixes: DONE ✅**
- Replaced deprecated `getRunningServices()` with SharedPreferences flag
- Replaced raw `Thread {}` with `CoroutineScope(Dispatchers.IO + SupervisorJob())`
- Replaced deprecated `LocalBroadcastManager` with shared `StateFlow` (`EventBus`)

---

## Known remaining issues to investigate

- [ ] Ed25519 auth — BC provider fix applied but not yet confirmed working; ECDSA preferred for now
- [ ] Friend expiry UI — expiry date picker not yet on approval screen (currently no expiry = permanent)
- [ ] WAN support — no NAT traversal yet; both device types LAN-only for now

---

## Next priority work

- [ ] Test Ed25519 client key authentication
- [ ] Add expiry date picker on Friend approval screen
- [ ] mDNS hostname in clone URLs (so IP changes don't break remotes)
