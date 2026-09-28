pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "extranet-netdiag"

// Layer 1 (pure JVM, no Android SDK required): shared kernel + measurement logic.
include(":core")
include(":probe")

// Layer 2 (Android): one module per system that needs platform APIs.
include(":android:sensor-core")   // S1  Sensor Core
include(":android:measurement")   // S2  Measurement Engine + S7 Capability Registry
include(":android:inference")     // S3  Inference
include(":android:decision-sdk")  // S4  Decision SDK

// Layer 3 (Android application): S6 Presentation.
include(":app")

// S5 Collective (ingest + Capacity Atlas) is a Cloudflare Worker and deliberately
// has no Gradle module. It lives in `collective/` and is built by its own toolchain.
