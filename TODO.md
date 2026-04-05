# TODO — LocalGitHub

## Current status (2026-04-04)

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

---

## Next: install & test

### 1. Install updated APK
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ~/.gradle/wrapper/dists/gradle-8.6-bin/afr5mpiioh2wthjmwnkmdsd5w/gradle-8.6/bin/gradle assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### 2. Fresh pair (clean state)
- Phone: Devices → My Devices tab → tap **+** → PIN appears
- Laptop:
```bash
/tmp/git-host join <PIN> --host 192.168.1.154
```
- **Wait** on laptop — CLI now blocks until you approve on phone
- Approve on phone → CLI prints clone command

### 3. Test clone
```bash
git clone ssh://192.168.1.154/Test.git /tmp/clone-test
```

### 4. Test push
```bash
cd /tmp/clone-test
echo "test" >> README.md && git add . && git commit -m "test"
git push
```

---

## Known remaining issues to investigate

- [ ] Ed25519 auth — BC provider fix applied but not yet confirmed working; ECDSA preferred for now
- [ ] Friend expiry UI — expiry date picker not yet on approval screen (currently no expiry = permanent)
- [ ] WAN support — no NAT traversal yet; both device types LAN-only for now
- [ ] Server running state not persisted across app restarts (toggle resets on reopen)
- [ ] Repo list doesn't refresh after a push creates a new repo (requires manual restart or pull-to-refresh)

---

## Remaining MVP work

- [ ] Expiry date picker on Friend approval screen
- [ ] Notifications on push/pull activity
- [ ] Auto-start server on boot
- [ ] Persist server running state in SharedPreferences
- [ ] mDNS hostname in clone URLs (so IP changes don't break remotes)
