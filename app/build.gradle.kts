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
        versionCode = 3
        versionName = "0.3"
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
    // TODO (needs Android Studio, cannot be verified without a real Gradle sync — see README's
    // "Wiring the vendored library" section): depend on the vendored remote-desktop-clients
    // submodule's `bVNC` and `remoteClientLib` library modules (both com.android.library,
    // Groovy DSL, AGP 8.13.2). Options, in order of how much of upstream's own build setup they
    // preserve:
    //   1. Composite build (settings.gradle.kts: includeBuild("remote-desktop-clients")) with
    //      dependencySubstitution rules mapping this app's dependency coordinates onto the
    //      included build's :bVNC / :remoteClientLib projects.
    //   2. Direct project inclusion (settings.gradle.kts: include(":bvnc-lib"); project(":bvnc-
    //      lib").projectDir = file("remote-desktop-clients/bVNC")) — simpler, but AGP-version
    //      mismatch risk (our root uses AGP 9.2.1, upstream's own root pins 8.13.2 for these
    //      modules) needs to be resolved/tested in Android Studio, not guessed here.
    // implementation(project(":bvnc-lib"))
    // implementation(project(":remote-client-lib"))
}
