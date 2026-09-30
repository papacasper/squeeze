#!/usr/bin/env bash
# Builds synthetic test videos and pushes them to the phone's Download folder for device-smoke.sh.
#   stepdown-test.mp4  5 min 1080p, 256 kbps audio  -> audio is capped (256 -> ~180 kbps) on "Discord Free"
#   stepdown-long.mp4  20 min 720p, 128 kbps audio  -> target unreachable; audio steps 64 -> 48 -> 32 kbps
# Expected: `device-smoke.sh stepdown-test.mp4 "Discord Free"` PASS; `SMOKE_MIN_HEIGHT=720 ... stepdown-long.mp4`
# shows the 64/48/32 audio steps and the "Can't reach" hint (exit 1 is expected for that one: synthetic
# content overshoots the minimum bitrate).
set -euo pipefail
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
gen() { # gen <name> <size> <secs> <vbr> <abr>
  ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=$2:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
    -t "$3" -c:v libx264 -b:v "$4" -c:a aac -b:a "$5" -ac 2 "$tmp/$1"
  adb push "$tmp/$1" /sdcard/Download/ >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$1" >/dev/null
  echo "pushed $1"
}
gen stepdown-test.mp4 1920x1080 300 2500k 256k
gen stepdown-long.mp4 1280x720 1200 400k 128k
