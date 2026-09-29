# AlpDroid

A standalone, Termux-style Android terminal app: real **Alpine Linux**, running directly on the
device via `proot` — no root needed — with shared storage and the device's live network already
available inside it. Built from scratch: its own terminal emulator, its own PTY bridge, no
third-party terminal library, no root, no VM.

## Features

- **Multi-tab sessions** — run several independent shells side by side, each with its own working
  directory, scrollback, and environment. Tabs survive backgrounding and Activity recreation (a
  foreground keep-alive service and session persistence mean a killed/recreated process reattaches
  to still-running shells instead of losing them).
- **Full VT100/ANSI terminal emulator**, written from scratch — scrollback, 256-color and
  truecolor SGR, bracketed paste, DECAWM auto-wrap, mouse click reporting (so full-screen TUIs with
  clickable elements work correctly instead of every tap just opening the keyboard), and
  reflow-on-resize (existing wrapped text re-wraps to a new column width, not just future output).
- **Built for CLI coding agents** — one-tap installers (Settings → Quick Install) for Node.js,
  Python, git/curl, and agent tools like opencode and Claude Code CLI. Full-screen TUI apps get a
  real terminal resize when the keyboard opens/closes (so their content redraws correctly instead
  of being pushed off-screen), and dragging inside one translates to arrow-key scrolling instead of
  doing nothing.
- **Pinch-to-zoom** font sizing with existing content reflowing to the new width, not just new
  output.
- **File browser** (drawer, right edge) — browse both the Android side and the Alpine rootfs, copy/
  move/zip/share files across the two, backed by real shared-storage access (`/sdcard` is
  bind-mounted straight into the guest).
- **Backup & restore** — the whole Alpine rootfs to a single `.tar.gz`, symlink-safe.
- **SSH quick-connect profiles** — save host/port/user, reconnect in one tap; a dropped connection
  can be retried from the tab menu without hunting back through Settings.
- **Home-screen widget** — jump straight into a new session without opening the app first.
- **Customizable**: multiple color themes, adjustable font size, Fira Code with ligatures on by
  default, custom one-tap command shortcuts (Settings → Custom shortcuts).
- **Session resume** — after the whole process is killed (not just backgrounded), the app offers to
  reopen the same number of named tabs on next launch.

## How it works

- **Terminal UI**: `TerminalView` (`app/src/main/java/com/alpdroid/app/terminal/`) is a
  from-scratch `View` — its own VT100/ANSI screen-buffer model (`TerminalEmulator`) and its own
  Canvas-based renderer, each character positioned on its own fixed grid cell (not left to the
  font's own text-shaping) so box-drawing/block art renders correctly.
- **Real PTY, not just a pipe**: the Android SDK has no public API to fork a process with a
  controlling terminal already attached (no `forkpty()`, no pre-exec hook on `ProcessBuilder`).
  `app/src/main/cpp/pty_bridge.c` is a small native helper — allocates a PTY, execs the guest
  shell attached to it, then relays bytes between the PTY and its own stdin/stdout (which is
  all a plain `ProcessBuilder` subprocess gives Kotlin). A window resize goes over a separate
  named-pipe control channel so it can never be confused with terminal data.
- **Alpine itself**: downloaded straight to app-private storage on first launch —
  `AlpineRootfs.kt` reads Alpine's `latest-stable` release metadata at runtime (no version to
  hardcode/bump) and extracts the minirootfs tarball with a small built-in `ustar` parser.
  Nothing is bundled as an APK asset (APK assets can't hold symlinks; a real filesystem has no
  such problem), so there's no build-time rootfs-fetch step at all.
- **`proot`**: fetched at **Gradle build time** (`scripts/fetch_proot.py`) from Termux's own
  package repository and packaged as `libalpineterm_proot.so` under `jniLibs/<abi>/` — Android's
  installer extracts and marks files there executable regardless of their actual content, the
  standard way to run a binary the app didn't statically link. This step needs real network
  access (Android Studio / CI); see below.
- **Shared storage**: requests "All files access" (`MANAGE_EXTERNAL_STORAGE`) and bind-mounts
  the device's real shared storage into the guest at `/sdcard` (`-b <storage>:/sdcard`, same
  convention Termux uses) — a file manager, USB transfer, or another app sees exactly what the
  guest writes there, live, and vice versa.
- **Shared network**: automatic. `proot` never isolates the network namespace, so the guest
  already shares the device's live network stack — same interfaces, same IP, same reachability.
  The only thing that doesn't work out of the box is DNS (no `netd` inside the guest), so
  `AlpineSession.kt` refreshes `/etc/resolv.conf` with the host's own DNS servers on every
  session start.
- **Foreground keep-alive**: `TerminalKeepAliveService` holds the process at foreground priority
  while a shell or long CLI operation is running, so Android is far less likely to kill it while
  backgrounded — the notification shows the live session count and has an Exit action to close
  every session at once.

## Building

This can't be built inside a sandbox with restricted network access — `fetch_proot.py` needs to
reach `packages.termux.dev`, and the Android Gradle Plugin needs Google's Maven repo. Open it in
Android Studio, or run in CI with normal internet access:

```
./gradlew :app:assembleDebug     # debug build
./gradlew :app:assembleRelease   # release build (needs keystore.properties — see below)
```

The NDK (CMake, for `pty_bridge`) is required — Android Studio will prompt to install it if
missing.

### Release signing

`app/build.gradle.kts` reads signing credentials from `keystore.properties` at the repo root
(git-ignored, never committed). Without it, `assembleRelease` still builds — useful for CI
checks — but produces an unsigned APK that can't be installed as-is. To sign real releases,
create `keystore.properties`:

```
storeFile=path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Known limitations

- No sixel, no true rectangular copy — the VT100 subset covers what a shell, `less`, `vim`, `top`,
  and modern CLI agent tools actually use, not the full terminfo esoterica list.
- `proot`'s exact Termux-published dependency set can change; `fetch_proot.py` resolves it from
  the live package index rather than a hardcoded list, but a build can still fail if Termux
  restructures that package — the app falls back to a plain (but still real-PTY) system shell
  in that case rather than refusing to start.
- A CLI tool that starts its own long-running background daemon (rather than a plain child
  process) is responsible for its own clean shutdown — some (opencode's own background server
  included) provide an explicit stop command (e.g. `opencode service stop`) that should be run
  before `exit`, since Ctrl+C alone may only detach the interactive front end.

## Licensing

`proot` is GPL-2.0, invoked here as a subprocess (never linked into this app's own code);
`libtalloc`/`libandroid-shmem` (its runtime deps) are LGPL-3.0. Everything under
`app/src/main/java` and `app/src/main/cpp` is this project's own code.
