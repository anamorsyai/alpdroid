# AlpDroid

![version](https://img.shields.io/static/v1?label=version&message=1.7.18&color=blue)
![license](https://img.shields.io/static/v1?label=license&message=MIT&color=green)
![platform](https://img.shields.io/static/v1?label=platform&message=Android&color=brightgreen)
![minSdk](https://img.shields.io/static/v1?label=minSdk&message=24&color=orange)

Real Alpine Linux on your phone. No root, no VM, no third-party terminal library.

AlpDroid runs an Alpine Linux userland directly on the device via `proot`, with its own
from-scratch VT100 terminal emulator and PTY bridge, shared storage at `/sdcard`, and the
device's live network inside the guest.

## Screenshots

Screenshots live in `assets/screenshots/` (captured on device):

- `assets/screenshots/tabs.png` — multi-tab terminal sessions
- `assets/screenshots/files.png` — file browser (Android + Alpine rootfs)
- `assets/screenshots/settings.png` — Settings, plugins, and agent API

Note: capture on device before release; placeholders only until then.

## Features

- **Multi-tab terminal** — independent shells with own working directory, scrollback, and
  environment; tabs survive backgrounding and Activity recreation via a foreground
  keep-alive service and session persistence.
- **Own VT100 emulator** — written from scratch: scrollback, 256-color and truecolor SGR,
  bracketed paste, auto-wrap, mouse click reporting for full-screen TUIs, reflow-on-resize,
  pinch-to-zoom font sizing.
- **Alpine via proot, no root** — minirootfs downloaded on first launch (latest-stable
  metadata read at runtime); `proot` fetched at build time from Termux packages.
- **File browser** — browse Android and Alpine sides, copy/move/zip/share across both;
  `/sdcard` is bind-mounted into the guest.
- **Plugins with approval** — Settings panels from `plugin.json` (fields, script buttons,
  scheduled/background jobs); added scripts need user approval; values included in backups.
- **Agent API (`alpctl`)** — local token-guarded control API for tabs, settings, clipboard,
  notifications, devices, and plugins, usable by CLI coding agents in a tab.
- **GitHub one-tap sign-in** — device flow with token encrypted in the Android Keystore and
  automatic git authentication.
- **In-app updater** — check and install release APKs from Settings.
- **Backup / restore** — whole Alpine rootfs to a single symlink-safe `.tar.gz`, with
  optional automatic weekly backup.
- **Devices panel** — SD cards / USB drives (`/mnt/...`), USB devices, network adapters,
  Wi-Fi scan.
- **SSH profiles** — saved host/port/user quick-connect with one-tap retry.
- **Home-screen widget** — jump straight into a new session.
- **Themes** — multiple color themes, adjustable font size, Fira Code with ligatures.

## Quick start

1. Download the APK from [Releases](../../releases) and install it on your device.
2. Open AlpDroid. On first launch it downloads the Alpine minirootfs into app-private
   storage (needs internet once).
3. Your first tab opens a shell inside Alpine. Grant "All files access" when prompted to
   enable `/sdcard` sharing.
4. Open Settings → Quick Install to add Node.js, Python, git/curl, or agent tools.

Tabs are temporary sessions — running programs stop when the app is closed or updated.
See Settings → Guide inside the app.

## Building from source

Requirements: Java 17, Android SDK 34, NDK `26.3.11579264`, CMake `3.22.1`.

```sh
./gradlew :app:assembleDebug
```

`proot` is fetched at build time (`scripts/fetch_proot.py`) and needs normal internet
access to `packages.termux.dev` and Google's Maven repo — sandboxed/offline builds fail.

Release signing uses env vars (`ALPDROID_*`); see `.github/workflows/` for the exact
names and the CI release flow. Debug builds use the committed `app/debug.keystore`.

## Architecture

See [assets/architecture.svg](assets/architecture.svg) for the component diagram.

Summary: `TerminalView` (Canvas renderer + `TerminalEmulator` screen buffer) talks to a
native `pty_bridge` PTY helper over stdin/stdout, with window resize on a separate
named-pipe control channel. Sessions spawn the guest shell under `proot` with the
minirootfs extracted by a built-in `ustar` parser. Storage is shared via bind-mount;
network is shared (same stack), with `/etc/resolv.conf` refreshed from host DNS.

## Security model

Guest is trusted (same as Termux): code running in the terminal runs as your UID with
access to shared storage, so only run software you trust. Secrets (GitHub token, API
tokens) are stored encrypted in the Android Keystore. Agent/plugin capabilities
(`alpctl`, plugin scripts) are off by default or need explicit approval, with an audit
trail. Full details: [SECURITY.md](SECURITY.md).

## Privacy

No analytics, no tracking, no ads. Network use is downloads you trigger (Alpine
rootfs, packages, updater checks) plus DNS. Backups stay on your storage. Full
details: [PRIVACY.md](PRIVACY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build, test, and pull-request conventions.

## License

MIT — see [LICENSE](LICENSE). `proot` (GPL-2.0) and its runtime libs (LGPL-3.0) are
invoked as subprocesses, never linked into app code.
