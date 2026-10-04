# AlpDroid brand assets

Dark Alpine theme: background `#0D1117`, teal accent `#3ED0B8`, monospace type.

| File | Size | Use |
|------|------|-----|
| `logo.svg` | 512x512 | App icon source: terminal `~ $` + cursor block on a rounded square. |
| `banner.svg` / `banner.png` | 1600x600 | README header. |
| `social-preview.svg` / `social-preview.png` | 1280x640 | GitHub *Social preview* image (upload under Settings -> General). |
| `store-graphic.svg` | 1024x500 | Store feature graphic. |
| `architecture.svg` | 1200x700 | "How it works" diagram used in the README. |
| `screenshots/` | - | Real on-device screenshots (see below). |

The SVGs are the sources; the PNGs are rendered from them.

## Re-rendering PNGs

Any SVG renderer works. With a headless Chromium:

```sh
chrome --headless --no-sandbox --hide-scrollbars --window-size=1280,800 \
  --screenshot=/tmp/social.png file://$PWD/assets/social-preview.svg
convert /tmp/social.png -crop 1280x640+0+0 +repage assets/social-preview.png
```

## Launcher icon

Import `logo.svg` via Android Studio -> *File -> New -> Image Asset* -> Foreground Layer -> Path, then
generate the `mipmap-*` densities. Do not hand-place PNGs in `app/src/main/res/`.

## Screenshots

Must be **real captures from a device** (system screenshot or `adb exec-out screencap -p`), never mock-ups.
Save PNGs in `assets/screenshots/` and keep personal data out of frame (notifications, private paths,
tokens). Suggested set: terminal with a running tool, the Settings panel, the file browser, the SSH server
dialog. Phone-sized portrait, about 1080x2400.
