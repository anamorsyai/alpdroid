# Security Policy

## Reporting a Vulnerability

Do **not** open a public issue for security reports.

Please use **GitHub private vulnerability reporting**
(repository → Security tab → "Report a vulnerability").
Include: affected version, steps to reproduce, and impact assessment.
We will acknowledge receipt, investigate, and coordinate a fix and disclosure timeline with you.

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.7.x   | Yes (AlpDroid and AlpBrowser 0.x) |
| < 1.7   | No (please update) |

Only the latest 1.7.x release receives security fixes.

## Scope Notes

AlpDroid runs a full Linux guest on the device, plus an opt-in local control
API ("agent access") that lets programs inside the terminal drive app features.
This defines the trust model:

- **Guest-trust model.** Enabling agent access trusts the *entire guest*.
  Anything running in the Alpine guest (including CLI coding agents) can call
  the local control API with the same authority. Only enable it if you trust
  everything running inside the guest, and rotate the agent token
  (`regenerateAgentToken`) if that trust is ever in doubt.
- **GitHub token handling.** The GitHub OAuth token is stored AES-GCM
  encrypted under a non-exportable Android Keystore key (see `GitHubAuth.kt`),
  never in plain preferences. By default it is *not* exposed to the guest;
  sharing it with guest programs requires explicitly opting in
  (`agentGithubToken` in Settings).
- **SSH server.** The optional one-tap SSH server listens on all network interfaces (port 8022) and
  accepts root login by a generated password or an added public key, so anyone on the same network
  who knows the password has full guest access. Use it on networks you trust, stop it with Ctrl+C
  when done, and delete `/etc/alpdroid/ssh_password` to rotate the password.
- **AlpBrowser local access.** AlpBrowser (the companion browser) exposes a control API and an optional DevTools port on
  `127.0.0.1` only. With *Open local access* on (the default) programs on the phone can use them without a token, while any
  request that looks like it came from a web page (an `Origin` or `Sec-Fetch-*` header, or a `Host` other than
  loopback) is refused. Turn the setting off to require the control token for every request. Remote DevTools is off
  until you enable it.
- **Captured traffic.** In Capture mode AlpBrowser decrypts HTTPS with a certificate authority generated on the device, and
  History can contain cookies, tokens and passwords from the sites you test. It is kept in memory on the phone, never
  uploaded, and is lost when AlpBrowser stops; evidence files you save (`browserctl save`, HAR export) are ordinary
  unencrypted files, so keep them private. Install the AlpBrowser certificate authority inside Alpine
  (`browserctl trust`) only if you need other tools to accept captured HTTPS.
- **Backups and shared storage.** Rootfs backups (`.tar.gz`) and anything
  under shared storage (`/sdcard`) are unencrypted files — whoever can read
  the storage can read them. Keep backups somewhere private.
- **Out of scope:** vulnerabilities requiring a compromised OS, a rooted
  device used against its own owner, or social-engineering the user into
  enabling agent access / granting storage permissions.
