# Device smoke test

Proves a build works on a real phone, end to end: pick a video, compress to a preset, result fits.

1. `./gradlew testDebugUnitTest assembleRelease` (release keeps the phone's signature, so it updates in place; needs `local.properties` signing keys).
2. `adb install -r app/build/outputs/apk/release/app-release.apk`
3. `scripts/device-smoke.sh <video display name> "<preset label>"`
   - URL download: `scripts/device-smoke.sh <https url> "<preset>"` shares the link in, presses Download, then compresses (`--download-only` instead of a preset stops after the download). Known good: `https://www.youtube.com/watch?v=jNQXAC9IVRw`.
   - Site links checked 2026-10-09 with `--download-only` (PASS): Instagram reel `https://www.instagram.com/reel/Chunk8-jurw/`, TikTok photo post (slideshow path) `https://www.tiktok.com/@kevinzimple/photo/7691656733684534558`. Unsupported-site error: `https://example.com/` must FAIL with "Squeeze can't download from this site". Known broken: X `https://x.com/freethenipple/status/643211948184596480` ("No video could be found in this tweet" in the app, works with desktop yt-dlp). Reddit blocks the home IP ("unable to access the Reddit API").
   - Quick share: `adb shell "am start -a android.intent.action.SEND -t text/plain -n com.papacasper.squeeze/.QuickShare --es android.intent.extra.TEXT '<url>'"`, then `adb logcat -d -s SqueezeTiming:I` must show a `result` line with no taps.
   - If the "This probably won't fit" dialog appears the run fails unless `SMOKE_IF_UNREACHABLE` names the button to press (`"Try anyway"`, `"Allow down to 480p"`, `"Split into 2 parts"`).

Success = exit 0 and `smoke: PASS`. The verdict is the `result FITS|OVER|FAILED <bytes>` line the service logs under the `SqueezeTiming` tag (the script streams it, together with every pass line, into its output), so it holds even when Squeeze is in the background; the "Fits under ..." screen text is the fallback. Anything else fails; exit 2 means the UI couldn't be driven (wrong screen, file not found).

Reference case: the 8K 12:56 clip on Casper's phone, `20260818_180742.mp4`, preset `Discord Free`. Expected: PASS in ~8 min, output ~19 MB (was 36 MB before audio re-encoding).

Notes: the script never screenshots and only reads on-screen text while Squeeze is foreground, because the phone is in daily use. Picking a preset starts the run immediately; there is no separate start button.
