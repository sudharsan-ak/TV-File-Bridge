plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tvfilebridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvfilebridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("dev.mobile:dadb:1.2.10")

    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Bundles the tv-companion APK as a raw asset so the phone app can push +
// install it onto the TV over the existing ADB connection (no Play Store,
// no separate distribution channel - the phone app carries its companion).
tasks.register<Copy>("copyCompanionApk") {
    dependsOn(":tv-companion:assembleDebug")
    from(project(":tv-companion").layout.buildDirectory.file("outputs/apk/debug/tv-companion-debug.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "tv_companion.apk" }
}

// Same reasoning as copyCompanionApk above, for the accessibility watchdog -
// bundled so the phone app can install it onto the TV over ADB too, rather
// than requiring a separate manual sideload step.
tasks.register<Copy>("copyWatchdogApk") {
    dependsOn(":accessibility-watchdog:assembleDebug")
    from(project(":accessibility-watchdog").layout.buildDirectory.file("outputs/apk/debug/accessibility-watchdog-debug.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "accessibility_watchdog.apk" }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
    dependsOn("copyCompanionApk")
    dependsOn("copyWatchdogApk")
}
