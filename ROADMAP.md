# AlpDroid roadmap (planned, not built yet)

## USB raw access: format, flash ISO, Ventoy
- Userspace USB mass-storage (bulk-only transport + SCSI) in the app; Android gives no raw block access without root.
- Format (FAT32/exFAT), write ISO/IMG to a drive, install Ventoy.
- Destructive: needs explicit confirmation (show drive name/size, type-to-confirm), refuse the phone's own storage, and must be tested on a spare drive first.
- Non-Android-driver USB devices (serial, other adapters) would go through the same USB host layer.

## Device bridge (terminal <-> Android APIs / custom apps)
- PARTLY DONE in 1.4.0: local control API + `alpctl` (settings, tabs, clipboard, notifications, devices, GitHub token), GitHub device-flow sign-in, opencode web server on 0.0.0.0. Still open: in-app controllable browser, intents, sensors.
- Local socket server in the app, token-authenticated, exposed to the guest (MCP/JSON-RPC) so tools like opencode can call it.
- Capabilities as plugins: controllable in-app WebView browser (navigate, read DOM, click, type, screenshots; CDP-compatible), clipboard, notifications, intents, sensors.
- Off by default, per-capability permission prompts, audit log.

## Done: plugins (1.5.0)
- Settings -> Plugins: panels from plugin.json (text/number/toggle/select fields, script buttons, live output), saved values per plugin, included in backup/restore, user approval before an added plugin runs, `alpctl plugin add|list|remove`. Scheduled and keep-running background scripts added in 1.6.0 (run while the app process is alive; nothing after reboot until the app is opened). Possible later: more field types, plugin-defined tabs on the main screen, exact-time schedules that survive process death (AlarmManager/WorkManager).

## Smaller ideas still open
- Dirty-row redraw; double/triple-tap word/line select; per-tab working-directory memory (OSC 7); per-tab badges; command-history search; per-tool key rows; safe drive eject.
- Re-try filling the leftover width on the right (centering shipped; column stretch caused vanishing lines, so avoid changing the column layout).

## Done
- Repair dependencies button, "Open terminal here", automatic weekly backup with rotation (1.3.1).
