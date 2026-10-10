// Plugins are declared once here and applied per module, so every module resolves the same
// Kotlin Gradle plugin. AGP 9 compiles Kotlin itself for :app, using this same version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
