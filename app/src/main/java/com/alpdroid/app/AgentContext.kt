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
# You are running inside AlpDroid (Android app, v$version)

This is a real Alpine Linux environment on an Android phone, inside the AlpDroid app. It is not a normal server or desktop machine — read this before choosing tools.

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
- `apk add` often exits non-zero here because a mirror hiccuped, even when the package installed. Don't chain with `&&`: run `apk add ... ; command -v <tool>` (retry once, or `apk update`) and decide from whether the tool exists.
- No graphical programs. Headless tools only.
- Building native Node/Python modules needs `apk add build-base python3` and is slow.

## Controlling the app
${if (agentAccessOn) AGENT_ON else AGENT_OFF}

## The built-in browser (AlpBrowser) — web dev & bug bounty
$BROWSER
""".trim()

    private const val AGENT_OFF = """Agent access is OFF, so the `alpctl` command does not work yet. If the user wants you to change app settings, use tabs, send notifications or add plugins, tell them to turn on Settings → Agent access & GitHub → "Let programs in the terminal control AlpDroid". `alpctl about` prints this note."""

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

    private const val BROWSER = """AlpDroid has a companion app, AlpBrowser (a separate app you may need the user to install). It is a real Chromium WebView whose traffic goes through an in-app HTTPS intercepting proxy, and it is fully controllable from here over a loopback control API — so you can browse, read and rewrite decrypted HTTPS, and automate the page for the user's own web development or authorized bug-bounty testing on targets they are permitted to test.

Reach it with the `browserctl` command (loopback 127.0.0.1). It is already installed and needs no token on this phone — AlpBrowser accepts local programs directly (its "Open local access" setting), and a token that AlpDroid provisions is used only if that setting is off. Never ask the user to copy a token. Just:
  browserctl ping                        # check it is reachable
  browserctl trust                       # make Alpine's curl/python trust the intercepted HTTPS (appends the proxy CA)
History, rules and interception only see traffic while Capture is on; without it the page browses directly. Every browserctl command exits non-zero when it did not really succeed (check the exit status, not just the text). If ping says AlpBrowser is not linked, the app is not installed yet — the user installs it from AlpDroid -> Settings -> About -> AlpBrowser. Once it is installed AlpDroid links to it automatically (within a few seconds, also in shells that are already open); `navigate`, `eval` and `clear` need a page, so if they report an error the user has to open AlpBrowser once so it has a page.

By default AlpBrowser browses directly (fast) and History lists only the URLs loaded. For decrypted requests/responses (and rules or interception) route it through the proxy first: `browserctl capture on` (`browserctl mode bugbounty` does this too; `intercept on` implies it). Turn it off again with `browserctl capture off` when you only need to browse.

Two modes (set the one that fits the task): `browserctl mode bugbounty` or `browserctl mode webdev`.

Both modes:
- browserctl navigate <url> [--wait] [--state load|domcontentloaded|networkidle] [--timeout ms]   open a page; --wait blocks until it has loaded and reports the final URL
- browserctl eval '<js>' | eval --file <path> | eval --stdin [--await]   run JavaScript in the live page (multi-line, backticks and non-ASCII are fine; --await waits for a promise)
- browserctl history [host] [--method M] [--status N] [--since ISO] [--limit n] [--page n] [--compact]   captured requests (only the newest 500 are kept; the answer says how many were dropped); browserctl get <id> for one in full: bodies are base64 of the DECODED bytes (gzip already removed), with bodyText and bodyPretty alongside
- browserctl save <id> --dir <dir>      write request.txt, response.txt, meta.json and the raw bodies to disk; browserctl har [--out file] exports HAR 1.2
- browserctl tabs / browserctl tab new [url] [--profile name] | tab switch <id> | tab close <id>   several pages at once. Without `--tab`, commands act on the tab in FRONT, which another agent may change: when more than one agent (or script) shares the browser, open your own tab and put `browserctl --tab <id>` before every page command (navigate, eval, click, fill, text, html, waitfor, waitnav, shot, cookies, setcookie, console) and never use `tab switch`. `console` with --tab shows only that tab's console. A tab opened with --profile has its OWN cookies and storage, so two logins can sit side by side (IDOR / cross-tenant checks); `cookies` and `setcookie` act on the front tab's profile; browserctl profile list | profile delete <name>
- browserctl frames <id> [--from n] [--limit n] [--compact]   the messages of a WebSocket (both directions) or the events of an event stream; history entries for them carry kind "websocket" or "sse" (Capture must be on while the connection runs)
- browserctl clear [cookies|cache|storage|all]
- browserctl ca                         print the proxy root CA (PEM)

Bug bounty (intercept & tamper):
- browserctl scope <pattern> ...        only capture these hosts (globs, e.g. '*.target.com'); empty = everything
- browserctl intercept on [hostFilter] [--responses] [--all-assets]  pause matching requests (add --responses to also pause replies; images/CSS/JS are skipped unless --all-assets); browserctl pending shows parked messages
- browserctl resolve <holdId> [forward|drop]   release a parked message
- browserctl resolve <holdId> edit [--method M] [--target PATH] [--status N] [--header 'Name: v']... [--body TEXT]   change a parked message, then release it
- browserctl rule add <REQ_HEADER|REQ_BODY|RES_HEADER|RES_BODY> <find> <replace> [host]   auto match-and-replace
- browserctl rule header <req|res> 'Name: value' [host]   set or add a header on all matching traffic
- browserctl rules / browserctl rule del <id>
  (For `rule add`, REQ_HEADER/RES_HEADER with a <find> rewrites header VALUES containing that text; to set a whole header use `rule header`. Response bodies are delivered uncompressed so body rules match.)
- browserctl resend <METHOD> <url> [--header 'Name: v']... [--body TEXT]   Repeater: send a request again and print the response (status, headers, base64 body)

Web development:
- browserctl console [limit]            read the page's console.log/warn/error and uncaught JS errors
- Chromium remote debugging (CDP/DevTools) is enabled, so a DevTools client can also attach to the live page.

Driving the page without Playwright (acts on the tab in front; every command exits non-zero on failure and prints the reason):
- browserctl click <sel> | fill <sel> <text> | type <sel> <text> [--delay ms] | press <key> | text <sel> | attr <sel> <name> | html [sel] | exists <sel>
- browserctl waitfor <sel> [--state visible|hidden|attached] | waitnav [--state ...]   (all element commands wait up to 10 s by default; --timeout ms changes it)
- browserctl shot [--selector css] [--full-page] [--out file.png] [--format png|jpeg]   screenshot; the Browser screen must be on screen (open AlpBrowser)
- browserctl cookies [--domain d] (every attribute is listed while `browserctl cdp` has Remote DevTools on) | setcookie <name> <value> [--domain d] [--path p] [--secure] [--httponly] [--samesite Lax|Strict|None] [--expires epoch] [--max-age s]
- browserctl cdp   turns remote debugging on and prints a WebSocket URL (it carries the control token only when Open local access is off) for Puppeteer/Playwright `connectOverCDP`; the page being driven is the same one the proxy is capturing. `cdp off` turns it off.

Rules: only test targets the user is authorized to test; never exfiltrate captured data anywhere; treat tokens/cookies in captured traffic as secret.

Run `browserctl` with no arguments for the full command list."""

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
