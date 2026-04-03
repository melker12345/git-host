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


## Some undeveloped ideas

- The app should support sharing binary files between devices.
- Mobile UI should be refined, two tabs the top allowing for switching between Code | Media
  The Code tab shows stored git repos, the Media tab shows stored media files (like a gallary app).

- Handle SSH key and handeling the setting of origin,
  I want super low friction for setting up new devices this includes finding a slick way of managing the ssh keys and setting up the origin.
  We should allow for configuration of the ssh key generation and the origin setting.
  We do not want to force "our way" of handeling things like SSH keys onto users. 
  
  We need to consider the UX and DX for the experiance someone that installs this application for the first time goes through.
  
  User installs Mobile app:
    Mobile should host and manage allowed devices.
    We should offer like a setup guide.
    The setup guid shall cover how it works and what's needed.
    There is not a lot of setup needed on the mobile side, only the desktop needs to generate SSH and set up the origin etc
    perhaps we could offer one-liner commands on mobile that the user can copy and run on their desktop to set up the SSH key and origin.
    This can be done by having a button in the setup guide that allows for transfering the commands to the pc.

  Desktop app:
    Should also be avalible for hosting.
    Say a user want to host on a old laptop or rasberry PI or PC.
    We need to support this and this is what our go application does.
    So it would be nice if we had like a setup guide for pc app.
    Where we promt user do you want setup guide of manuall configuration?
    followed with things like "generate new SSH key? (yes/no)", "set up origin? (yes/no)", etc.

- The mobile should be able to manage "Friends" 
  Differentiate between Devices and Friends.
  Devices are only the users own devices.
  Friends are other users that the user wants to share repos with.

  The hosting device should be able to set permissions for each friend, allowing access to specific repos, read-only or read-write.
  but also set an exiry date on the friends access.
  So phone is able to see the list of added "Friend" devices and press e.g Bob's key is valid for a week.

  We should also have notifications for when a Friend is pusing/pulling, or accessing hosting device in any other capacity.
  
  We need to also consider what if Friend is not on same network as hosting device.
  We ideally should have a setting to allow for WAN connections.

  "
  Thinkign of the flow here: 
  Say i have a repo called Test.
  I'm activly working on it (hosted on my mobile).
  I want to start colaborating with a friend.
  How should this work?
  Friend sends SSH public key?
  Can we allow for a temporary TCP connection to a device over WAN?
  That we use to transfer the SSH key through?
  Can we allow for our PIN to be used even if the connection happens over WAN?

  We might want a toggle in settings on host machince for WAN connections, set timeout for WAN connections etc.
  "

- We should also allow for multiple Friends to be added.

- Friend is added, that firend shuold be able to list the repos that are accessible to them.
- Allow Friends to commit an issue?
  This might be a bit complex, but it would be nice to have.
  Say user A wants to report an issue, 
  they enter a command, issue is written to a md file and sent to the hosting device.
  Hosting device can then view the issue and respond to it.
  
  This would mean we need to seperatly handle Issue files from code files.

- 