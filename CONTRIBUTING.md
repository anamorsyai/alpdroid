# Contributing to AlpDroid

Thanks for helping! Bug reports, device test results, docs fixes and code are all welcome. Please read the
[Code of Conduct](CODE_OF_CONDUCT.md) first.

## Reporting bugs and ideas

- Search [existing issues](https://github.com/anamorsyai/alpdroid/issues) first.
- Use the bug template and include: AlpDroid version, Android version, device model, and what you did.
  For background-kill problems also say which battery setting is on.
- **Security problems:** do not open a public issue — see [SECURITY.md](SECURITY.md).

## Building

Requirements: **JDK 17**, Android SDK 34, NDK `26.3.11579264`, CMake `3.22.1`, Python 3.

```sh
./gradlew :app:assembleDebug     # fetches proot at build time (scripts/fetch_proot.py)
./gradlew :app:installDebug
```

The build needs internet access to `packages.termux.dev` (proot) and Google's Maven repository. Debug builds
use Android's auto-generated debug key unless you place an `app/debug.keystore`.

## Project layout

| Path | What lives there |
|---|---|
| `app/src/main/java/com/alpdroid/app/` | App code: `MainActivity`, sessions (`AlpineSession`, `PtySession`), `AgentBridge` (alpctl), plugins, updater, GitHub auth |
| `app/src/main/java/com/alpdroid/app/terminal/` | Hand-written terminal: `TerminalEmulator`, `TerminalView`, themes |
| `app/src/main/java/com/alpdroid/app/files/` | File browser and file operations |
| `app/src/main/cpp/` | `pty_bridge.c`, the native PTY helper |
| `scripts/` | `fetch_proot.py` (build-time proot download) |
| `docs/` | User guide |
| `assets/` | Logo, banner and diagrams (SVG sources — see `assets/README.md`) |

## Code style

- Official Kotlin style: 4-space indent, `camelCase` members.
- Match the surrounding code, including how much it explains. Comments should say *why*, not *what*.
- No third-party terminal libraries — the emulator, PTY bridge and views are hand-written on purpose.
- Keep threads in mind: PTY reading, plugin jobs and the agent bridge run off the UI thread. Anything touching
  views goes through the main thread.
- No emojis in code, commit messages, or user-visible strings.

## Pull requests

1. Fork and branch from `main`.
2. Keep each PR focused on one change.
3. Describe what changed and how you tested it (device, Android version).
4. Make sure `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes — CI runs both on every PR. Add a unit test for logic you touch (see `app/src/test/`).
5. Update [`CHANGELOG.md`](CHANGELOG.md) for user-visible changes.

## Rules that matter

- **Never commit** keystores, passwords, OAuth client secrets or tokens. `keystore.properties` and
  `local.properties` stay local. Report accidental commits immediately.
- Security-sensitive changes (Keystore handling, agent bridge auth, backup/restore paths, plugin execution,
  the SSH server) need extra review — see [SECURITY.md](SECURITY.md) for the trust model.
- Screenshots must be real on-device captures (never mock-ups); store them in `assets/screenshots/`.

## Releases (maintainers)

Releases are built by [`alpdroid-release.yml`](.github/workflows/alpdroid-release.yml) from a manual run:
bump `versionCode`/`versionName`, add a `## X.Y.Z — title` section to `CHANGELOG.md` (it becomes the release
notes), push, then run the workflow with the tag (`vX.Y.Z`). With *publish_release* off it only uploads the
signed `AlpDroid-vX.Y.Z.apk` as a workflow artifact. Signing secrets live in repository Actions secrets, never in the tree.
