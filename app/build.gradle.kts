plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.webmchan"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.webmchan"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        // The maintained FFmpegKit fork only publishes arm64-v8a prebuilts.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Community-maintained continuation of FFmpegKit (Free tier: libvpx VP8/VP9, libvorbis, libopus).
    // Same com.arthenica.ffmpegkit package as the original. Verify the latest coordinate at
    // https://github.com/ffmpegkit-maintained/ffmpeg if Gradle can't resolve this one.
    implementation("dev.ffmpegkit-maintained:ffmpeg:7.1.6")
}
