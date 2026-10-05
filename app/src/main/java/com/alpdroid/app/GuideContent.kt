package com.alpdroid.app

/** Text for Settings → Guide. Plain-language and task-based; developer notes live in the last section only. */
object GuideContent {
    data class Section(val title: String, val summary: String, val body: String)

    val sections = listOf(
        Section(
            "Getting started",
            "The basics in one minute",
            """AlpDroid gives you a real Linux command line (Alpine Linux) on your phone. No root needed.

• Tap + at the top to open another tab. Each tab is its own session.
• Long-press a tab to rename it, close it, or save everything on its screen to a text file.
• Swipe left or right on the screen to switch tabs.
• Drag up or down to scroll back through earlier output.
• Pinch with two fingers to make the text bigger or smaller.
• Long-press text to select it, then choose Copy.
• The row above the keyboard has CTRL, ALT, arrows, ESC and TAB. Tap CTRL or ALT once, then the next key.
• Swipe in from the left edge to open your files.

Tip: tabs are temporary, like any terminal. If the app is closed or updated, running programs stop. Your files stay, and Backup keeps a copy.""",
        ),
        Section(
            "Display, themes & keys",
            "Colors, text size, your own buttons",
            """Settings → Display & Theme:
• Theme changes the colors of the whole app, including text already on screen.
• Font and size — or just pinch the screen.
• Ligatures turns things like -> into single symbols (Fira Code font).
• You can hide the row of extra keys if you don't need it.
• Bell sound plays a tone when a program wants your attention.

YOUR OWN BUTTONS
Add a button to the key row that types a command for you in one tap, for example `git status`. Set them up in the same screen.""",
        ),
        Section(
            "Sessions & keep-alive",
            "Keeping long tasks running",
            """Android likes to stop apps that aren't on screen. To keep a long task going (a build, a coding assistant):

• Keep sessions alive (Settings → Sessions & Background) is on by default. You'll see a notification saying how many sessions are running. Tap it to come back, or tap Exit to close everything.
• The wake lock (on by default) also keeps the processor and Wi-Fi awake, so servers and long jobs keep running with the screen off. It uses more battery; turn it off in Settings if you don't need that.
• Smart resource manager (on by default) slows terminal repainting when the phone is warm or in battery saver, trims old scrollback when memory is short, closes a frozen session that keeps the CPU busy, and shows each session's CPU and RAM. It's in the same screen.
• If background processes still die on Android 12+, use "Copy adb fix for killed processes" in the same screen.

If your phone still stops the app, look for battery or "background activity" settings for AlpDroid in Android and allow it to run in the background.""",
        ),
        Section(
            "Packages & Quick Install",
            "Adding tools",
            """Settings → Packages & Toolchains lets you install popular tools with one tap: Node.js, Python, git, curl, opencode, Claude Code and more. The command appears in your current tab so you can watch it work.

• opencode is installed into /root/.opencode/bin and is ready to use straight away, in that tab and every new one. Start it by typing `opencode` (or `opencode2`).
• If a tool won't start after installing, tap "Repair tool dependencies" and try again.
• "Update package index" refreshes the list of available software.
• You can search Alpine's full software catalog by name and install anything you find.""",
        ),
        Section(
            "Files & operations",
            "Browsing, copying, compressing",
            """Swipe in from the left edge to see your files. You can switch between your phone's storage and the Alpine files.

• Long-press a file or folder for options: copy, move, rename, delete, compress, extract, share, and "Open terminal here".
• Long-press one item, then tap others to work on several at once.
• Longer jobs (copy, move, compress, extract, backup, restore) show progress in a notification. Tap it to return to the app.
• Files you keep under /sdcard are also visible to your other Android apps.""",
        ),
        Section(
            "Backup & restore",
            "Keeping your setup safe",
            """Settings → Backup & Storage:
• Backup saves your whole Alpine setup into one file. "App folder" is quick but is deleted if the app is uninstalled — "chosen file" lets you save into Downloads, an SD card, or the cloud so it survives.
• If storage is nearly full you'll get a warning first.
• Automatic weekly backup can do it for you. It keeps the three newest automatic backups and never touches the ones you made yourself.
• Restore brings back a chosen backup. It replaces everything currently in Alpine and closes your tabs, so use it deliberately.
• Reinstall Alpine starts fresh with a clean copy. Anything stored inside Alpine (outside /sdcard) is erased — back up first.
• Export/Import settings saves and loads your theme, font and buttons.

Your plugins and their saved values are included in every backup.""",
        ),
        Section(
            "Network, SSH & opencode web",
            "Connecting and sharing",
            """Settings → Network & SSH:
• Shows your phone's address on your Wi-Fi.
• Save SSH connections (server, port, user) and reconnect with one tap.
• "Start SSH server" runs OpenSSH on port 8022 in a new tab so you can log in from a laptop on the same Wi-Fi: ssh -p 8022 root@<phone address>. The first run installs openssh; a random root password is generated, shown and copied for you (kept in /etc/alpdroid/ssh_password — delete that file to get a new one). Anyone on the network with the password gets full access, so only use trusted networks. Stop with Ctrl+C in that tab.
• "Add SSH public key" lets a laptop log in with its key (paste the contents of ~/.ssh/id_ed25519.pub) instead of the password.
• A web server you start in a tab can be opened on the phone itself at 127.0.0.1 with the port number.

OPENCODE IN YOUR BROWSER
"Start opencode web server" runs opencode's web version so you can use it from a browser on another device on the same Wi-Fi.
• You choose opencode or opencode2 and the server opens in a new tab.
• It prints a generated password, which the app catches and shows you with a Copy button — enter it in the browser. Anyone on that network with the password gets full access. Only use this on a network you trust.
• Then open http://<phone address>:4096 on your other device.
• Stop it any time with Ctrl+C in that tab.""",
        ),
        Section(
            "Devices",
            "SD cards, USB, network, Wi-Fi",
            """Settings → Devices shows what your phone is connected to. It refreshes when you plug something in or out — tap Refresh to update it (and to rescan Wi-Fi) yourself.

• Drives: a plugged-in SD card or USB stick appears with how full it is. Open a new tab and find it at /mnt/<its name>. Allow "all files access" in Backup & Storage first.
• USB devices: shows what's attached. "Grant access" gives Android's permission to use it.
• Network: which connection is active, your addresses, and traffic per connection.
• Wi-Fi networks: shows what's nearby. Turn on Location for this — Android requires it for Wi-Fi lists. Tap Connect to open Android's Wi-Fi settings and join one.

Formatting drives and installing system images from here aren't available yet.""",
        ),
        Section(
            "GitHub sign-in",
            "Use your GitHub account in the terminal",
            """Settings → Agent access & GitHub:
1. Tap "Sign in with GitHub".
2. Your browser opens GitHub's page. Check it's the right account and tap Authorize.
3. You're signed in. Return to the app (it tries to come back by itself; if not, press Back or tap the notification).

Your sign-in is stored safely and encrypted on your phone.

USING IT
Turn on "Let agents use my GitHub token" and `git` in the terminal (clone, push, pull) works with your account without asking for a password.

SIGNING OUT
Tap Sign out to remove it from the phone. To also cancel the permission on GitHub, remove AlpDroid at github.com/settings/applications.""",
        ),
        Section(
            "Agent access",
            "Let a coding assistant control the app",
            """Coding assistants running in a tab (like opencode or Claude Code) can be allowed to control AlpDroid: change the theme, open and read tabs, type commands, copy text, show notifications and more.

• It's OFF until you turn it on in Settings → Agent access & GitHub.
• When on, any program running in a tab can do this — including software you've installed — so only turn it on when you want it.
• Changes that affect battery or background behavior always ask you first.
• "Regenerate token" cuts off anything that had access before.

HOW AGENTS LEARN ABOUT THE APP
AlpDroid saves a short note about itself where coding assistants look on their own (AGENTS.md, CLAUDE.md and similar in the home folder). It explains what the environment is and what to avoid, and — when Agent access is on — how to use `alpctl`. Anything you wrote in those files is kept; only a marked block is managed. You can switch the note off in the same screen, and type `alpctl about` to read it.

Try it: turn it on, open a tab and type `alpctl` to see what's available.""",
        ),
        Section(
            "Plugins",
            "Add your own screens and buttons",
            """Plugins let you add a custom screen to Settings with fields and buttons that run small scripts for you — for example a "deploy" button or a "backup my notes" button.

GET STARTED
1. Settings → Plugins → Create sample plugin.
2. Tap Open on it, change the fields, and press a button. The result appears below.

WHO MAKES THEM
You, or a coding assistant — just ask it to "create an AlpDroid plugin that does X". Nothing runs until you've seen the script and tapped Allow, and you're asked again if it changes.

GOOD TO KNOW
• Your entries are remembered per plugin.
• Plugins and their entries are included in your backups.
• Delete a plugin from the Plugins list.""",
        ),
        Section(
            "Scheduled & background scripts",
            "Jobs that run on their own",
            """A plugin can run a script every few minutes, or keep one running in the background.

• Open the plugin and use the switches under Automation.
• "Run now" runs a job immediately. "View log" shows what it printed.
• Jobs keep going while the AlpDroid notification is showing. After restarting your phone, open the app once to start them again.
• The notification's Exit button pauses all jobs until you next open the app.
• Times are approximate — a job set for every 10 minutes runs about every 10 minutes.""",
        ),
        Section(
            "Common questions",
            "Quick fixes",
            """Lines pile up when I press the up arrow.
Open a new tab. The prompt is set when a tab starts.

opencode won't quit.
Type `opencode service stop`, then `exit`.

My drive doesn't show up.
Open a new tab after plugging it in, and check "all files access" is allowed. Only formats Android can read (usually FAT32 or exFAT) work.

The Wi-Fi list is empty.
Allow the location permission and turn Location on.

A build is slow.
Linux tools run through a compatibility layer, so heavy jobs (big installs or builds) take longer than on a computer.

Something else is wrong.
Try opening a new tab. If that doesn't help, Backup first, then Reinstall Alpine.""",
        ),
        Section(
            "For developers & agents",
            "Commands and file formats (optional)",
            """Everything here is optional — you don't need it to use the app.

alpctl — control the app from the terminal (needs Agent access on). Run `alpctl` for the list. Examples:
  alpctl set theme dracula
  alpctl tab send 2 "ls -la"
  alpctl tab screen 2 100
  alpctl notify "Done" "Build finished"
  alpctl github token
  alpctl plugin add ./my-plugin

PLUGIN FILES
Share a plugin as ONE file: Settings → Plugins → "Export as .ad" / "Import plugin file (.ad)". A .ad file is JSON with "alpdroid":1, "id", the manifest keys below, and "files":{"script.sh":"...text..."} (schema: docs/alpdroid-plugin.schema.json in the repo). Importing never runs anything; you review and Allow first.

A plugin is a folder ~/.alpdroid/plugins/<name>/ with a plugin.json and scripts:
{
  "title": "Deploy helper",
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
Field types: text, number, toggle, select (with "options"). Each field reaches the script as an environment variable named FIELD_<ID> (toggles are 1 or 0).""",
        ),
    )
}
