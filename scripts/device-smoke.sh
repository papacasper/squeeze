#!/usr/bin/env bash
# Drives the installed Squeeze app over adb: pick a video (or download one from a URL), choose a size preset,
# wait for the result.
# PASS (exit 0) only if the job fits ("result FITS" in the SqueezeTiming log, or "Fits under ..." on screen).
# Exit 1 = over/failed/timeout/won't-fit dialog, exit 2 = couldn't drive the UI. The verdict comes from logcat, so
# the phone may be used meanwhile; screen text is read only while Squeeze is foreground; never screenshots.
#
#   scripts/device-smoke.sh <video display name> <preset label> [timeout_s]
#   scripts/device-smoke.sh 20260818_180742.mp4 "Discord Free"
#   SMOKE_SPLIT=1 scripts/device-smoke.sh 20260818_180742.mp4 "Discord Free"   # with parts enabled
#   SMOKE_MIN_HEIGHT=1080 scripts/device-smoke.sh ...   # set the resolution floor first
#   scripts/device-smoke.sh 20260818_180742.mp4 --hints   # print pre-encode warnings, start nothing
#   scripts/device-smoke.sh https://youtu.be/jNQXAC9IVRw "Discord Free"   # URL: share it in, Download, then compress
#   scripts/device-smoke.sh <url> --download-only   # PASS once the downloaded file is selected; no compression
#   SMOKE_IF_UNREACHABLE="Try anyway" scripts/device-smoke.sh ...   # button to press if the "won't fit" dialog
#     appears ("Try anyway", "Allow down to 480p", "Split into 2 parts"); unset = report it and exit 1
set -u
FILE="${1:?video display name (e.g. clip.mp4) or an http(s) URL}"
PRESET="${2:?preset label, e.g. \"Discord Free\"}"
TIMEOUT="${3:-1500}"
PKG=com.papacasper.squeeze
XML=/sdcard/squeeze-smoke-ui.xml

LOG="$(mktemp)"
dump() { adb shell uiautomator dump "$XML" >/dev/null 2>&1; adb shell cat "$XML" 2>/dev/null; }
cleanup() { adb shell rm -f "$XML" >/dev/null 2>&1; [ -n "${LOGPID:-}" ] && kill "$LOGPID" 2>/dev/null; rm -f "$LOG"; }
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
adb logcat -c
adb logcat -s SqueezeTiming:I > "$LOG" 2>/dev/null & LOGPID=$!
case "$FILE" in
http://*|https://*)
  # Share the link in as text (the SEND text/plain path pre-fills the URL field), then press Download.
  adb shell am start -a android.intent.action.SEND -t text/plain -n "$PKG/.MainActivity" \
    --es android.intent.extra.TEXT "'$FILE'" >/dev/null 2>&1
  sleep 3
  step "start download" "Download"
  start=$(date +%s); last=""
  while :; do
    sleep 5
    xml="$(dump)"
    now="$(printf '%s' "$xml" | grep -o 'text="\(Selected file\|Compression failed\|Downloading[^"]*\|Checking[^"]*\|Starting[^"]*\|Putting[^"]*\|\[download\][^"]*\|Download failed[^"]*\)"' | tr '\n' ' ')"
    [ "$now" != "$last" ] && { echo "$(date +%T) $now"; last="$now"; }
    case "$now" in
      *Selected\ file*) echo "smoke: download done in $(( $(date +%s) - start ))s"; break ;;
      *Compression\ failed*|*'"Download failed"'*)
        printf '%s' "$xml" | python3 -c 'import re,sys; t=re.findall(r"text=\"([^\"]+)\"", sys.stdin.read()); i=next(i for i,x in enumerate(t) if x in ("Download failed","Compression failed")); print("smoke: download FAIL:", t[i+1] if i+1 < len(t) else "")'
        exit 1 ;;
    esac
    [ $(( $(date +%s) - start )) -gt "$TIMEOUT" ] && { echo "smoke: FAIL (download timeout ${TIMEOUT}s)"; exit 1; }
  done
  [ "$PRESET" = "--download-only" ] && { echo "smoke: PASS"; exit 0; }
  sleep 3 ;;
*)
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  step "open picker" "Pick image or video"; sleep 3
  step "videos filter" "Videos"; sleep 2
  step "choose file" "$FILE" 6; sleep 8 ;;
esac
if [ "$PRESET" = "--hints" ]; then # print feasibility hints shown after selecting the file, don't start a run
  for _ in 1 2 3 4; do adb shell input swipe 720 2400 720 1200 300; sleep 1; done
  dump | python3 -c 'import re,sys; [print(t) for t in re.findall(r"text=\"([^\"]*(?:Can.t reach|look rough)[^\"]*)\"", sys.stdin.read())]'
  exit 0
fi
if [ -n "${SMOKE_MIN_HEIGHT:-}" ]; then # pick the "Never go below" resolution chip, e.g. SMOKE_MIN_HEIGHT=1080
  step "resolution floor chip" "${SMOKE_MIN_HEIGHT}p" 6; sleep 1
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
sleep 2
if dump | grep -q "probably won"; then  # "won't": uiautomator escapes the apostrophe
  msg="$(dump | python3 -c 'import re,sys; t=re.findall(r"text=\"([^\"]+)\"", sys.stdin.read()); print(next((x for x in t if x.startswith("At ")), ""))')"
  echo "smoke: app says it won't fit: $msg"
  [ -n "${SMOKE_IF_UNREACHABLE:-}" ] || { echo "smoke: FAIL (unreachable; set SMOKE_IF_UNREACHABLE to choose)"; exit 1; }
  step "won't-fit dialog" "$SMOKE_IF_UNREACHABLE"
fi

start=$(date +%s); last=""; seen=0
while :; do
  sleep 15
  n=$(grep -c "SqueezeTiming" "$LOG")
  [ "$n" -gt "$seen" ] && { grep "SqueezeTiming" "$LOG" | tail -n +"$((seen + 1))" | sed 's/^.*SqueezeTiming: /  log: /'; seen=$n; }
  case "$(grep -o 'result [A-Z]*' "$LOG" | tail -1)" in
    "result FITS") echo "smoke: PASS"; exit 0 ;;
    "result OVER"|"result FAILED") echo "smoke: FAIL"; exit 1 ;;
  esac
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
