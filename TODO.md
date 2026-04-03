# TODO — LocalGitHub

## Current status (2026-04-03)

**Pairing: WORKING ✅**  
`git-host join <PIN> --host 192.168.1.154` succeeds, device shows in Devices screen.

**SSH server: NOT YET TESTED — needs rebuild + install**  
`git push` gives `Connection refused` on port 2222 because the server hasn't been started.

---

## Next session — what to do first

### 1. Rebuild & install the APK in Android Studio
Three fixes were applied since the last working build that haven't been installed yet:

| Fix | File |
|-----|------|
| `javax.management` stubs (10 Java stub files) | `app/src/main/java/javax/management/*.java` |
| `parseSshWireKey` rewritten without MINA SSHD | `Pairing.kt` |
| `startForeground()` now passes `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` | `GitHostService.kt` |

In Android Studio: **Run ▶ or Build → Generate Signed/Debug APK**, then install via ADB or Android Studio directly.

### 2. Start the SSH server on the phone
- Open the app → **Repos** screen  
- Tap the **⏻ (power) icon in the top-right corner** of the title bar  
- A persistent notification "git-host running" should appear  
- If a notification permission dialog pops up, allow it

### 3. Test git push from laptop
```bash
cd ~/Desktop/Test          # the test repo from last session
export PHONE=192.168.1.154
git push phone main        # remote was already added as ssh://git@$PHONE:2222/Test.git
```
Expected: repo `Test.git` created on the phone, push succeeds.

### 4. Test git clone
```bash
cd /tmp
git clone ssh://git@192.168.1.154:2222/Test.git clone-test
ls clone-test/             # should show README.md
```

### 5. Test git pull (round-trip)
```bash
cd ~/Desktop/Test
echo "update" >> README.md
git add . && git commit -m "update"
git push phone main

cd /tmp/clone-test
git pull                   # should get the new commit
```

---

## If the server still won't start after rebuild

Check logcat for errors from the `GitHostService` and `SshServer` tags:
```bash
adb logcat -s GitHostService:D SshServer:D Pairing:D
```

Most likely culprits after the rebuild:
- **`NoClassDefFoundError`** for any other missing class → add another stub
- **`Exception` in `server.start()`** → SSH host key generation failure (BouncyCastle issue)
- **`SecurityException`** → missing permission in manifest

---

## Remaining MVP work after SSH server is confirmed working

- [ ] `git clone` from phone to laptop (verify UploadPack path)
- [ ] `git pull` after a second push (verify incremental sync)
- [ ] Auto-start server on boot (add `RECEIVE_BOOT_COMPLETED` + BroadcastReceiver)
- [ ] Persist server running state across app restarts (SharedPreferences flag + check in `ReposScreen`)
- [ ] Show actual server running state in UI (poll `GitSshServer.isRunning` instead of local toggle)
