plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.extranet.netdiag.android.sensor"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // NewApi and MissingPermission findings are deliberate: this module interrogates APIs
        // above minSdk behind explicit version checks. Reported, not fatal, until S1's device
        // matrix (B12) proves the guards complete.
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":measure"))
    implementation(libs.coroutines.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
