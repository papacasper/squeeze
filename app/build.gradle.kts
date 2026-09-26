import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Signing values come from local.properties, or from the environment on CI. Without them (e.g.
// an F-Droid build, which signs with its own key) the release build is simply left unsigned.
fun signingValue(property: String, env: String): String? =
    localProperties.getProperty(property) ?: System.getenv(env)

val releaseKeystore = rootProject.file("release.keystore")
val releaseStorePassword = signingValue("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keystore.keyAlias", "KEYSTORE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
val canSignRelease = releaseKeystore.exists() &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.papacasper.squeeze"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.papacasper.squeeze"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "2026.09.25"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    // The signing-metadata blob AGP embeds in the APK is opaque to F-Droid's scanner and useless here.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            // R8 minification off: youtubedl-android bundles Chaquopy (embedded Python) whose
            // internal reflection use isn't R8-shrink-safe even with keep rules for our own
            // packages — it crashes deep inside initPython() with "class X is not a concrete
            // class". Not worth chasing further: this is a sideloaded personal app, and code
            // shrinking is negligible against the ~200MB already added by bundled native
            // yt-dlp/Python/ffmpeg binaries.
            isMinifyEnabled = false
            isShrinkResources = false
            // arm64 only: yt-dlp/Python/ffmpeg ship once per ABI, so dropping 32-bit ARM and x86
            // cuts the APK by ~3/4. Debug builds stay universal so x86 emulators still work.
            ndk { abiFilters += "arm64-v8a" }
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // youtubedl-android ships yt-dlp/Python/ffmpeg as fake .so files it expects to find
    // as real extracted, executable files under nativeLibraryDir. AGP 8.1+ defaults native
    // libs to compressed/page-aligned-in-APK (useLegacyPackaging = false), so they're never
    // extracted and init() silently fails with "instance not initialized" on first use.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)

    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    // Decodes existing animated GIFs into frame bitmaps + delays for GifCompressor.
    implementation(libs.glide.gifdecoder)

    // Bundled yt-dlp + Python runtime and static ffmpeg, for the "paste a URL" download feature.
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
}
