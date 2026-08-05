import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties()

if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
} else {
    versionProps["build_number"] = "1"
    versionProps["last_build_date"] = SimpleDateFormat("yyyy.MM.dd").format(Date())
}

val currentBuildNumber = (versionProps.getProperty("build_number") ?: "1").toInt()
val todayDateStr = SimpleDateFormat("yyyy.MM.dd").format(Date())

val computedVersionCode = currentBuildNumber
val computedVersionName = "$todayDateStr.$currentBuildNumber"

// Increment build number for next invocation
val nextBuildNumber = currentBuildNumber + 1
versionProps["build_number"] = nextBuildNumber.toString()
versionProps["last_build_date"] = todayDateStr
versionProps.store(FileOutputStream(versionPropsFile), "Updated by Gradle Build")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kove.mirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kove.mirror"
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }
}

// Copy compiled release APK to root `builds/` folder for GitHub
tasks.register("copyReleaseApkToBuilds") {
    doLast {
        val apkFile = file("build/outputs/apk/release/app-release.apk")
        if (apkFile.exists()) {
            val buildsDir = rootProject.file("builds")
            if (!buildsDir.exists()) {
                buildsDir.mkdirs()
            }
            val versionedApk = File(buildsDir, "KoveMirror-v${computedVersionName}.apk")
            val latestApk = File(buildsDir, "KoveMirrorLatest.apk")
            apkFile.copyTo(versionedApk, overwrite = true)
            apkFile.copyTo(latestApk, overwrite = true)
            println("📦 [KoveMirror Build] Copied APK to ${versionedApk.absolutePath}")
            println("📦 [KoveMirror Build] Updated ${latestApk.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApkToBuilds")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // OSM Map - osmdroid & Mapsforge (OpenAndroMaps .map support)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.18")
    implementation("org.mapsforge:mapsforge-map-android:0.20.0")
    implementation("org.mapsforge:mapsforge-themes:0.20.0")

    // 3D Map - MapLibre GL Native (3D buildings, tilt/rotate camera).
    // v11.0.0 uses kotlin-stdlib 1.9.x, compatible with project's Kotlin 1.9.22.
    implementation("org.maplibre.gl:android-sdk:11.0.0")
}
