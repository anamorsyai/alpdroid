# Changelog

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
