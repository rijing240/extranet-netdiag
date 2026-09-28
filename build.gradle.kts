// Root build file. Plugins are declared here so subprojects can `alias(...)` them
// without re-declaring versions, and so the whole graph resolves one plugin set.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
