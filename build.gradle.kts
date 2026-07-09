// Root build file for the standalone LobiShell Remote Desktop Plugin project.
// Open the remote-desktop-plugin/ directory as its own Android Studio project —
// do NOT add this module to the main app's settings.gradle.kts.
plugins {
    // AGP 9.x bundles Kotlin — no separate org.jetbrains.kotlin.android plugin.
    id("com.android.application") version "9.2.1" apply false
}
