plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvfilebridge.a11ywatchdog"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvfilebridge.a11ywatchdog"
        minSdk = 21
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WorkManager for the periodic backstop check - deliberately not a
    // foreground service, so this stays a lightweight, no-persistent-
    // notification background job rather than something that shows up as
    // "running" all the time.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
