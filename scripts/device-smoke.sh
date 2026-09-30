#!/usr/bin/env bash
# Drives the installed Squeeze app over adb: pick a video, choose a size preset, wait for the result.
# PASS (exit 0) only if the result screen says "Fits under ...". Exit 1 = "Still over"/"Failed"/timeout,
# exit 2 = couldn't drive the UI. Reads screen text only while Squeeze is foreground; never screenshots.
#
#   scripts/device-smoke.sh <video display name> <preset label> [timeout_s]
#   scripts/device-smoke.sh 20260818_180742.mp4 "Discord Free"
#   SMOKE_SPLIT=1 scripts/device-smoke.sh 20260818_180742.mp4 "Discord Free"   # with parts enabled
#   scripts/device-smoke.sh 20260818_180742.mp4 --hints   # print pre-encode warnings, start nothing
set -u
FILE="${1:?video display name, e.g. clip.mp4}"
PRESET="${2:?preset label, e.g. \"Discord Free\"}"
TIMEOUT="${3:-1500}"
PKG=com.papacasper.squeeze
XML=/sdcard/squeeze-smoke-ui.xml

dump() { adb shell uiautomator dump "$XML" >/dev/null 2>&1; adb shell cat "$XML" 2>/dev/null; }
cleanup() { adb shell rm -f "$XML" >/dev/null 2>&1; }
trap cleanup EXIT

# tap_text <exact text>: taps the centre of the first on-screen node with that text (or containing it
# for a trailing "*"). Returns 1 if not on screen or if the foreground isn't Squeeze/the system picker.
tap_text() {
  local want="$1" xml center
  xml="$(dump)"
  center=$(printf '%s' "$xml" | python3 -c '
import re, sys
want = sys.argv[1]
prefix = want.endswith("*")
want = want.rstrip("*")
for m in re.finditer(r"<node[^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read()):
    t = m.group(1).strip()
    if t == want or (prefix and t.startswith(want)):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$want")
  [ -n "$center" ] || return 1
  adb shell input tap $center
}

step() { # step <description> <text> [scroll-tries]
  local tries="${3:-0}" i
  for i in $(seq 0 "$tries"); do
    tap_text "$2" && return 0
    [ "$i" -lt "$tries" ] && adb shell input swipe 720 2400 720 1200 300 && sleep 1
  done
  echo "smoke: could not find '$2' ($1)" >&2
  exit 2
}

adb get-state >/dev/null 2>&1 || { echo "smoke: no adb device" >&2; exit 2; }
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3
step "open picker" "Pick image or video"; sleep 3
step "videos filter" "Videos"; sleep 2
step "choose file" "$FILE" 6; sleep 8
if [ "$PRESET" = "--hints" ]; then # print feasibility hints shown after selecting the file, don't start a run
  for _ in 1 2 3 4; do adb shell input swipe 720 2400 720 1200 300; sleep 1; done
  dump | python3 -c 'import re,sys; [print(t) for t in re.findall(r"text=\"([^\"]*(?:Can.t reach|look rough)[^\"]*)\"", sys.stdin.read())]'
  exit 0
fi
if [ "${SMOKE_SPLIT:-0}" = 1 ]; then # turn on "Split long videos into parts" (Compose Switch: the NAF checkable node) before starting
  for i in 0 1 2 3 4 5 6; do
    c=$(dump | python3 -c '
import re, sys
for m in re.finditer(r"<node NAF=\"true\"[^>]*?checkable=\"true\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read()):
    x1, y1, x2, y2 = map(int, m.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break')
    [ -n "$c" ] && { adb shell input tap $c; sleep 1; break; }
    adb shell input swipe 720 2400 720 1200 300; sleep 1
  done
  [ -n "${c:-}" ] || { echo "smoke: split switch not found" >&2; exit 2; }
fi
step "choose preset (starts the run)" "$PRESET" 6

start=$(date +%s); last=""
while :; do
  sleep 15
  xml="$(dump)"
  if printf '%s' "$xml" | grep -q "package=\"$PKG\""; then
    now="$(printf '%s' "$xml" | grep -o 'text="\(Encoding[^"]*\|Part [^"]*\|Testing a short[^"]*\|Sample predicts[^"]*\|Made with[^"]*\|Split into[^"]*\|Fits[^"]*\|Still[^"]*\|[0-9.]* [KMG]B  (down[^"]*\|Failed[^"]*\|Reached[^"]*\|Encoder[^"]*\)"' | tr '\n' ' ')"
  else
    now="(squeeze not foreground)"
  fi
  [ "$now" != "$last" ] && { echo "$(date +%T) $now"; last="$now"; }
  case "$now" in
    *Fits*) echo "smoke: PASS"; exit 0 ;;
    *Still*|*Failed*) echo "smoke: FAIL"; exit 1 ;;
  esac
  [ $(( $(date +%s) - start )) -gt "$TIMEOUT" ] && { echo "smoke: FAIL (timeout ${TIMEOUT}s)"; exit 1; }
done
