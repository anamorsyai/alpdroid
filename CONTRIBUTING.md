# Contributing to AlpDroid

## Build

Requirements: **JDK 17**, Android SDK, and the Gradle wrapper in this repo.

```sh
# 1. Fetch the proot bootstrap (Alpine rootfs + proot binary)
python3 scripts/fetch_proot.py

# 2. Build / install
./gradlew assembleDebug
./gradlew installDebug
```

`scripts/fetch_proot.py` downloads the guest rootfs pieces into the assets
staging area; without it the app builds but the guest has nothing to boot.

## Code Style

- Follow the official Kotlin style (4-space indent, `camelCase` members).
- Match surrounding code. Keep functions small; no third-party terminal
  libraries — the emulator, PTY bridge, and views are hand-written.
- No emojis in code, commit messages, or user-visible strings.

## Pull Requests

1. Fork and branch from `main`.
2. Keep PRs focused — one change per PR.
3. Describe what changed and how you tested it (device/API level).
4. Ensure `./gradlew assembleDebug` passes before pushing.

## Rules

- **Never commit keystores, passwords, OAuth client secrets, or tokens.**
  `keystore.properties` and `local.properties` are local-only and must stay
  out of version control. Report accidental commits immediately.
- Security-sensitive changes (Keystore handling, agent bridge auth, backup
  restore paths, plugin execution) need extra review — see `SECURITY.md`
  for the trust model.
- Update `CHANGELOG.md` for user-visible changes.
