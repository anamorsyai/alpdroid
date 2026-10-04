# Changelog

## 1.7.44 — long pastes can't hang the terminal

- Pasting long text is now written to the session in 8KB chunks on its own writer thread (never the UI
  thread) instead of one giant write, and the paste limit rose from 1M to 5M characters.
- Ctrl+C cancels a paste in progress and drops anything queued behind it; a cancelled bracketed paste
  still sends its closing marker so the program doesn't stay stuck in paste mode.
- Large pastes show a "Pasting N KB — Ctrl+C cancels" hint.
- 7 more unit tests cover the chunked writer (30 total).

## 1.7.43 — lighter, smoother, tested

- Release files are now named `AlpDroid-vX.Y.Z.apk` (the in-app updater accepts any `.apk` asset, so
  updates from older versions keep working). Docs now say AlpDroid throughout and state the real sizes:
  ~2.4 MB app, ~3-4 MB first-run download (Alpine minirootfs), under 15 MB installed.
- Smoother UI: extra keys and the add-tab button scale on press, switching tabs cross-fades.
- Lighter on memory: terminal scrollback is configurable (500 / 1000 / 2000 / 5000 lines; default
  1000, was 2000) under Settings -> Sessions & Background -> Terminal memory. Applies to new tabs.
- Settings -> Sessions & Background: "Copy adb fix for killed processes" for Android 12+ devices that
  cap child processes; the wake-lock switch now says it also covers Wi-Fi.
- SSH public key validation moved to a small tested helper (`SshKeys`).
- 23 unit tests (terminal emulator, snapshot logic, SSH key validation) run in CI; CI and release workflow
  moved to `checkout@v5` / `setup-java@v5`; Dependabot keeps actions and Gradle dependencies current.

## 1.7.42 — claude key restarts a stopped local proxy

- The extra-keys `claude` key now checks whether a locally configured Anthropic-compatible proxy
  (e.g. a background shim on 127.0.0.1:9086 that `claude` is pointed at) is actually responding,
  and restarts it (`oc -r`) first if not — that proxy is a plain background process and doesn't
  survive the guest restarting or the app being killed, which otherwise looked like claude itself
  failing to connect. No-op when no such setup exists.

## 1.7.41 — claude key without the messaging warning

- The extra-keys `claude` key now runs `claude --messaging-socket-path /root/.claude/run/msg-$$.sock`
  (after creating that directory), which avoids Claude Code's "Cross-session messaging is off:
  ... without a uid mapping" warning under proot's fake root. Typing plain `claude` still works;
  it just shows the warning.

## 1.7.40 — smoother output, SSH keys

- Terminal output is fed to the emulator in 4KB slices instead of holding its lock for a whole
  32KB read, so drawing no longer stalls behind heavy output (cat of a big file, builds).
- Settings → Network & SSH → "Add SSH public key": paste a laptop's public key to log in to the
  SSH server without the password (written to /root/.ssh/authorized_keys, validated, de-duplicated).
  sshd now also runs with StrictModes off, since proot's fake ownership can trip that check.

## 1.7.39 — remove the "AlphaCode monitor" shortcut

- The one-time cleanup of the old monitor shortcut now also matches it by label and by a command
  that runs `monitor_alpine.sh` (1.7.38 only matched the exact command `alphacode monitor`).
- Plugins: state.json saves are synchronized and atomic; approval-hash cache guarded; job logs use one buffered writer, decode UTF-8 across reads, and a stopped-then-relaunched job no longer launches a duplicate. Removed unused ic_edit drawable.

## 1.7.38 — alphacode and claude keys

- Extra-keys row: built-in `alphacode` (runs plain `alphacode`) and `claude` keys. A user-made
  shortcut whose command is exactly `alphacode monitor` is removed once, since the new key
  replaces it.

## 1.7.37 — one-tap SSH server

- Settings → Network & SSH → "Start SSH server (port 8022)": installs openssh on first use,
  generates host keys and a random root password (kept in /etc/alpdroid/ssh_password), starts
  sshd in a new tab and shows/copies the `ssh -p 8022 root@<phone>` command and password.
  Port 8022 because Android apps cannot bind ports below 1024.
