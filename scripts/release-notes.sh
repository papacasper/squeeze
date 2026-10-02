#!/usr/bin/env bash
# Prints the release notes for the app's current versionCode from fastlane/metadata/android/en-US/changelogs/.
# Exits 1 if the file is missing or empty, so a release can't go out without notes.
set -eu
cd "$(dirname "$0")/.."
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
file="fastlane/metadata/android/en-US/changelogs/${code}.txt"
[ -n "$code" ] || { echo "release-notes: versionCode not found" >&2; exit 1; }
[ -s "$file" ] || { echo "release-notes: $file is missing or empty" >&2; exit 1; }
cat "$file"
