// Root aggregation project: no sources of its own. Every module declares its
// own targets and dependencies (see settings.gradle.kts for the list).
// The shared Kotlin/Compose plugins are resolved here once (`apply false`) so
// the subprojects' `alias(libs.plugins.*)` all reuse the same classloader —
// without this, Gradle warns that the Kotlin plugin gets loaded per-subproject.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}