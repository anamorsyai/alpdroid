# Privacy Policy — AlpDroid and AlpBrowser

Last updated: 2026.

AlpDroid is an offline-first terminal app, and its companion browser AlpBrowser follows the same rules. There are **no accounts, no
analytics, no ads, and no third-party tracking SDKs**. We collect nothing.

## Data Stays on Your Device

Everything AlpDroid stores lives on your device:

- **Alpine rootfs** — the Linux guest filesystem in app-private storage.
- **Backups** — `.tar.gz` rootfs archives you choose to create, stored where
  you save them.
- **Plugins and shortcuts** — your custom command shortcuts and plugin
  scripts, in app-private storage.
- **Settings** — theme, font size/family, keep-alive, backup, and agent-access
  preferences (`alpineterm_settings` SharedPreferences).
- **GitHub token** — stored AES-GCM encrypted under a non-exportable Android
  Keystore key, never in plaintext. Only present if you sign in with GitHub.

Nothing above is transmitted to us. There is no server side.

### AlpBrowser

- **Browsing data** — cookies, cache, open tabs and settings stay in AlpBrowser's private storage on your device; you can clear
  them from its ⋮ menu.
- **Captured traffic** — when you turn on Capture, requests and responses (which can include cookies and tokens) are kept in
  memory on the device for History, Repeater and export. They are never uploaded and are discarded when AlpBrowser stops. Files
  you export (evidence folders, HAR) go where you save them.
- **Control access** — the control API listens on `127.0.0.1` only. See [SECURITY.md](SECURITY.md) for how local access works.
- AlpBrowser requests only `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` and the foreground-service permissions it
  needs to keep running while you work in AlpDroid. It has no analytics, ads or accounts.

## Network Use

The app uses the network only for these user-visible purposes:

- **Alpine package mirrors** — `apk` inside the guest fetches packages
  directly from Alpine mirrors.
- **GitHub API / OAuth** — only when you sign in (OAuth Device Flow) or use a
  GitHub-backed feature; traffic goes to `github.com` / `api.github.com`.
- **Release checks** — the in-app updater queries GitHub releases to check
  for new versions of AlpDroid (and AlpBrowser, once installed) and download APKs you approve.
- **AlpBrowser browsing** — the sites you open in AlpBrowser are contacted directly (or through its on-device proxy when
  Capture is on); nothing goes through any server of ours.

## Android Permissions

| Permission | Why |
| ---------- | --- |
| `INTERNET` | Package downloads, GitHub API, release checks |
| `ACCESS_NETWORK_STATE` | Check connectivity before network operations |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Show/change Wi-Fi state in Devices view |
| `ACCESS_FINE_LOCATION` | Required by Android to scan/list Wi-Fi networks |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep shells and long agent/build runs alive in background (`TerminalKeepAliveService`) |
| `POST_NOTIFICATIONS` | Keep-alive status and agent-run notifications |
| `REQUEST_INSTALL_PACKAGES` | Hand a downloaded update APK to the system installer (you confirm there) |
| `WAKE_LOCK` | Keeps CPU awake (and a Wi-Fi lock held) while sessions run in the background; on by default, can be turned off in Settings |
| `VIBRATE` | Haptic/notification feedback |
| `READ/WRITE_EXTERNAL_STORAGE` (max SDK 29) | Legacy scoped-storage fallback on API 24–29 |
| `MANAGE_EXTERNAL_STORAGE` | Full shared-storage access on API 30+ so `/sdcard` bind-mounts into the guest |

Location data is never collected or stored; the location permission exists
solely because Android requires it for Wi-Fi scanning.

## Contact

Questions: open an issue on the GitHub repository.
