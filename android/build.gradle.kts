// Top-level build file. Plugin versions come from gradle/libs.versions.toml;
// `apply false` here declares them for subprojects without applying to the root.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
