plugins {
    id("com.android.application")
}

android {
    namespace = "com.tvlauncher.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvlauncher.lite"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload: the debug key avoids managing a release keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Deliberately no dependencies. AndroidX, Kotlin and Leanback are what make
// launchers heavy; everything here comes from the framework.
