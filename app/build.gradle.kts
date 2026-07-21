import java.io.FileInputStream
import java.util.Properties

plugins {
    // AGP 9.x has built-in Kotlin support — the org.jetbrains.kotlin.android plugin must NOT be
    // applied (it errors out). Same as the Linux Plugin's app/build.gradle.kts.
    id("com.android.application")
}

// Release signing: reads from app/keystore.properties, which is gitignored and never generated
// by this build script — create it yourself (locally only) pointing at your own release keystore:
//   storeFile=/absolute/or/relative/path/to/your.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Missing file = release builds stay unsigned (today's behavior, no regression) rather than
// failing the whole build — so a fresh checkout without this file still builds fine.
val keystorePropertiesFile = file("keystore.properties")
val hasKeystoreConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreConfig) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "de.lobianco.saftssh.remotedesktop"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lobianco.saftssh.remotedesktop"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "0.14"
    }

    buildFeatures {
        aidl = true
    }
    // One APK per ABI instead of a single universal one bundling every architecture's copy of
    // the native libs (FreeRDP + SPICE/GStreamer) — that fat APK was ~146MB even after dropping
    // the unused x86/x86_64 folders, because it still carried BOTH arm64-v8a's (~84MB) and
    // armeabi-v7a's (~63MB) libs in the one file. Sideloaded directly (no Play Store dynamic
    // delivery here), so isUniversalApk stays off — pick the APK matching the target device's ABI
    // (arm64-v8a for any real phone from the last ~8 years, which covers this project's own
    // OnePlus test device) instead of building/shipping the redundant combined file too.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    signingConfigs {
        if (hasKeystoreConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if (hasKeystoreConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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

    // Proxmox VE's VNC console is ALWAYS WebSocket-tunneled (pveproxy has no raw TCP VNC port
    // reachable from outside — same CONNECT-tunnel-only architecture reasoning as SPICE's
    // spiceproxy) — see vnc/ProxmoxVncWebSocket.kt. OkHttp's WebSocket support is the only new
    // dependency this needs; pure Kotlin/Java, no native code, negligible size next to the SPICE
    // native libs already bundled.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