- Quick install (opencode v1/v2, Claude Code CLI) and the SSH setup no longer chain `apk add && …`:
  apk retries once, then the next step is gated on the tool actually existing (`command -v`),
  so a mirror error after a successful install no longer skips the rest. Agent notes say the same.

## 1.7.36 — background reliability + perf/bug fixes

- Servers and long operations no longer die once the app is backgrounded, even with the
  battery exemption on: the foreground service now holds a partial wake lock (now on by
  default; toggle in Settings) **and** a Wi-Fi lock, so the CPU and Wi-Fi don't sleep with the
  screen off. A foreground start refused while backgrounded (Android 12+) is retried on the
  next resume instead of leaving the process killable. A one-time prompt asks for the battery
  exemption the first time a session exists.
- pty_bridge: master is non-blocking and large pastes keep draining output while writing —
  fixes a deadlock where a big paste into a program that echoes could wedge the tab.
- PtySession: resize thread no longer polls every 5ms forever when the control FIFO fails.
- Renderer: per-frame snapshot copies only the scrollback rows actually visible (was the whole
  2000-row scrollback every frame). PTY reader buffer 8KB -> 32KB.
- Updater: a cached APK of the right size must also parse as an APK to be reused.
- README version badge corrected.

## 1.7.33 — AlpDroid everywhere + agent token that survives restarts

- All user-facing "AlpineTerm" wording is now AlpDroid: guide, notifications, toasts,
  agent notes, alpctl messages, browser login label. The APK filename, the app's
  internal class names, and the managed AGENTS.md markers keep the historic
  AlpineTerm prefix (identifiers + compatibility).
- GitHub token for agents no longer rides the UI host lifecycle: after an update or
  process kill, `alpctl github token` (and git push/pull with it) keeps working while
  "Let agents use my GitHub token" is on. Previously agents lost the token until you
  toggled the option or reopened the app.

## 1.7.32 — alphacode button installs even when apk index fetches fail

- The alphacode (musl) button no longer chains through `apk add --no-cache curl`: busybox wget fetches the installer directly. On networks where Alpine's index mirrors time out, the old `apk add && curl …` chain skipped the install entirely ("4 errors" then nothing). wget needs no packages, so the button now installs in one tap anywhere.
- Same signing key; installs over v1.7.31 as a normal update.

## 1.7.31 — alphacode installs from the API (no stale raw CDN)

- The alphacode (musl) button now fetches its installer from the GitHub contents API instead of raw.githubusercontent — the raw CDN served stale blobs for up to hours, which broke the button with phantom "bad address 'token'" errors from an old script version.

## 1.7.30 — alphacode in Quick install

- Settings → Packages → AI coding agents now offers **alphacode (musl)** with ★: one tap installs the free MIT coding agent built from your fork — a fully static musl binary (no libstdc++/gcompat needed), sha256-verified, symlinked onto PATH.
- Release workflow covers the new build automatically (same signing key).

## 1.7.29 — Serve auto-opens the browser

- Server ready now opens this phone's browser on 127.0.0.1:4096 with the password
  already copied — paste to sign in. LAN URLs still shown in the dialog.

## 1.7.28 — opencode serve that just works

- One-tap server preloads gcompat (fixes the bun FFI crash on musl) and pops a
  dialog with the generated password + Copy button instead of scrollback hunting.

## 1.7.27 — Kill notice stops crying wolf

- Swipe-away/Back/rotation run onDestroy, which now marks a clean exit; only a death
  with no lifecycle at all raises the system-kill dialog.

## 1.7.26 — One-tap server really one tap

- The opencode Start button spawns a tab already running the server (argv from boot)
  instead of typing into a half-started shell.

## 1.7.25 — Battery button actually opens settings

- The exemption prompt silently died without its manifest permission; added it, and
  both entries now share one helper with a fallback + toast.

## 1.7.24 — System-kill notice

- Restarting into a stale heartbeat now plainly says the system stopped the app
  (not a crash), with a shortcut to the battery exemption that prevents it.

## 1.7.23 — One-click commands actually land

- New tabs type their auto-command on first shell output instead of into the void
  while proot is still starting (opencode Start button, file-browser terminal-here).

## 1.7.22 — Hardening round 3 + filling-logo update screen

- Update download now shows the filling Alpine logo with live progress.
- Symlink-safe scratch cleanup, kill-safe token/session writes, capped plugin JSON.
- GitHub socket/refresh/poll fixes, dead-activity callback cleanup, bridge hardening.

