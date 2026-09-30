# AlpDroid brand assets

Hand-authored SVG **sources**. Dark Alpine theme: bg `#0D1117`, teal accent `#3ED0B8`, monospace.

| File | Size | Use |
|------|------|-----|
| `logo.svg` | 512x512 | App icon source. Terminal `~ $` + cursor block on rounded square. |
| `banner.svg` | 1600x600 | README / GitHub social preview. Logo mark + wordmark + tagline. |
| `architecture.svg` | 1200x800 | Docs diagram. Terminal-only: App UI ↔ PTY bridge (C) ↔ proot + Alpine rootfs, plus alpctl / plugins / GitHub OAuth. No proxy. |
| `store-graphic.svg` | 1024x500 | Play Store feature graphic. Logo + tagline + 3 chips (Tabs, Plugins, No root). |

## Exporting PNGs

SVGs are sources only — Android and Play need PNGs:

1. **Launcher icon (logo):** import `logo.svg` at **1024x1024** via
   Android Studio → *File → New → Image Asset* → Foreground Layer → Path →
   `assets/logo.svg`, then generate `mipmap-*` densities. Do not hand-place PNGs in `app/src/main/res/`.
2. **Banner / store graphic:** export in any SVG editor (Inkscape, Figma,
   `rsvg-convert`, `resvg`) at exact pixel sizes (1600x600, 1024x500) for upload.
3. **Screenshots:** must be captured **on-device** (real phone, `adb screencap`
   or system screenshot) showing the actual terminal UI. Save PNGs into
   `assets/screenshots/` — never mock them up from SVG.

## Validity

Keep SVGs clean: single root `<svg>` with `xmlns`, matching open/close tags,
only standard shapes + `<text>` with monospace stack. Rough check:

```sh
for f in assets/*.svg; do echo "== $f"; grep -o '<svg' "$f" | wc -l; grep -o '</svg>' "$f" | wc -l; done
```
