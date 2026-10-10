plugins {
    // No version: AGP 9 brings Kotlin in on the build classpath already, and
    // asking for a specific one here fails compatibility checking.
    id("org.jetbrains.kotlin.jvm")
}

// The protocol lives here, and nothing in this module may import android.*.
// Everything in TorChat's ARCHITECTURE.md §3-§10 is portable logic; only the
// TorProvider implementation is platform-specific, and that lives in :app.
// Keeping this a plain JVM module means the whole protocol is testable in
// seconds without an emulator.

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Only the JsonElement DOM is used — no @Serializable classes, so the
    // serialization compiler plugin is not needed. Canonical encoding (§5.2) is
    // hand-written on top of the DOM because it must be byte-exact.
    api(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

