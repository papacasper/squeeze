# Release

Cut an app release and publish it everywhere users get it. Success = every check below passes.

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. `versionName` is the date `YYYY.MM.DD`; a second release the same day gets a letter suffix (`2026.10.09b`).
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: one plain user-facing line per change. It becomes the GitHub release body (`scripts/release-notes.sh`).
3. `./gradlew testDebugUnitTest :app:lintDebug assembleRelease` must pass.
4. Commit `Release <versionName>`, tag `v<versionName>`, then `git push && git push origin v<versionName>`.
5. Wait for the Release workflow: `gh run watch -R papacasper/squeeze $(gh run list -R papacasper/squeeze -w Release -L1 --json databaseId -q '.[0].databaseId') --exit-status`.
   Check: `gh release view v<versionName> -R papacasper/squeeze` lists `Squeeze-<versionName>.apk` and its `.sha256`.
6. Mirror to papacasper.com (in a scratch dir):
   ```
   gh release download v<versionName> -R papacasper/squeeze --clobber && sha256sum -c Squeeze-<versionName>.apk.sha256
   scp -q Squeeze-<versionName>.apk papacasper.com:/tmp/Squeeze.apk.new
   ssh papacasper.com "sudo mv /tmp/Squeeze.apk.new /var/www/papacasper.com/downloads/Squeeze.apk && sudo chown www-data:www-data /var/www/papacasper.com/downloads/Squeeze.apk"
   ```
   Check: `curl -s https://papacasper.com/downloads/Squeeze.apk | sha256sum` equals the release `.sha256`.
7. Discord #downloads (channel `1539101615355723836`, discord-mcp tools): read the channel, post the new embed first (title `Squeeze <versionName>`, description = download link `https://papacasper.com/downloads/Squeeze.apk` + releases page, a field with the changelog), then delete the previous release post. Exactly one release post remains.
8. Phone (if on adb): `adb install -r` the release APK.
9. Record the release (version, message id, sha256) in the vault project note `01-Projects/Claude-Projects/discord-compressor.md`.
