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

1. Download the APK from [Releases](https://github.com/anamorsyai/alpdroid/releases) (pick the newest `AlpineTerm-vX.Y.Z.apk`).
2. Open it. Android asks to allow installs from that source once — allow, then install.
3. Updates install straight over the old version (same signing key). If Android ever reports a package conflict, back up first, uninstall, then fresh-install + restore.

Requirements: Android 7.0 (API 24)+, ~500 MB free for Alpine + tools, internet for first setup.

## 2. First run

1. Open AlpDroid. It downloads the Alpine minirootfs into app-private storage (one-time, needs internet).
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

The row above the keyboard has CTRL, ALT, arrows, ESC, TAB. Tap CTRL/ALT once, then the next key (sticky modifiers). Hide the row in Settings → Display if unneeded. Add your own one-tap buttons there (e.g. `git status`) in the same screen.

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
3. Ask a coding assistant to "create an AlpineTerm plugin that does X".
4. Nothing runs until you review the script and tap Allow; re-asked on changes.

Format: folder with `plugin.json` + scripts; fields reach scripts as `FIELD_<ID>` env (toggles `1`/`0`); buttons can run in background; schedules included. Entries persist and are backed up.

## 14. Scheduled & background jobs

Per plugin under Automation: switches, Run now, View log. Jobs run while the keep-alive notification shows; after reboot open the app once. Notification Exit pauses jobs until next open. Times are approximate.

## 15. Updates

Settings → check for updates (auto-check throttled; manual anytime).

- "Update now" downloads with the filling-logo progress screen, checks it's actually newer, then installs (app closes; tabs/sessions don't survive updates, files do).
- If Android detours you to allow installs, returning re-offers the waiting install automatically.

## 16. Sessions & keep-alive

Android stops background apps. Countermeasures (Settings → Sessions & Background):

- **Keep sessions alive** (default on): foreground notification with session count; tap to return, Exit closes all.
- **Wake lock**: keeps CPU awake for very long jobs; costs battery, off by default.
- If the system still kills the app, allow Unrestricted battery use for AlpDroid (in-app button under Settings), lock it in Recents.
- After a system kill, the app says so plainly on next launch (not a crash) with a battery-settings shortcut. Open sessions can't survive a kill — everything running stops.

## 17. Home-screen widget

Add the AlpDroid widget → tap jumps straight into a new session.

## 18. Troubleshooting

- Garbled prompt after resizing: open a new tab (prompt sizes at tab start).
- opencode won't quit: `opencode service stop`, then `exit`.
- Drive missing: new tab after plugging in; check all-files access; FAT32/exFAT only.
- Empty Wi-Fi list: location permission + Location on.
- Slow builds: Linux runs through a compatibility layer, so heavy jobs take longer than on a computer.
- Still broken: new tab → else backup + Reinstall Alpine.

## 19. Security notes

Guest = trusted (like Termux): terminal code runs as your UID with storage access — run software you trust. Tokens live encrypted in the Keystore. Agent/plugin powers are off-by-default or approval-gated. Details: [SECURITY.md](../SECURITY.md), [PRIVACY.md](../PRIVACY.md) (no analytics/tracking/ads).
