<p align="center">
  <img src="assets/banner.svg" alt="AlpDroid — real Alpine Linux on your phone. No root." width="100%">
</p>

<p align="center">
  <a href="https://github.com/anamorsyai/alpdroid/releases/latest"><img src="https://img.shields.io/github/v/release/anamorsyai/alpdroid?label=release&color=3ED0B8&style=flat-square" alt="Latest release"></a>
  <a href="https://github.com/anamorsyai/alpdroid/releases"><img src="https://img.shields.io/github/downloads/anamorsyai/alpdroid/total?color=3ED0B8&style=flat-square" alt="Downloads"></a>
  <a href="https://github.com/anamorsyai/alpdroid/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/anamorsyai/alpdroid/ci.yml?branch=main&label=build&style=flat-square" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-brightgreen?style=flat-square" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/root-not%20needed-orange?style=flat-square" alt="No root">
</p>

<p align="center">
  <b>English</b> · <a href="README.ar.md">العربية</a>
</p>

<p align="center">
  <a href="https://github.com/anamorsyai/alpdroid/releases/latest"><b>Download</b></a> ·
  <a href="docs/USER_GUIDE.md">User guide</a> ·
  <a href="docs/ALPBROWSER.md">AlpBrowser guide</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://github.com/anamorsyai/alpdroid/issues">Report a bug</a>
</p>

---

