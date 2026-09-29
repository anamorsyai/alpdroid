package com.alpdroid.app

import java.io.File

/**
 * A description of this environment for coding agents (opencode, Claude Code, Codex, Gemini CLI, ...),
 * which don't know an app like this exists. Written into the files those tools load on their own —
 * inside a marked block, so anything the user put in the same files is preserved — and removed again
 * when the user switches it off. Also saved as /etc/alpdroid/about.md for `alpctl about`.
 */
object AgentContext {
    private const val BEGIN = "<!-- alpineterm:begin (managed by AlpineTerm — edits inside this block are overwritten) -->"
    private const val END = "<!-- alpineterm:end -->"

    /** Where each tool looks for global instructions (relative to the guest's /root). */
    private val TARGETS = listOf(
        ".config/opencode/AGENTS.md",
        ".claude/CLAUDE.md",
        ".codex/AGENTS.md",
        ".gemini/GEMINI.md",
    )

    /** Earlier versions also wrote ~/AGENTS.md, which opencode and Codex then loaded a second time as
     *  a "project" file for everything under /root — only ever cleaned up now. */
    private val LEGACY_TARGETS = listOf("AGENTS.md")

    private val lock = Any()
    @Volatile private var cachedVersion: String? = null

    fun appVersion(context: android.content.Context): String = cachedVersion ?: (runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?").also { cachedVersion = it }

    fun text(version: String, agentAccessOn: Boolean): String = """
# You are running inside AlpineTerm (Android app, v$version)

This is a real Alpine Linux environment on an Android phone, inside the AlpineTerm app. It is not a normal server or desktop machine — read this before choosing tools.

## The environment
- OS: Alpine Linux (musl libc, busybox, apk). Install software with `apk add --no-cache <package>`. No systemd, no docker/podman, no sudo — you are already "root", but it is simulated (proot), not real root.
- Because of proot: no mount, no raw devices, no iptables, no kernel modules. File-heavy work (npm install, big builds) is slower than on a normal machine.
- Phone storage is at /sdcard. SD cards and USB drives appear under /mnt/<name> in tabs opened after they were plugged in.
- The network is the phone's own. A server on 0.0.0.0:PORT is reachable from other devices on the same Wi-Fi; on 127.0.0.1 only from the phone.
- The user is on a small touchscreen. Keep output short; avoid wide tables and very long lines.
- Tabs are temporary: closing or updating the app ends running processes. Files under /root and /sdcard persist, and the app's Backup includes /root. Don't rely on background processes surviving.
- opencode installs to /root/.opencode/bin (commands `opencode` and `opencode2`); that folder is already on PATH.

## Things that commonly fail here
- Prebuilt binaries built for glibc may not run (Alpine uses musl). Prefer `apk` packages. Many prebuilt tools need `apk add libstdc++`; some need `gcompat`.
- No graphical programs. Headless tools only.
- Building native Node/Python modules needs `apk add build-base python3` and is slow.

## Controlling the app
${if (agentAccessOn) AGENT_ON else AGENT_OFF}
""".trim()

    private const val AGENT_OFF = """Agent access is OFF, so the `alpctl` command does not work yet. If the user wants you to change app settings, use tabs, send notifications or add plugins, tell them to turn on Settings → Agent access & GitHub → "Let programs in the terminal control AlpineTerm". `alpctl about` prints this note."""

    private const val AGENT_ON = """Agent access is ON. Use the `alpctl` command (run `alpctl` alone for the full list):
- Look around: `alpctl state`, `alpctl settings`, `alpctl devices`, `alpctl tabs`, `alpctl plugin list`.
- Change settings: `alpctl set theme dracula`, `alpctl set font_size 16`, `alpctl set show_extra_keys off`.
- Tabs: `alpctl tab new "name"`, `alpctl tab send <id> "command"`, `alpctl tab screen <id> 100`, `alpctl tab close <id>`.
- Tell the user things: `alpctl notify "Title" "text"`, `alpctl toast "text"`, `alpctl clip set "text"`, `alpctl open <url>`.
- Add a button: `alpctl shortcut add "Label" "command"`.

Rules to follow:
- Only change what the user asked for. Keep-alive, wake lock and auto-backup changes pop up on screen for the user to confirm.
- Do not read, type into, or close other tabs unless the user asked — they may hold secrets or unrelated work.
- Do not print the GitHub token. If the user signed in to GitHub and allowed it, plain `git` already authenticates.
- When a long task finishes or needs the user's attention, send `alpctl notify "Done" "what finished"`.

Custom screens (plugins): to give the user their own fields and buttons, create a folder with a `plugin.json` and scripts, then install it with `alpctl plugin add <folder>`. The user reviews the scripts and taps Allow before anything runs. Example plugin.json:
{
  "title": "Deploy helper",
  "description": "Shown under the title",
  "fields": [
    {"id": "host", "type": "text", "label": "Host", "default": "example.com"},
    {"id": "dry", "type": "toggle", "label": "Dry run", "default": true}
  ],
  "buttons": [
    {"id": "go", "label": "Deploy", "script": "deploy.sh"},
    {"id": "watch", "label": "Watcher", "script": "watch.sh", "background": true}
  ],
  "schedules": [
    {"id": "health", "label": "Health check", "script": "health.sh", "everyMinutes": 10}
  ]
}
Field types: text, number, toggle, select (with "options"). Each field reaches the script as the environment variable FIELD_<ID> in capitals (toggles are 1 or 0). One-shot scripts stop after 2 minutes; scheduled ones after 5."""

    private fun block(content: String) = "$BEGIN\n$content\n$END\n"

    /** Same directory + rename, so a reader (or a second writer) never sees a half-written file. */
    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    private fun upsert(file: File, content: String?) {
        val existing = if (file.isFile) file.readText() else ""
        val start = existing.indexOf(BEGIN)
        val end = if (start >= 0) existing.indexOf(END, start) else -1
        // A damaged block (marker line deleted or mangled): leave the file exactly as it is rather than
        // guess what to cut — appending another block would grow it forever, deleting could eat user text.
        if (start >= 0 && end < 0) return
        val hasBlock = start >= 0
        if (content == null) {
            if (!hasBlock) return
            val before = existing.substring(0, start).trimEnd('\n')
            val after = existing.substring(end + END.length).trimStart('\n')
            val rest = listOf(before, after).filter { it.isNotBlank() }.joinToString("\n\n")
            if (rest.isBlank()) file.delete() else writeAtomic(file, rest.trimEnd('\n') + "\n")
            return
        }
        val merged = if (hasBlock) {
            existing.substring(0, start) + block(content) + existing.substring(end + END.length).trimStart('\n')
        } else if (existing.isBlank()) {
            block(content)
        } else {
            existing.trimEnd('\n') + "\n\n" + block(content)
        }
        if (merged != existing) writeAtomic(file, merged)
    }

    /** Writes (or, with [enabled] false, removes) the managed block in each agent's global file. */
    fun sync(root: File, version: String, agentAccessOn: Boolean, enabled: Boolean) {
        val home = File(root, "root")
        val content = text(version, agentAccessOn)
        synchronized(lock) {
            runCatching {
                val about = File(File(root, "etc/alpdroid").apply { mkdirs() }, "about.md")
                val body = content + "\n"
                if (!about.isFile || about.readText() != body) writeAtomic(about, body)
            }
            TARGETS.forEach { rel -> runCatching { upsert(File(home, rel), if (enabled) content else null) } }
            LEGACY_TARGETS.forEach { rel -> runCatching { upsert(File(home, rel), null) } }
        }
    }
}
