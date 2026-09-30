import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fetches a statically-linked `proot` build for each supported ABI at Gradle-configuration
// time and stages it under jniLibs (renamed to lib*.so — Android's installer extracts and
// marks files under nativeLibraryDir executable regardless of their actual content, which is
// the standard, documented way to run a binary you didn't link into the app; a file written to
// storage at runtime instead would be blocked by W^X on API 29+). See scripts/fetch_proot.py.
val generatedProotJni = layout.buildDirectory.dir("generated/proot-jni")

val fetchProot = tasks.register<Exec>("fetchProot") {
    inputs.file(rootProject.projectDir.resolve("scripts/fetch_proot.py"))
    outputs.dir(generatedProotJni)
    commandLine(
        "python3",
        rootProject.projectDir.resolve("scripts/fetch_proot.py").absolutePath,
        "--output-dir",
        generatedProotJni.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.alpdroid.app"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.alpdroid.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 40
        versionName = "1.7.19"

        ndk {
            // Keep in sync with fetch_proot.py's ANDROID_ABI_TO_TERMUX_ARCH — no point building
            // (or shipping) the native pty_bridge helper for an ABI proot isn't available for.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        getByName("debug") {
            // The old committed debug.keystore is gone (secrets don't live in git); when no
            // local file exists AGP falls back to its auto-generated debug key — same behavior.
            val debugKs = file("debug.keystore")
            if (debugKs.isFile) {
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Release signing, in order of preference:
        // 1. Environment (CI): ALPDROID_KEYSTORE_FILE/ALPDROID_STORE_PASSWORD/ALPDROID_KEY_ALIAS/ALPDROID_KEY_PASSWORD.
        //    The keystore itself never lives in git — see .github/workflows/alpdroid-release.yml,
        //    which restores it from the KEYSTORE_BASE64 secret. This is what makes the repo
        //    safe to publish: history still contains the old committed key (rotate it!), but no
        //    new secret ever enters the tree.
        // 2. Local keystore.properties (git-ignored): same keys as the ALPDROID_* names, for
        //    developers signing release builds on their own machine.
        // Without either, a release build comes out unsigned (installs nothing, CI-checks fine).
        val envStore = System.getenv("ALPDROID_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
        val keystorePropsFile = rootProject.file("keystore.properties")
        if (envStore != null) {
            create("release") {
                storeFile = envStore
                storePassword = System.getenv("ALPDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ALPDROID_KEY_ALIAS")
                keyPassword = System.getenv("ALPDROID_KEY_PASSWORD")
            }
        } else if (keystorePropsFile.exists()) {
            val keystoreProps = Properties().apply { load(keystorePropsFile.inputStream()) }
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only actually signed if keystore.properties exists (see signingConfigs above) —
            // falls back to Android Gradle Plugin's default "unsigned release" behavior otherwise,
            // which still builds (useful for CI checks that don't need a signed artifact) but
            // can't be installed as-is.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir(generatedProotJni)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // proot (+ the pty-bridge helper built via CMake below) are executables shipped under
    // jniLibs, not real shared libraries — legacy packaging is what extracts them as real,
    // executable files at install time instead of leaving them zipped inside the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    afterEvaluate {
        tasks.named("preBuild") {
            dependsOn(fetchProot)
        }
    }

    buildFeatures {
        viewBinding = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    // DrawerLayout alone (not the full material/appcompat stack) for the right-edge-swipe
    // settings panel — it's a small, standalone artifact with its own built-in edge-drag
    // gesture detection, not worth reimplementing by hand.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // Material 3 components (buttons, dialogs, chips, switches, color/shape theming) for the
    // redesign — the app previously used only plain platform widgets (Button, AlertDialog,
    // Switch) with hand-rolled coloring.
    implementation("com.google.android.material:material:1.12.0")
    // The official splash-screen API, backported down to this app's minSdk — a real system
    // splash (not a fake "splash Activity" that just delays showing the real UI) with an
    // animated-vector icon entrance on API 31+, falling back to the same icon shown statically
    // (no animation) on everything below that, which is the library's own documented behavior.
    implementation("androidx.core:core-splashscreen:1.0.1")
}
