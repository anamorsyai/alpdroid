import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure Kotlin/JVM module, deliberately Android-free: the proxy engine has
// no dependency on the Android SDK, so it can be built, unit-tested and
// reused (e.g. from a desktop companion tool) without an Android toolchain.
// jvmTarget is set directly (not via a toolchain) so this compiles with
// whatever JDK is on PATH, matching what Android's D8/R8 expects (17).

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // BouncyCastle for the TLS-MITM certificate authority (key generation, X.509 building).
    // Pure JVM, no Android dependency — keeps core-proxy's build-anywhere property intact.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

tasks.test {
    useJUnitPlatform()
}
