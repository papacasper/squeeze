import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.papacasper.squeeze"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.papacasper.squeeze"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "2026.09.21"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = localProperties.getProperty("keystore.storePassword")
            keyAlias = localProperties.getProperty("keystore.keyAlias")
            keyPassword = localProperties.getProperty("keystore.keyPassword")
        }
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
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")

    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Decodes existing animated GIFs into frame bitmaps + delays for GifCompressor.
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")

    // Bundled yt-dlp + Python runtime and static ffmpeg, for the "paste a URL" download feature.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
