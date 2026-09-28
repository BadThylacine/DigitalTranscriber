plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.offlinetranscriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.offlinetranscriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Real phones are arm64; x86_64 is for the emulator. Remove it to shrink the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    // Keep model files uncompressed so they can be memory-mapped quickly.
    androidResources { noCompress += listOf("onnx", "txt") }
}

dependencies {
    // Downloaded by scripts/setup.sh (or setup.ps1) into app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
