# Device smoke test

Proves a build works on a real phone, end to end: pick a video, compress to a preset, result fits.

1. `./gradlew testDebugUnitTest assembleRelease` (release keeps the phone's signature, so it updates in place; needs `local.properties` signing keys).
2. `adb install -r app/build/outputs/apk/release/app-release.apk`
3. `scripts/device-smoke.sh <video display name> "<preset label>"`

Success = exit 0 and `smoke: PASS` (result screen says "Fits under ..."). Anything else fails; exit 2 means the UI couldn't be driven (wrong screen, file not found).

Reference case: the 8K 12:56 clip on Casper's phone, `20260818_180742.mp4`, preset `Discord Free`. Expected: PASS in ~8 min, output ~19 MB (was 36 MB before audio re-encoding).

Notes: the script never screenshots and only reads on-screen text while Squeeze is foreground, because the phone is in daily use. Picking a preset starts the run immediately; there is no separate start button.
