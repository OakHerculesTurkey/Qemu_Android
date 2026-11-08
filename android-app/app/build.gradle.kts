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
        versionCode = 5
        versionName = "0.2.1"
        ndk {
            // Snapdragon 8 Elite and every other modern phone: arm64 only.
            // x86_64 can be added for emulators / Chromebooks (see build-apk.sh).
            abiFilters += (project.findProperty("qemuAbis") as String? ?: "arm64-v8a").split(",")
        }
    }

    // Release signing key: never stored in the repository. Supplied through
    // environment variables (CI: GitHub Secrets) or -PqemuKeystore=...
    // Without it, builds are signed with this machine's Android debug key.
    val ksPath = System.getenv("QEMU_KEYSTORE") ?: project.findProperty("qemuKeystore") as String?
    val ksPass = System.getenv("QEMU_KEYSTORE_PASSWORD")
        ?: project.findProperty("qemuKeystorePassword") as String?
    val haveReleaseKey = !ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty() && file(ksPath).exists()

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = System.getenv("QEMU_KEY_ALIAS") ?: "qemu-android"
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
            // QEMU binaries are shipped as lib*.so and executed with
            // ProcessBuilder, so they must be extracted to nativeLibraryDir.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Screenshot tests: render real views with Robolectric's native graphics
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

// Guest framebuffer for ScreenshotTest: -PqemuFb=/path/to/file.fb
tasks.withType<Test>().configureEach {
    // Robolectric needs these on JDK 17+
    jvmArgs(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
    (project.findProperty("qemuFb") as String?)?.let { systemProperty("qemu.fb", it) }
    // Optional Maven mirror for the Android runtime Robolectric downloads itself
    (project.findProperty("robolectricRepo") as String?)?.let {
        systemProperty("robolectric.dependency.repo.url", it)
        systemProperty("robolectric.dependency.repo.id", "mirror")
    }
}
