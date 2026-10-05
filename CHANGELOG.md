# Changelog

## 1.7.51 — terminal fixes, cooler idle, efficiency-cores option

- Fixed: pressing Home used to cut every tab's history to 500/100 lines (the memory trimmer treated
  "UI hidden" as memory pressure). History is now only trimmed when Android is really short on memory.
- Fixed: after a line filled the screen width exactly, `ESC[1K` / `ESC[1J` (ncurses) threw an exception that
  dropped the rest of the output and left the parser stuck, swallowing the next character.
- Fixed: lines scrolled inside vim/less/htop or a partial scroll region no longer fill the shell's scrollback.
- Fixed: colon-style SGR (`4:3` curly underline, `38:2::r:g:b`) no longer resets all attributes; underline colour
  (`58;…`) is parsed instead of being misread as bold/reverse.
- Added: DCS/APC/PM strings are swallowed instead of printed; REP (`CSI b`) and primary device attributes
  (`CSI c`) are implemented; setting a scroll region homes the cursor.
- Fixed: copying a selection across soft-wrapped rows no longer inserts newlines and padding spaces.
- Cooler: the cursor stops blinking after 10 s of inactivity (each blink repainted the whole view); output
  produced while the app is in the background no longer wakes the UI thread; the bell is rate-limited
  (a `yes $'\a'` flood no longer vibrates, plays tones and raises notifications hundreds of times a second);
  fewer allocations when scrolling (the evicted history row is reused); the pty bridge reads up to 32 KB
  per wakeup; the resource manager's settings check is cached and its bookkeeping skipped in the background.
- Fixed: a rare lost "child exited" notification in the pty bridge could keep a tab open after its shell ended.
- New (Settings → Sessions & Background): **Run sessions on efficiency cores** pins new tabs to the phone's
  low-power cores, so a program that keeps a core busy while idle runs much cooler. Off by default.

## 1.7.50 — prompt says alpdroid

- The shell prompt now reads `root@alpdroid` instead of `root@alpineterm`. It is rewritten at every session
  start, so existing installs pick it up in new tabs without reinstalling Alpine.

## 1.7.49 — no more ALSA error floods, no stale server tab names

- Programs that try to play a sound (opencode's notification beeps, media players) used to fill the
  terminal with pages of `ALSA lib ... cannot find card '0'` errors, because there is no sound hardware
  under proot. The noise scrolled the screen, garbled full-screen programs, and cost CPU and battery to
  render. Alpine now gets a `/etc/asound.conf` with a null default device (written at session start;
  an `asound.conf` you wrote yourself is left alone), so those calls succeed silently.
- One-tap server tabs (SSH server, opencode serve) are no longer remembered across restarts, so a name like
  "opencode serve" no longer comes back on an ordinary shell tab.

## 1.7.48 — snappier typing, fewer repaints

- Typing latency: the 1.7.47 repaint cap could delay the echo of a keystroke inside a busy full-screen
  program by up to one frame interval. Now, for 400 ms after any keystroke, output repaints are not
  capped at all (the cap only applies to sustained output with nobody typing).
- Repaints that would draw exactly the frame already on screen are skipped. Programs such as opencode
  rewrite identical content constantly; detection is by a 64-bit digest of the visible grid, cursor and
  size (taken under the same lock as the render snapshot, so a missed update is impossible), ~14 us on
  a desktop JVM for a 50x100 grid. Settings shows how many repaints were skipped.
- Settings -> Sessions & Background -> Smart resource manager now also shows the terminal's frames
  per second and draw time, so heat can be tied to a number on the real device.
- New experimental "Faster process tracing" switch (off by default): new interactive tabs let proot use
  its seccomp filter instead of single-stepping every syscall of every guest process. Much less CPU
  for syscall-heavy programs (node/bun CLIs such as opencode); can make some `apk` operations fail with
  EPERM, which is why the old behaviour stays the default. Plugin scripts and package search are
  unaffected.
- 4 more unit tests (content digest); 53 in total.

## 1.7.47 — runs cooler: smart resource manager

- Terminal repaint is capped under sustained output (~30/s; ~20/s in battery saver or low battery;
  ~10/s when Android reports the phone hot). Full-screen programs such as opencode used to repaint the
  whole grid on every screen refresh — most of the heat of running them here. The first update after a
  pause is still immediate and a trailing frame is always drawn. Nothing is painted while the terminal
  is off screen.
- Smart resource manager (Settings -> Sessions & Background, on by default): watches thermal state,
  battery saver, battery level and memory pressure; trims scrollback of background tabs when Android
  is short on memory; closes a session whose native bridge keeps a core busy while the whole session is
  idle (the frozen-session signature) with a notification; shows each session's CPU and RAM.
- `ResourcePolicy` holds the decisions (mode, /proc parsing, runaway detection) with 18 unit tests;
  `TerminalEmulator.trimScrollback` is tested too (49 unit tests in total).

## 1.7.46 — frozen sessions and Ctrl+C that did nothing

- Fixed a freeze in the native pty bridge (introduced with the paste fix in 1.7.35): if the program in
  a tab exited or hung up the terminal while input was still waiting to be delivered, the bridge
  spun at 100% CPU forever and never looked at anything else again — no output, no Ctrl+C, no tab
  close. It now stops delivering to a closed terminal and follows the program's exit.
- Ctrl+C no longer depends on the terminal accepting input: if the byte could not be written within
  0.6 s (a wedged server, a program that stopped reading its input), the bridge sends SIGINT directly
  to the foreground process group over its control channel. New `KILL` control message too.
- Server tabs (SSH server, opencode web): pressing Ctrl+C twice within 2.5 s force-stops the server
  and closes the tab, for a server that hung or ignores the first one.
- The terminal output reader now survives a failure on a single chunk (and `Error`s such as
  out-of-memory) instead of dying — a dead reader fills the bridge's pipe and freezes the session.
- `scripts/test_pty_bridge.py`: integration tests for the bridge (stuck input, guest killed
  mid-input, INT/KILL, resize, large paste), run in CI.

## 1.7.45 — claude key: private socket directory

- The extra-keys `claude` key now makes `/root/.claude/run` private (mode 0700) before launching
  `claude --messaging-socket-path ...`; Claude Code refuses a directory that is group/world accessible
  ("socket directory must be mode 0700"), which is what a plain `mkdir -p` produced.

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
