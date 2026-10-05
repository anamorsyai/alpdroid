# AlpDroid User Guide

Real Alpine Linux on your phone — no root, no VM. This guide covers everything;
shorter task-based help also lives in the app under Settings → Guide.

Contents: [Install](#1-install) · [First run](#2-first-run) · [Tabs & terminal](#3-tabs--terminal) ·
[Keyboard & keys](#4-keyboard--keys) · [Display & themes](#5-display--themes) · [Packages](#6-packages--quick-install) ·
[Files](#7-files--operations) · [Backup](#8-backup--restore) · [Network, SSH, opencode web](#9-network-ssh--opencode-web) ·
[Devices](#10-devices) · [GitHub](#11-github-sign-in) · [Agent access](#12-agent-access--alpctl) ·
[Plugins](#13-plugins) · [Scheduled jobs](#14-scheduled--background-jobs) · [Updates](#15-updates) ·
[Keep-alive & battery](#16-sessions--keep-alive) · [Widget](#17-home-screen-widget) ·
[Troubleshooting](#18-troubleshooting) · [Security notes](#19-security-notes)

## 1. Install

1. Download the APK from [Releases](https://github.com/anamorsyai/alpdroid/releases) (pick the newest `AlpDroid-vX.Y.Z.apk`).
2. Open it. Android asks to allow installs from that source once — allow, then install.
3. Updates install straight over the old version (same signing key). If Android ever reports a package conflict, back up first, uninstall, then fresh-install + restore.

Requirements: Android 7.0 (API 24)+, internet for first setup.

Size: the APK is about 2.4 MB, the first-run download (Alpine's minirootfs, the smallest official Alpine image) about 3-4 MB, and the app plus base system take under 15 MB on disk. Tools you install later (Node.js, Python, git, agents) add their own size.

## 2. First run

1. Open AlpDroid. It downloads the Alpine minirootfs (about 3-4 MB) into app-private storage (one-time, needs internet).
2. Your first tab opens a shell inside Alpine (`/root`).
3. Grant **All files access** when prompted to share `/sdcard` with Alpine.
4. Settings → Quick Install to add Node.js, Python, git/curl, or agent tools.

Tabs are temporary sessions: running programs stop when the app is closed or updated. Files persist; Backup keeps a copy.

## 3. Tabs & terminal

- `+` opens a tab; each tab is an independent shell (own directory, scrollback, env).
- Long-press a tab: rename, close, or save its screen to a text file.
- Swipe left/right to switch tabs; drag up/down to scroll output.
- Pinch with two fingers to resize text.
- Long-press text to select, then Copy.
- Full-screen TUIs work (vim, htop, opencode): mouse-click reporting included.

## 4. Keyboard & keys

The row above the keyboard has keyboard toggle, search, ESC, TAB, CTRL, ALT, arrows, HOME/END, `/`, `-`, `|`, and your own one-tap shortcuts at the end (existing installs keep `alphacode` and `claude` as ordinary shortcuts you can edit or delete). Tap CTRL/ALT once, then the next key (sticky modifiers). Hide the row in Settings → Display if unneeded. Add your own one-tap buttons there (e.g. `git status`) in the same screen.

## 5. Display & themes

Settings → Display & Theme: theme (whole app incl. existing text), font + size (or pinch), ligatures (Fira Code, `->` becomes →), bell sound, extra-keys toggle.

## 6. Packages & Quick Install

Settings → Packages & Toolchains: one-tap installs (Node.js, Python, git, curl, opencode, Claude Code…). The command runs in your current tab so you watch it work.

- `opencode` lands in `/root/.opencode/bin`, ready in every new tab.
- Tool won't start? "Repair tool dependencies", then retry.
- "Update package index" refreshes the catalog; the search box covers all of Alpine's packages.

## 7. Files & operations

Swipe in from the left edge. Switch between phone storage and Alpine files.

- Long-press: copy, move, rename, delete, compress, extract, share, "Open terminal here".
- Multi-select: long-press one item, tap others.
- Long jobs show progress notifications; tap to return.
- `/sdcard` is visible to other Android apps too.

## 8. Backup & restore

Settings → Backup & Storage.

- **Backup**: whole Alpine setup → one backup file. "App folder" is fast but dies with uninstall; "chosen file" (Downloads, SD, cloud) survives it.
- Low-storage warning before starting; optional weekly auto-backup (keeps 3 newest auto ones, never touches manual ones).
- **Restore**: replaces everything, closes tabs — deliberate action only.
- **Reinstall Alpine**: clean slate (erases inside-Alpine data outside `/sdcard`) — back up first.
- **Export/Import settings**: theme, font, buttons. Plugins + values ride along in every backup.

## 9. Network, SSH & opencode web

Settings → Network & SSH.

- Shows your Wi-Fi address. A server in a tab is reachable on the LAN at `http://<phone-ip>:<port>`.
- **SSH profiles**: save host/port/user, reconnect in one tap.
- **SSH server** (log in from a laptop): "Start SSH server (port 8022)" installs OpenSSH on first use, starts it in a new tab and shows `ssh -p 8022 root@<phone-ip>` with a generated root password (kept in `/etc/alpdroid/ssh_password`; delete that file to get a new one). Android apps cannot use ports below 1024, hence 8022. Use "Add SSH public key" to paste your laptop's `~/.ssh/id_ed25519.pub` and log in without the password. Phone and laptop must be on the same network; stop with Ctrl+C. Anyone on that network with the password gets full access (trusted networks only).
- **opencode web**: "Start opencode web server" opens a tab running `opencode serve` on `0.0.0.0:4096`. It prints a generated password, which the app catches and shows you with a Copy button — enter it in the browser. Anyone on that network with the password gets full access (trusted networks only). Stop with Ctrl+C.
- **Refresh network / DNS**: rewrites guest DNS live after Wi-Fi/mobile switches.

## 10. Devices

Settings → Devices (auto-refresh on plug/unplug, manual Refresh too).

- Drives: SD/USB with free space → new tab → `/mnt/<name>` (needs all-files access).
- USB devices: list + Android permission grant.
- Network: active connection, addresses, per-connection traffic.
- Wi-Fi scan: needs Location on + permission; Connect jumps to Android Wi-Fi settings.

## 11. GitHub sign-in

Settings → Agent access & GitHub → Sign in with GitHub (your browser opens a GitHub approval page).

- Turn on "Let agents use my GitHub token": `git` clone/push/pull just works, no passwords.
- Sign out removes it from the phone; also revoke at github.com/settings/applications.

## 12. Agent access & alpctl

Coding assistants in a tab (opencode, Claude Code) can control the app — OFF by default.

- Turn on in Settings → Agent access & GitHub. When on, any guest program can use it: trusted networks/software only.
- Battery/background changes always ask first. "Regenerate token" cuts off prior access.
- Assistants auto-learn the environment from managed `AGENTS.md`/`CLAUDE.md` notes in home.
- Try: `alpctl` lists everything (`set`, `tab send/screen`, `notify`, `plugin add`, `github token`…).

## 13. Plugins

Custom Settings screens with fields + buttons running your scripts.

1. Settings → Plugins → Create sample plugin.
2. Open it, set fields, press a button; output shows below.
3. Ask a coding assistant to "create an AlpDroid plugin that does X".
4. Nothing runs until you review the script and tap Allow; re-asked on changes.

Format: folder with `plugin.json` + scripts; fields reach scripts as `FIELD_<ID>` env (toggles `1`/`0`); buttons can run in background; schedules included. Entries persist and are backed up.

**Plugin files (`.ad`)** — a plugin can also be a single `.ad` file (AlpDroid plugin): one JSON document holding the manifest and every script, so it is easy to share. Import with Settings → Plugins → **Import plugin file (.ad)**; export an installed plugin with **Export as .ad**. Importing only copies files — you still review the scripts and tap Allow before anything runs. Re-importing the same `id` replaces the scripts and keeps your saved field values. Layout (full schema: [docs/alpdroid-plugin.schema.json](alpdroid-plugin.schema.json), example: [examples/hello.ad](../examples/hello.ad)):

```json
{
  "alpdroid": 1,
  "id": "hello",
  "title": "Hello", "description": "…", "version": "1.0.0",
  "fields":    [{ "id": "name", "type": "text", "label": "Name", "default": "world" }],
  "buttons":   [{ "id": "greet", "label": "Greet", "script": "greet.sh" }],
  "schedules": [{ "id": "tick", "script": "tick.sh", "everyMinutes": 5 }],
  "files":     { "greet.sh": "#!/bin/sh\necho Hello $FIELD_NAME\n", "tick.sh": "date\n" }
}
```
Rules: `id` is `a-z 0-9 _ -` (max 40); every `script` must be a key of `files`; file names are relative (no `..`, not `plugin.json`/`state.json`/`logs/`); up to 64 files, 256 KB each, 1 MB total.

## 14. Scheduled & background jobs

Per plugin under Automation: switches, Run now, View log. Jobs run while the keep-alive notification shows; after reboot open the app once. Notification Exit pauses jobs until next open. Times are approximate.

## 15. Updates

Settings → check for updates (auto-check throttled; manual anytime).

- "Update now" downloads with the filling-logo progress screen, checks it's actually newer, then installs (app closes; tabs/sessions don't survive updates, files do).
- If Android detours you to allow installs, returning re-offers the waiting install automatically.

## 16. Sessions & keep-alive

Android stops background apps. Countermeasures (Settings → Sessions & Background):

- **Keep sessions alive** (default on): foreground notification with session count; tap to return, Exit closes all.
- **Wake lock** (default on, also holds a Wi-Fi lock): keeps the CPU and Wi-Fi awake so servers and long jobs keep running with the screen off. It only applies while sessions or jobs exist; turn it off in Settings to save battery.
- The first time a session starts, AlpDroid asks to be exempt from battery optimization. Accept it — without it servers and builds can still be stopped when the screen is off. You can also set Unrestricted battery use for AlpDroid from Settings and lock it in Recents.
- On Android 12+ some devices additionally cap child processes ("phantom process killer"). If background processes still die, that limit can only be lifted from Developer options or `adb` (not from the app).
- After a system kill, the app says so plainly on next launch (not a crash) with a battery-settings shortcut. Open sessions can't survive a kill — everything running stops.

### Smart resource manager

Settings → Sessions & Background → Smart resource manager (on by default) keeps AlpDroid cool and light:

- **Repaint rate**: a terminal repaints at most ~30 times a second under sustained output (full-screen programs such as opencode used to repaint on every screen refresh, which is most of the heat), ~20 in battery saver or low battery, ~10 when Android reports the phone is hot. Nothing is painted while the terminal isn't on screen.
- **Memory**: when Android is short on memory, old scrollback of background tabs is trimmed first. Terminal history per tab is also configurable (Terminal memory).
- **Frozen sessions**: a session whose helper process keeps a CPU core busy while everything in it is idle is closed, with a notification, instead of draining the battery.
- **Usage**: the same screen shows each session's CPU and RAM, the app's memory, and the terminal's frames per second, draw time and skipped repaints (tap Refresh usage).
- **Typing stays instant**: for 0.4 s after a keystroke the repaint cap is lifted, and a repaint that would draw the same frame again is skipped.
- **Faster process tracing (experimental, off by default)**: lets proot trace only the system calls it needs instead of every one. Programs like opencode, node and python then use much less CPU and run cooler. Applies to new tabs. If `apk` or another tool fails with "Permission denied"/EPERM in a new tab, turn it off.
- **Run sessions on efficiency cores (off by default)**: pins new tabs to the phone's low-power cores. A program that keeps a core busy while idle (some CLIs do) then runs much cooler and drains less battery; heavy work such as builds is slower. Applies to new tabs.
- **Balance load across cores (on by default)**: a busy session in a background tab (or any busy session while the app is hidden) is parked on the phone's low-power cores, so the tab you are using and your other apps keep the fast ones; it moves back when you return to it or it calms down. Hot phone → even the active tab is parked. Only AlpDroid's own processes are touched, never other apps, and nothing is paused or re-prioritised. The status above says when a session is parked. Needs a phone whose low-power cores can be detected.

## 17. Home-screen widget

Add the AlpDroid widget → tap jumps straight into a new session.

## 18. Troubleshooting

- Garbled prompt after resizing: open a new tab (prompt sizes at tab start).
- opencode won't quit: `opencode service stop`, then `exit`.
- Drive missing: new tab after plugging in; check all-files access; FAT32/exFAT only.
- Empty Wi-Fi list: location permission + Location on.
- Pages of `ALSA lib ... cannot find card` errors: fixed in 1.7.49 (a null sound device is configured). On older versions create `/etc/asound.conf` with `pcm.!default { type null }` and `ctl.!default { type null }`.
- Slow builds: Linux runs through a compatibility layer, so heavy jobs take longer than on a computer.
- SSH or a server stops with the screen off: see [Sessions & keep-alive](#16-sessions--keep-alive) (battery exemption, wake lock).
- `apk add` prints errors but the tool installed: mirrors can hiccup even when packages land. Check with `command -v <tool>`; if it's missing, run "Update package index" and retry.
- Still broken: new tab → else backup + Reinstall Alpine.

## 19. Security notes

Guest = trusted (like Termux): terminal code runs as your UID with storage access — run software you trust. Tokens live encrypted in the Keystore. Agent/plugin powers are off-by-default or approval-gated. Details: [SECURITY.md](../SECURITY.md), [PRIVACY.md](../PRIVACY.md) (no analytics/tracking/ads).
