plugins {
    id("com.android.application")
}

android {
    namespace = "com.oakherculesturkey.jkbotti"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oakherculesturkey.jkbotti"
        minSdk = 28
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.0"
        ndk {
            abiFilters += (project.findProperty("jkAbis") as String? ?: "arm64-v8a").split(",")
        }
    }

    // Release key is never stored in the repository (same scheme as the QEMU
    // app): JK_KEYSTORE + JK_KEYSTORE_PASSWORD, otherwise the debug key.
    val ksPath = System.getenv("JK_KEYSTORE") ?: project.findProperty("jkKeystore") as String?
    val ksPass = System.getenv("JK_KEYSTORE_PASSWORD")
        ?: project.findProperty("jkKeystorePassword") as String?
    val haveReleaseKey = !ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty() && file(ksPath).exists()

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = System.getenv("JK_KEY_ALIAS") ?: "jk-botti"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (haveReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Xash3D FWGS dlopen()s the game libraries straight from this
            // APK's nativeLibraryDir, so they must be extracted on install.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