**AlpDroid** puts a real [Alpine Linux](https://alpinelinux.org) shell on your Android phone — no root, no
virtual machine, no cloud. Install packages with `apk`, run Node.js, Python or git, start an SSH or web
server, and use command-line coding agents (opencode, Claude Code and others) straight from a phone-friendly
terminal built from scratch for touch. Its companion app, **AlpBrowser**, gives those agents — and you — a
browser to test and automate web apps, with a built-in intercepting proxy.

## Highlights

| | |
|---|---|
| **Real Linux, no root — and tiny** | Alpine runs in userspace through `proot`. A ~2.4 MB app plus a ~3-4 MB base-system download; under 15 MB installed. |
| **A terminal made for phones** | Multi-tab, own VT100/xterm emulator, truecolor, emoji and wide characters, mouse support for TUIs, pinch-zoom, ligatures, search, an extra-keys row (ESC, TAB, CTRL, ALT, arrows, and your own one-tap shortcuts). |
| **One-tap servers** | Start an **SSH server** to log in from your laptop, or the **opencode web** UI — each runs in the background with its own Stop, Log and Open buttons. On the phone itself opencode web opens already signed in. |
| **Built for coding agents** | One-tap installs for opencode, Claude Code and alphacode, shortcut buttons to launch them, GitHub sign-in so `git` just works, and `alpctl` — a token-guarded API that lets agents use the app. |
| **AlpBrowser — the agent's browser** | A companion app for bug-bounty testing and web development. Fast direct browsing with tabs and an ad blocker; turn on Capture for a Burp-style Proxy (pause, edit, forward or drop requests and responses, searchable History, match-and-replace Rules), a Repeater and persistent extensions. The agent can click, fill forms, wait for elements, run scripts, take screenshots, save evidence, export HAR files, manage cookies and hand the page to Playwright or Puppeteer — with no tokens to copy on your phone. See the **[AlpBrowser guide](docs/ALPBROWSER.md)**. |
| **Plugins and automation** | Add your own Settings screens and buttons with a `plugin.json` or a single shareable `.ad` file, and run scripts on a schedule. Every plugin needs your approval before it runs. |
| **Cool and light** | A smart resource manager caps repaint rate (lower when the phone is hot or in battery saver), trims memory under pressure, closes frozen sessions, balances load across cores and keeps the guest `/tmp` from filling the phone. |
| **Stays alive in the background** | A foreground service, optional wake lock and Wi-Fi lock keep long builds, servers and jobs running with the screen off — and AlpDroid tells you plainly if Android stopped it. |
| **Your files, both sides** | Built-in file browser for Android and Alpine storage; `/sdcard` and USB/SD drives are visible inside Linux. Back up the whole Alpine system to one file, with optional weekly automatic backups. |
| **Self-updating** | AlpDroid checks for new versions, downloads only what changed (small patches, about a quarter of the full size), verifies the result and installs over the old one. It updates AlpBrowser the same way. |
| **Safe by design** | No analytics, no ads. Tokens live in the Android Keystore. Plugins and agent powers need your approval. |

## Install

1. Download the latest **`AlpDroid-vX.Y.Z.apk`** (about 2.4 MB) from the [Releases](https://github.com/anamorsyai/alpdroid/releases/latest) page.
2. Open it and allow installs from your browser or file manager when Android asks.
3. Launch AlpDroid. The first start downloads the tiny Alpine base system once (about 3-4 MB) — see [Size](#size).
4. Allow **All files access** if you want `/sdcard` inside Linux.

Updates install straight over the old version — releases are signed with the same key, and the app can
check for and download new versions itself.

**Requirements:** Android 7.0 (API 24) or newer · arm64, armv7, x86_64 or x86.

### Size

AlpDroid uses Alpine's *minirootfs*, the smallest official Alpine image, so the base install is tiny:

| | Size |
|---|---|
| APK download | ~2.4 MB |
| First-run download (Alpine base system) | ~3-4 MB |
| On disk after setup (app + base system) | under 15 MB |

Anything you install afterwards (Node.js, Python, git, agents...) adds its own size on top. Packages come from Alpine's repositories, which are far smaller than other distributions'.

## Quick start

```sh
apk add --no-cache git python3 nodejs npm   # or use Settings → Quick install
git clone https://github.com/<you>/<repo>
```

- **Quick install** (Settings): Node.js, Python, git, opencode, Claude Code, alphacode, editors and more.
- **SSH from a laptop:** Settings → Network & SSH → *Start SSH server*. The app shows
  `ssh -p 8022 root@<phone-ip>` and a password; *Add SSH public key* lets you log in with a key instead.
- **Browser UI:** *Start opencode web server* serves it on your Wi-Fi at port 4096.
- **Keep it running:** allow *Unrestricted* battery use when the app asks, so servers survive a locked screen.

See the full **[User guide](docs/USER_GUIDE.md)** — also available inside the app under *Settings → Guide*.

## Everything it does

<details>
<summary><b>Terminal</b></summary>

- Independent tabs with rename, close and save-screen; tabs survive backgrounding and reopen after the app is restarted (programs that were running do not survive a restart; your files do).
- Own VT100/xterm emulator: scrollback, 256-color and truecolor, emoji and wide characters, bracketed paste, reflow on resize, mouse reporting, asynchronous search, IME and CJK composing support.
- Themes, Fira Code / JetBrains Mono, adjustable size (or pinch), optional ligatures and bell sound.
- A key row with ESC, TAB, CTRL, ALT, arrows and your own one-tap shortcut buttons (for example launchers for `alphacode` or `claude`).
- Long pastes cannot hang a tab; closing a tab stops everything it started.
</details>

<details>
<summary><b>Servers and network</b></summary>

- One-tap SSH server (OpenSSH on port 8022, generated password or your public key), running in the background.
- One-tap opencode web server on your LAN (port 4096): signed-in automatically on the phone, a copyable link for other devices, restarts itself if Android stops it.
- SSH profiles for quick reconnects, live DNS refresh after Wi-Fi/mobile switches, fallback `apk` mirrors.
- Devices panel: SD/USB drives, USB devices, network adapters and traffic, Wi-Fi scan.
</details>

<details>
<summary><b>Coding agents and GitHub</b></summary>

- Quick install for Node.js, Python, git, opencode, Claude Code, alphacode, editors and more; "Repair tool dependencies" if one won't start.
- Managed `AGENTS.md` / `CLAUDE.md` notes in your home directory so agents learn the environment, including how to drive AlpBrowser.
- `alpctl`: a local, token-guarded API for CLI agents (tabs, clipboard, notifications, settings, plugins). Off by default.
- GitHub one-tap sign-in (device flow). Opt in to let `git` and agents use the token.
</details>

<details>
<summary><b>AlpBrowser (companion app)</b></summary>

- Direct, full-speed browsing with tabs, an ad and tracker blocker, JavaScript and Desktop-site switches.
- **Capture** mode routes pages through an on-device proxy that decrypts HTTPS: full History with formatted bodies, scope, pause/edit/forward/drop of requests and responses, match-and-replace Rules, Repeater, HAR export and evidence folders.
- **Extensions** (userscripts) that stay installed and run on the sites you choose.
- **`browserctl`**: click, fill, type, press keys, read text and attributes, wait for elements, navigate and wait, run scripts from a file or stdin, screenshots, cookies, tabs, history filters and a DevTools address (`cdp`) for Playwright and Puppeteer.
- Open local access: AlpDroid and other programs on your phone control it with no token; web pages are refused.
- Install, open and update it from Settings → About → AlpBrowser. Full details: [AlpBrowser guide](docs/ALPBROWSER.md).
</details>

<details>
<summary><b>Plugins and automation</b></summary>

- Your own Settings screens defined by `plugin.json` plus scripts, or one shareable `.ad` file (import and export from Settings → Plugins).
- Buttons, fields, scheduled jobs and background scripts. Every plugin must be reviewed and approved by you before it runs.
</details>

<details>
<summary><b>Files and backup</b></summary>

- File browser for Android and Alpine sides: copy, move, rename, compress, extract, share, *Open terminal here*; progress notifications for long jobs.
- Backup and restore the whole Alpine system to a single `.tar.gz` (in the app folder or a file you choose), with restore pre-checks and optional weekly automatic backups.
- Export and import your settings.
</details>

<details>
<summary><b>Performance and reliability</b></summary>

- Smart resource manager: adaptive repaint rate, scrollback trimming under memory pressure, guest `/tmp` clean-up, frozen-session detection and per-session CPU/RAM readouts.
- Optional faster process tracing, efficiency-core pinning and automatic load balancing across cores for cooler running.
- Keep-alive service with wake lock and Wi-Fi lock; clear, non-alarming notices when Android stops the app, with shortcuts to the right battery settings.
</details>

<details>
<summary><b>App</b></summary>

- In-app updater with a filling-logo progress screen, integrity checks and automatic AlpBrowser updates.
- Home-screen widget that opens a new session in one tap.
- A built-in task-based **Guide** under Settings → Guide.
</details>

## FAQ

**Is this root or a virtual machine?** Neither. Alpine runs as ordinary processes under `proot`, which fakes
root inside the guest only. It cannot touch the rest of Android.

**Can I run graphical apps?** No — AlpDroid is a terminal environment (CLI tools, servers, build tools).

**Why does my background process stop?** Android can kill background apps. Keep the keep-alive notification
on, allow *Unrestricted* battery use, and keep the wake lock on for long jobs. Open sessions cannot survive a
system kill; files do. On Android 12+ some devices additionally limit child processes — see the
[troubleshooting section](docs/USER_GUIDE.md#19-troubleshooting).

**What is AlpBrowser and do I need it?** It is optional. It is a separate browser app that AlpDroid's agents can control, for bug-bounty
testing and web development. Install it from Settings → About → AlpBrowser; see the [AlpBrowser guide](docs/ALPBROWSER.md).

**Do the agent tools need a token or password?** Not on your phone: AlpDroid and AlpBrowser find each other automatically. AlpBrowser's
*Open local access* setting (on by default) lets programs on the phone control it without a token while still refusing web pages, and you can turn it
off. Other agent powers (`alpctl`, plugins, GitHub token sharing) are opt-in or ask for approval.

**Is it safe?** The guest is trusted like Termux: programs you run have the same access as the app. Secrets
are stored encrypted in the Android Keystore, and agent/plugin powers are opt-in. Details in
[SECURITY.md](SECURITY.md).

**Does it collect data?** No analytics, tracking or ads. See [PRIVACY.md](PRIVACY.md).

## Building from source

Requirements: JDK 17, Android SDK 34, NDK `26.3.11579264`, CMake `3.22.1`, Python 3.

```sh
./gradlew :app:assembleDebug     # fetches proot from packages.termux.dev at build time
./gradlew :app:installDebug
./gradlew :alpbrowser:assembleDebug   # the AlpBrowser companion app
```

Release signing uses `ALPDROID_*` environment variables; the release flow lives in
[`.github/workflows/alpdroid-release.yml`](.github/workflows/alpdroid-release.yml). More in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Building the whole project

AlpDroid is a multi-module Gradle project: `:app` (AlpDroid), `:alpbrowser` (AlpBrowser) and `:core-proxy` (the pure-JVM intercepting proxy
engine). Run everything with `./gradlew :core-proxy:test :alpbrowser:testDebugUnitTest :app:testDebugUnitTest`.

## How it works

<p align="center">
  <img src="assets/architecture.svg" alt="AlpDroid architecture: app UI, native PTY bridge, proot and the Alpine rootfs" width="80%">
</p>

`TerminalView` renders the hand-written `TerminalEmulator` buffer. It talks to a small native `pty_bridge`
helper that gives each shell a real PTY (resizes travel over a separate named pipe). Each session runs the
Alpine shell under `proot`, with storage bind-mounted and the device's network shared.

## Project

- [User guide](docs/USER_GUIDE.md) · [AlpBrowser guide](docs/ALPBROWSER.md) · [Changelog](CHANGELOG.md) · [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md) · [Security policy](SECURITY.md) · [Privacy](PRIVACY.md)

## Acknowledgements

[Alpine Linux](https://alpinelinux.org) · [proot](https://proot-me.github.io) (GPL-2.0, run as a separate
process, never linked into the app) via the [Termux](https://termux.dev) package repository ·
[Fira Code](https://github.com/tonsky/FiraCode) and [JetBrains Mono](https://www.jetbrains.com/lp/mono/) (SIL OFL).

## License

[MIT](LICENSE).
