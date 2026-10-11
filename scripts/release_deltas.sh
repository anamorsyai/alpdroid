#!/usr/bin/env bash
# Builds update deltas for a release: one small `*.adp` file per recent earlier release of the same app, each turning
# that release's APK into the new one (see scripts/make_apk_delta.py and ApkDelta.kt). Phones that are on one of those
# versions download the delta instead of the whole APK; everyone else still gets the full APK.
#
#   release_deltas.sh <app|browser> <new tag> <new APK> [how many earlier releases, default 3]
#
# Writes deltas/<new APK name without .apk>-from-<old version>.adp. Never fails the release: a delta that cannot be
# built is skipped, and the full APK is always published.
set -u
channel=${1:?channel (app|browser)}
tag=${2:?new tag}
apk=${3:?new APK}
count=${4:-3}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p deltas old

case "$channel" in
  app)     tag_re='^v[0-9]' ;;
  browser) tag_re='^alpbrowser-v[0-9]' ;;
  *) echo "unknown channel $channel"; exit 0 ;;
esac

# Earlier, published (not draft, not pre-release) releases of this app, newest last.
tags=$(gh release list --limit 100 --json tagName,isDraft,isPrerelease \
  -q '.[] | select((.isDraft | not) and (.isPrerelease | not)) | .tagName' 2>/dev/null | grep -E "$tag_re" || true)
earlier=$( { printf '%s\n' "$tags" | grep -vxF "$tag"; printf '%s\n' "$tag"; } | sort -V | awk -v t="$tag" '$0 == t { exit } { print }' | tail -n "$count")

base=${apk%.apk}
for old in $earlier; do
  ver=${old#alpbrowser-}; ver=${ver#v}
  dir="old/$old"
  mkdir -p "$dir"
  if ! gh release download "$old" -p '*.apk' -D "$dir" --clobber 2>/dev/null; then echo "skip $old: no APK to download"; continue; fi
  oldapk=$(ls "$dir"/*.apk 2>/dev/null | head -n 1)
  [ -n "$oldapk" ] || { echo "skip $old: no APK"; continue; }
  out="deltas/$(basename "$base")-from-$ver.adp"
  if python3 -I "$here/make_apk_delta.py" "$oldapk" "$apk" "$out"; then echo "built $out"; else echo "skip $old: delta failed"; rm -f -- "${out:?}"; fi
done
ls -l deltas 2>/dev/null || true
exit 0
