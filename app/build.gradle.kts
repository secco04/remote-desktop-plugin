plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android plugin must NOT be
    // applied (it errors out). Same as the Linux Plugin's app/build.gradle.kts.
    id("com.android.application")
}

android {
    namespace = "de.lobianco.saftssh.remotedesktop"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lobianco.saftssh.remotedesktop"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// AGP 9.x / Kotlin 2.x: set the JVM target via the Kotlin compilerOptions DSL (kotlinOptions removed).
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // No dependency on the vendored remote-desktop-clients submodule as a library — VNC is a
    // from-scratch client (vnc/VncClient.kt) and RDP vendors FreeRDP's own upstream JNI bridge
    // (com/freerdp/freerdpcore/services/LibFreeRDP.java) directly as source plus prebuilt native
    // libraries in src/main/jniLibs/ (picked up automatically by AGP). See README.md.
}
