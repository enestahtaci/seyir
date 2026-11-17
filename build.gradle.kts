plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Android Kotlin itself; this pins the Kotlin compiler version.
    alias(libs.plugins.kotlin.android) apply false
}
