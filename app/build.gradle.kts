import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.mtsprout.halfnav"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mtsprout.halfnav"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "TOMTOM_KEY", "\"${localProps.getProperty("TOMTOM_KEY", "")}\"")
    }

    // Release signing key lives outside the repo; its location and password are in
    // local.properties (RELEASE_STORE_FILE etc.). Without them, release builds are unsigned.
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Strip unused code and resources, and optimize: smaller, faster, not debuggable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The map engine is native code, ~12 MB per processor type. Build one APK per type instead
    // of one carrying all of them: arm64 for phones, 32-bit ARM for old phones, x86_64 for emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
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
    kotlinOptions { jvmTarget = "17" }
}

// Name the APKs after the app and version. The arm64 release (what phones use) is plain
// HalfNav-1.2.apk; others say what they're for, e.g. HalfNav-1.2-x86_64.apk, HalfNav-1.2-debug.apk.
@Suppress("DEPRECATION")
android.applicationVariants.all {
    val variant = this
    outputs.all {
        val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abi = out.getFilter(com.android.build.OutputFile.ABI)
        val parts = listOfNotNull(
            variant.buildType.name.takeIf { it != "release" },
            abi?.takeIf { it != "arm64-v8a" },
        )
        out.outputFileName = "HalfNav-${variant.versionName}" + parts.joinToString("") { "-$it" } + ".apk"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.maplibre.gl:android-sdk:11.13.5")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