## 1.7.21 — Update-install resume fix

- Granting the install permission in Settings then returning re-offers the waiting
  install instead of stranding the user with no way to continue.

## 1.7.20 — Bug-fix & hardening pass

- Updater no longer leaks connections on redirects; partial downloads swept.
- Terminal: reader-thread crash guards, tear-free selection, search thread cleanup.
- Sessions: EXIT-trap reaper for script runs, scratch dirs cleaned, stale bridge file fail-closed.
- Native bridge: fifo/signal/fd fixes, winsize clamps; backup restore + file browser symlink hardening.
- Scheduler idle skip, GitHub sign-in rotation-safe, SSH profiles synchronized.

## 1.7.19 — Security & stability pass

- Full audit fixes: terminal cell-aliasing, CSI overflow guards, feed bounds checks.
- Rootfs install now serialized, staged + checksum-gated before swap; HTTPS-only redirects.
- Tab close kills the whole guest tree (bridge SIGTERM handler + forced reaper).
- Agent bridge: ephemeral-port fallback against loopback squatting, request caps.
- Backup/restore to a user-chosen file (survives uninstall); extractor hardened.
- Restore no longer discards a good install marker on failure; own backup thread.
- Package search injection fix; plugin manifest/hash caps; DNS TTL + fallback fix.

## 1.7.7

- Async terminal search with match counter.
- IME composing-text support for CJK/complex input.
- Backup/restore made cancellable with restore preflight checks.
- Devices view moved off the UI thread; browser snapshot reload.
- Session resume-all now survives individual tab failures.

## 1.7.6

- Fixed mouse clicks and scroll being ignored in full-screen TUIs.
- Click reporting now uses screen rows instead of scrollback-offset rows.
- Scroll gesture translation to arrow keys restored.
- Clickable TUI elements (menus, pickers) work correctly again.

## 1.7.5 — Performance Round

- Cursor rendering switched to rect-blink (no full redraw).
- Screen-only `tailText` instead of full scrollback scans.
- Terminal fingerprint cache for faster re-layout.
- SSH command quoting fixes and USB-drive deduplication.
- Tar extraction caps against archive bombs.

## 1.7.4 — Thread/Memory Fixes

- Watchdog lifecycle tied to session lifetime (no leaked threads).
- Daemon executors so background pools can't block process exit.
- Tar-bomb caps and bounded reads on PTY/bridge streams.
- Preference saves moved off the UI thread.
- Integer-overflow guards in buffer arithmetic.

## 1.7.3 — Audit Fixes

- Terminal race and out-of-bounds guards in the emulator core.
- PTY file-descriptor and stderr-stream leak fixes.
- Atomic backup writes (no half-written archives on failure).
- Rename/zip path hardening against traversal.
- Plugin approval now enforced on every execution path.

## 1.7.2

- Audible notification sound when an agent run finishes.
- GitHub silent refresh: device-flow refresh token persisted with expiry.
- Access token auto-renewed in `validToken` before calls.
- Stop-button initialization self-reference fix.

## 1.7.1

- Split AlpDroid into a standalone terminal app (ex claude-workspace).
- `alpctl` bridge, GitHub OAuth device flow, and plugins ported over.
- Devices view redesign and terminal correctness fixes.
- Release workflow and Gradle build set up for this repo.

## 1.6.3

- Session persistence and resume across process kills.
- SSH quick-connect profiles with one-tap reconnect.
- File browser copy/move/zip/share across Android and rootfs.
- Theme, font, and shortcut customization refinements.
- Stability fixes from the 1.6 test cycle.

## 1.3.1

- Custom one-tap command shortcuts (Settings → Custom shortcuts).
- Home-screen widget for jumping straight into a session.
- Pinch-to-zoom font sizing with content reflow.
- Fira Code with ligatures as default font.
- Bug fixes for keyboard resize and scrollback.

## 1.1.0

- Initial public release: Alpine Linux via proot, no root required.
- From-scratch VT100/ANSI terminal emulator with multi-tab sessions.
- Shared storage bind-mounted into the guest (`/sdcard`).
- Quick installers for Node.js, Python, git/curl, and agent CLIs.
- Rootfs backup and restore to a single `.tar.gz`.
