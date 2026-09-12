# Squeeze

Native Android app (Kotlin + Jetpack Compose) that compresses images, video, and GIFs on-device to fit a chosen size target — no cloud upload, no account, nothing leaves your phone.

## Why

Sending a video to Discord, email, or a chat app that caps attachment size usually means fumbling with a desktop tool. Squeeze does the whole thing on the phone: pick a file, pick a target size, get back a file that fits.

## Features

- **Size targets**: Discord tiers (Free 20MB / Nitro Basic 50MB / Nitro 500MB), Email 20MB, Text/RCS 100MB, WhatsApp/Telegram 2GB, or a custom slider.
- **Images**: JPEG quality binary search, respects EXIF orientation, falls back to downscaling if quality alone can't hit the target.
- **Video**: H.265 (HEVC) re-encode via Media3 Transformer, retrying at progressively lower bitrate and, if needed, lower resolution (1080p → 720p → 540p → 480p → 360p → 240p) until it fits. Automatically falls back to H.264 if a hardware encoder stalls mid-pass.
- **GIF**: compress existing GIFs, or convert a video clip straight to GIF with trim controls.
- **Share-target integration**: "Share → Squeeze" from any app that shares an image or video.
- **Background-safe**: compression runs in a foreground service, so it keeps going (with a progress notification) even if you background the app.
- **History**: the last 20 compressions are logged locally and viewable from the app.

## Building

Requires the Android SDK (`compileSdk 35`, `minSdk 26`) and JDK 17.

```bash
./gradlew :app:assembleDebug     # debug build
./gradlew :app:assembleRelease   # release build (see Signing below)
```

### Signing a release build

Release builds are signed and require a keystore. Point `local.properties` (git-ignored, not included in this repo) at your own keystore credentials:

```properties
keystore.storePassword=your-store-password
keystore.keyAlias=your-key-alias
keystore.keyPassword=your-key-password
```

Generate a keystore first if you don't have one:

```bash
keytool -genkeypair -v -keystore release.keystore -alias your-key-alias \
  -keyalg RSA -keysize 2048 -validity 10000
```

`release.keystore` is expected at the project root and is also git-ignored — you provide your own; the one used for the author's own signed builds is not in this repo.

## Distribution

This isn't published to the Play Store — it's built and sideloaded (`adb install`, or transferred and installed manually). R8 minification/shrinking is enabled for release builds, which keeps the APK small enough to attach directly to a chat message if you want to.

## License

MIT — see [LICENSE](LICENSE).
