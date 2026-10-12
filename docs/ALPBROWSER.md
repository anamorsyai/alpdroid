# AlpBrowser Guide

**AlpBrowser** is the companion browser for [AlpDroid](../README.md). It is a full web browser for your phone that
the AlpDroid agent — or you — can drive completely, built for two jobs: **bug-bounty testing** and **web
development**. It is a separate app (`com.alpdroid.browser`), signed with the same key as AlpDroid, so the two
recognise each other automatically.

Contents: [Get started](#get-started) · [Browsing](#browsing) · [Capture, Proxy and History](#capture-proxy-and-history) ·
[Intercept](#intercept) · [Rules](#rules) · [Scope](#scope) · [Repeater](#repeater) · [Extensions](#extensions) ·
[Driving AlpBrowser from the agent](#driving-alpbrowser-from-the-agent) · [Command reference](#command-reference) ·
[Recipes](#recipes) · [Local access and security](#local-access-and-security) · [Limits and good to know](#limits-and-good-to-know) ·
[Troubleshooting](#troubleshooting)

## Get started

1. In AlpDroid open **Settings → About → AlpBrowser** and tap **Install AlpBrowser**. AlpDroid downloads the latest
   signed release and hands it to Android's installer. Later the same place offers **Open AlpBrowser** and
   **Check for browser updates**; AlpDroid also checks for updates on its own, but only once AlpBrowser is installed.
2. Open AlpBrowser once. From then on the agent in any AlpDroid tab can reach it with the `browserctl` command, which
   AlpDroid installs for you:

   ```sh
   browserctl ping
   ```

There is nothing to configure and no token to copy. AlpBrowser keeps running in the background while you talk to the
agent in AlpDroid; a small notification shows while it is up, with a **Stop** button.

## Browsing

AlpBrowser opens pages **directly, at full Chromium speed**, with HTTP/2 and QUIC, until you ask it to capture traffic.

- **Tabs** — open, switch and close tabs from the browser screen; links that open pop-ups become tabs. Your open pages
  are restored the next time you start the app.
- **Ad and tracker blocker** — strong and on by default, with a counter of what was blocked this session.
- **Settings sheet (⋮ menu)** — JavaScript on/off, Desktop site, Capture traffic, Open local access, Remote DevTools,
  the **Bug bounty / Web dev** mode switch, and clear cookies, cache or everything.
- **Modes** — *Bug bounty* turns on Capture and scope filtering; *Web dev* turns Capture off for the fastest browsing
  and keeps the console, DOM and network tools within reach.

## Capture, Proxy and History

Turn **Capture traffic** on (the ⋮ menu, the Proxy screen, or `browserctl capture on`) and AlpBrowser routes the page
through its on-device proxy. The proxy decrypts HTTPS using a certificate authority generated on your phone, so
**History** can show every request and response in full: method, URL, status, timing, headers and bodies.

With Capture **off**, the browser stays fast and direct, and History lists only the addresses that were loaded.

The Proxy screen has these tabs:

| Tab | What it is for |
|---|---|
| **Held** | Requests and responses paused by Intercept, waiting for you to forward, edit or drop them. |
| **History** | Searchable list of captured requests with filters; open any entry to read the request and response. Bodies are decoded (gzip and similar) and shown as formatted JSON, HTML or form fields. |
| **Rules** | Match-and-replace rules that rewrite traffic automatically. |
| **Scope** | Choose which hosts are recorded. |
| **Options** | Intercept switches (pause responses too, skip images/CSS/JS, only pause hosts containing…), the Capture switch, and live counters of the data that really went through the proxy. |

WebSockets and event streams (SSE) are captured too: the handshake is one History entry and every message is kept (up to 2,000 per
connection, in both directions); read them with `browserctl frames <id>`. To make WebSocket messages readable, the browser's request for
the optional compression extension is not forwarded, so the server sends them uncompressed. Event streams are passed on live and are not
paused by Intercept. Editing or injecting a WebSocket message is not supported.

History keeps the most recent 500 requests within a memory budget; older ones are discarded. Use **Save**
(`browserctl save`) or **HAR** export to keep what matters.

## Intercept

Switch interception on from the Held tab (or `browserctl intercept on`), optionally for one host, and matching requests wait for you. For each held request you can
**Forward**, **Drop** or **Edit** (method, path, headers, body). With *Intercept responses* on you can do the same
with what the server sends back. Static assets (images, fonts, styles) are skipped unless you ask for them, so a page
does not stall on dozens of pauses. Any request can also be sent to the Repeater for hand-tuned replays.

## Rules

Rules rewrite traffic as it passes through — no pausing needed. A rule targets one part of a request or response
(request header or body, response header or body), a text to find, its replacement, and optionally one host. Rules can
be switched on and off individually, and they are kept between runs.

## Scope

The scope is the list of hosts AlpBrowser records, one pattern per line, with `*` as a wildcard — for example
`*.example.com`. An empty scope records every site. The Proxy screen marks the Scope tab while one is active, so
History never looks empty without a reason.

## Repeater

Pick any request from History (or write one from scratch), change it, and send it as often as you like. The response is
shown formatted, with status, headers, body and timing.

## Extensions

Extensions are **userscripts**: small JavaScript files that run on the sites you choose. Install one, give it the
addresses it applies to, and it stays installed and runs every time those pages load. (Android's WebView cannot run
Chrome or Firefox extension packages, so AlpBrowser uses userscripts for the same purpose.)

## Driving AlpBrowser from the agent

Anything you can do by hand, the agent can do with `browserctl`. A typical flow is a few plain commands — no
hand-written JavaScript needed:

```sh
browserctl capture on
browserctl navigate https://example.com/login --wait
browserctl fill 'input[name=email]' 'me@example.com'
browserctl fill 'input[name=password]' 'secret'
browserctl click 'button[type=submit]'
browserctl waitnav
browserctl history --host example.com --compact
```

Every element command waits for its target to appear, and fails with a clear message and a non-zero exit status if it
never does, so scripts can rely on exit codes (`browserctl click '#go' || echo "not found"`).

For tools that speak the Chrome DevTools Protocol, `browserctl cdp` prints a WebSocket address that Playwright or
Puppeteer can attach to — they then drive the very page AlpBrowser is showing and capturing:

```js
const browser = await chromium.connectOverCDP(process.argv[2]);   // the address printed by `browserctl cdp`
```

AlpDroid also keeps built-in notes in your home directory, so coding agents such as opencode and Claude Code learn the
commands by themselves.

## Command reference

Selectors are standard CSS selectors. Time values are in milliseconds. Every command exits with `0` on success and
`1` when the action itself failed (element not found, page not ready, request rejected); `2` means a usage mistake.

### General

| Command | What it does |
|---|---|
| `ping` | Check that AlpBrowser is running and reachable. |
| `mode [bugbounty\|webdev]` | Show or change the mode. |
| `capture [on\|off]` | Show or change whether pages go through the proxy. Needed for decrypted History, rules and interception. |
| `ca` | Print the proxy's certificate authority (PEM). |
| `trust` | Install that certificate authority in Alpine's trust store so tools such as `curl` and `git` accept captured HTTPS. |
| `clear [cookies\|cache\|storage\|all]` | Clear browsing data. |

### Pages and elements

| Command | What it does |
|---|---|
| `navigate <url> [--wait] [--state load\|domcontentloaded\|networkidle] [--timeout ms]` | Open a page; with `--wait`, return when it has loaded and report the final address, title, status and redirects. |
| `waitnav [--state …] [--timeout ms]` | Wait for the current navigation to finish. |
| `click <sel>` | Click an element (a real tap at its position). |
| `fill <sel> <text>` · `type <sel> <text> [--delay ms]` | Set a field's value, or type it key by key. An empty text clears the field. |
| `press <key>` | Press a key such as `Enter`, `Tab` or `Escape`. |
| `text <sel>` · `attr <sel> <name>` · `html [sel]` | Read an element's text, an attribute (`value`, `checked`, `selected` and `disabled` return the live state), or HTML. |
| `exists <sel>` | Report whether the element exists and is visible (exit `1` if not). |
| `waitfor <sel> [--state visible\|hidden\|attached] [--timeout ms]` | Wait for an element. |
| `eval '<js>'` · `eval --file <path>` · `eval --stdin` `[--timeout ms]` | Run JavaScript in the page and print its value. Works on sites with a strict Content-Security-Policy. The script can be an expression (a promise or `await` is waited for), a list of statements (the value of the last expression is printed), or statements that `return` a value. Long, multi-line scripts and any language are supported. |
| `shot [--selector css] [--full-page] [--out path] [--format png\|jpeg]` | Screenshot of the visible page, one element, or the whole page. Needs the Browser screen to be on screen. |
| `cookies [--domain d]` | Read cookies. By default Android gives name and value only; while Remote DevTools is on (`browserctl cdp`) every attribute is listed: domain, path, expiry, `httpOnly`, `secure`, `sameSite` and whether it is a session cookie. |
| `setcookie <name> <value> [--domain d] [--path p] [--secure] [--httponly] [--samesite Lax\|Strict\|None] [--expires epoch] [--max-age s]` | Set a cookie with its attributes. |
| `cdp [port]` · `cdp off` · `cdp --json` | Turn on remote debugging and print the WebSocket address for Playwright or Puppeteer. |
| `--group <name>` before any command | Gives an agent its **own isolated set of tabs**: `browserctl --group alice tab new <url>`, then every command with the same `--group` runs only in alice's tabs. A group has its own cookies and storage (so two logins never meet), its own front tab (it does not move the tab on screen and is not moved by other agents), and its own console; `clear` inside a group affects only that group. `group list` shows the groups, `group close <name>` closes its tabs and wipes its data. History, intercept, rules, scope and capture stay shared. Needs a WebView with profile support (Chrome 128+). |
| `--tab <id>` before any page command (also `BROWSERCTL_TAB=<id>`) | Aim the command at one tab instead of the tab in front. **Use this whenever several agents or scripts share the browser**: each opens its own tab (`tab new`) and passes its own `--tab`, so none of them navigates, clicks, or reads cookies in another's page. `console` then shows only that tab's messages. Screenshots still need the tab to be the one on screen. |
| `tabs` · `tab new [url] [--no-wait] [--profile name]` · `tab switch <id>` · `tab close <id>` | Manage tabs. A tab opened with `--profile` has its **own cookies and storage**, so two different logins can run side by side. Needs a WebView with profile support (Chrome 128 or newer); otherwise the command says so and no tab is opened. `cookies` and `setcookie` act on the profile of the targeted tab (the front tab unless `--tab` is given). |
| `profile list` · `profile delete <name>` | List the named profiles, or delete one with its data (not while a tab still uses it). |
| `console [limit]` | Read the page's console messages. |

### Traffic

| Command | What it does |
|---|---|
| `history [host] [--host h] [--method M] [--status N] [--contains text] [--since ISO] [--limit n] [--page n] [--compact]` | List captured requests. `--compact` prints one plain-text line per request; the default is JSON. |
| `get <id>` | One request and response in full (bodies decoded, with a formatted version alongside). |
| `save <id> --dir <dir>` | Write an evidence folder: `meta.json`, `request.txt`, `request.body`, `response.txt`, `response.body`. `--out <file>` writes request and response into one file. |
| `har [--out file] [history filters]` | Export captured traffic as a HAR 1.2 file. |
| `frames <id> [--from n] [--limit n] [--compact]` | The messages of a **WebSocket** (both directions, with timestamps, text or base64) or the events of a **Server-Sent Events** stream, for a History entry. `history` marks such entries with `kind` (`websocket` or `sse`); `--compact` shows `[websocket 12]` next to them. Needs Capture to have been on while the connection ran. |
| `resend <METHOD> <url> [--header 'Name: v']… [--body TEXT]` | Send a request and print the response. |

### Bug bounty

| Command | What it does |
|---|---|
| `scope [pattern …]` | Show or set the recorded hosts. |
| `intercept on [host] [--responses] [--all-assets]` · `intercept off` | Turn interception on or off. |
| `pending` | List held requests and responses. |
| `resolve <holdId> forward\|drop` | Release or discard a held item. |
| `resolve <holdId> edit [--method M] [--target PATH] [--status N] [--header 'Name: v']… [--body TEXT]` | Edit a held item and release it. |
| `rule add <REQ_HEADER\|REQ_BODY\|RES_HEADER\|RES_BODY> <find> <replace> [host]` · `rules` · `rule del <id>` | Manage match-and-replace rules. |
| `rule header <req\|res> 'Name: value' [host]` | Set or add a header on all matching traffic. |

## Recipes

**Collect evidence for a report**

```sh
browserctl capture on
browserctl navigate https://target.example/app --wait
# …reproduce the issue…
browserctl history --host target.example --status 500 --compact
browserctl save <id> --dir ./evidence/issue-1
browserctl har --host target.example --out ./evidence/session.har
```

**Test one request change without touching the page**

```sh
browserctl resend POST https://target.example/api/profile \
  --header 'Content-Type: application/json' --body '{"role":"admin"}'
```

**Rewrite a header on every request to one host**

```sh
browserctl rule header req 'X-Test: 1' target.example
```

**Screenshot a single element**

```sh
browserctl shot --selector '#chart' --out ./chart.png
```

## Local access and security

AlpDroid and AlpBrowser talk only over your phone (`127.0.0.1`). **Open local access** — on by default, in AlpBrowser's
⋮ menu — lets programs running on your phone control AlpBrowser without a token, so there is nothing to copy and
nothing that can expire.

- Web pages are always refused: a request that carries browser headers (`Origin`, `Sec-Fetch-*`) or an unexpected
  `Host` is rejected, so a site you open cannot reach the control port.
- Other apps on the phone can reach it, as they could any local service. If you would rather not allow that, switch
  **Open local access** off: every request then needs the control token, which AlpDroid hands to the agent
  automatically.
- Everything AlpBrowser captures stays on your phone. Captured tokens and cookies are sensitive — keep evidence files
  private and share only what a report needs.
- Test only sites you own or are authorised to test.

## Limits and good to know

- **Capture must be on** for History bodies, rules and interception; with it off the page browses directly.
- **Screenshots and DevTools need the Browser screen on screen.** Android pauses an off-screen page.
- Android can stop background apps. Keep AlpBrowser's notification showing and allow unrestricted battery use for it, as
  you do for AlpDroid.
- History holds the latest 500 requests; very large bodies (over 25 MiB) are not buffered.
- `cookies` shows name and value only unless Remote DevTools is on, in which case it lists all attributes.
- Remote DevTools (CDP) is off by default and turns on when you run `browserctl cdp`.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `browserctl ping` fails | Open AlpBrowser once, then start a new AlpDroid tab. Check it is installed from Settings → About. |
| History is empty | Check **Capture traffic** is on and the **Scope** is empty or includes the host. |
| HTTPS sites show certificate errors in other tools | Run `browserctl trust` in Alpine to install the AlpBrowser certificate authority there. |
| `shot` says the page is not on screen | Bring AlpBrowser's Browser screen to the front and retry. |
| `cdp` prints nothing | Open a page in AlpBrowser first, keep the Browser screen visible, and run `browserctl cdp` again. |
| A command times out | The page may still be loading or may have navigated; run `browserctl waitnav` and retry. |

For anything else, see AlpDroid's [User guide](USER_GUIDE.md#19-troubleshooting).
