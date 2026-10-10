plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// AlpBrowser: a standalone intercepting browser (WebView + the shared core-proxy MITM engine),
// driven entirely by a loopback control API so the AlpDroid agent has full control of the page,
// the network, and the decrypted TLS traffic. Deliberately lean — NO terminal/proot/Alpine
// pipeline (that lives in :app) — just browser + proxy + control bridge.
android {
    namespace = "com.alpdroid.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alpdroid.browser"
        minSdk = 26 // androidx.webkit.ProxyController + WebView interception
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing mirrors :app — from the ALPDROID_* CI environment when present, else unsigned.
    // Debug uses AGP's default auto-generated debug key (alpdroid keeps no committed keystore).
    signingConfigs {
        val envStore = System.getenv("ALPDROID_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
        if (envStore != null) {
            create("release") {
                storeFile = envStore
                storeType = "PKCS12"
                storePassword = System.getenv("ALPDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ALPDROID_KEY_ALIAS")
                keyPassword = System.getenv("ALPDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-proxy"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.webkit:webkit:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
