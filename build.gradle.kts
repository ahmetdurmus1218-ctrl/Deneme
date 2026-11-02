plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// Single source of truth for the app version: gradle.properties (jarvis.version), overridable from CI
// with -Pjarvis.version=x.y.z (the release workflow passes the tag).
val jarvisVersion: String = providers.gradleProperty("jarvis.version").get()
allprojects { version = jarvisVersion }
