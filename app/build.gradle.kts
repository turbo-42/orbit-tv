import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.walker.tvhome"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.walker.tvhome"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Optional: set unsplash.accessKey in local.properties to use Unsplash instead of Earth View.
        buildConfigField(
            "String",
            "UNSPLASH_ACCESS_KEY",
            "\"${localProperties.getProperty("unsplash.accessKey", "")}\"",
        )
        // Optional: weather.location=lat,lon,Place in local.properties; otherwise located by IP.
        buildConfigField(
            "String",
            "WEATHER_LOCATION",
            "\"${localProperties.getProperty("weather.location", "")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded personal build: reuse the debug key so it installs over the debug APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
