plugins {
    id("com.android.application")
}

android {
    namespace = "com.oakherculesturkey.qemu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oakherculesturkey.qemu"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Snapdragon 8 Elite and every other modern phone: arm64 only.
            // x86_64 can be added for emulators / Chromebooks (see build-apk.sh).
            abiFilters += (project.findProperty("qemuAbis") as String? ?: "arm64-v8a").split(",")
        }
    }

    signingConfigs {
        // Fixed, intentionally public key so that every build (local or CI)
        // has the same signature and can be installed over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // QEMU binaries are shipped as lib*.so and executed with
            // ProcessBuilder, so they must be extracted to nativeLibraryDir.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
