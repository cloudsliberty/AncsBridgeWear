// Wear OS ANCS Notification Bridge - module Gradle configuration
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.notisync.wear.ancs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notisync.wear.ancs"
        minSdk = 30 // Android 11 / Wear OS 3.0+ (BLUETOOTH_CONNECT/SCAN runtime perms need 31+, guarded at runtime)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
}

dependencies {
    // Wear OS core & Material design
    implementation(libs.wear)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)

    // AndroidX & lifecycle
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)

    // Coroutines for async BLE / GATT event flow
    implementation(libs.kotlinx.coroutines.android)
}
