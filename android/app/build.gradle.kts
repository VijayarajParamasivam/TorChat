import java.util.Properties

plugins {
    // Kotlin itself comes from AGP 9's built-in support.
    alias(libs.plugins.android.application)
}

// Signing credentials live outside the build script. Without keystore.properties
// the release variant simply builds unsigned rather than failing, so a fresh
// clone still compiles.
//
// `-PtorchatKeystore=<path to keystore.properties>` signs with a keystore kept
// somewhere else entirely — so the release key never has to be copied into a
// synced or shared checkout. Its storeFile resolves next to that file.
val keystoreFile: File = (findProperty("torchatKeystore") as String?)?.let(::File)
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.torchat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.torchat"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = keystoreFile.parentFile.resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off. BouncyCastle and kmp-tor both reach for classes by
            // name, and an MVP does not need the size win badly enough to risk
            // a crash that only appears in the shipped build.
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // tor is executed, not dlopen'd, so it has to exist as a real file in
            // nativeLibraryDir. Modern AGP defaults to leaving native libs
            // compressed inside the APK, where nothing can exec them.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The View system: XML layouts, the action bar, fragments, and a
    // navigation graph tying them together.
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.preference.ktx)

    // Contact codes as QR. zxing's `core` is pure Java with no Android or Play
    // Services dependency, and CameraX reads the frames — so scanning works on a
    // device with no Google services on it, which matters for this app.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // The identity and the onion key live behind the Keystore (§11).
    implementation(libs.androidx.security.crypto)

    // Tor itself. The binary ships in the APK's JNI lib directory — Android 10+
    // blocks executing anything from the app data directory, so the desktop
    // client's "download tor at install time" approach does not port (§13).
    implementation(libs.kmptor.runtime)
    implementation(libs.kmptor.resource.exec)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
