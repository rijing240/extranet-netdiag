plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.extranet.netdiag.android.measurement"
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
        // NewApi findings are expected and deliberate here: this module exists to interrogate
        // APIs above minSdk behind explicit Build.VERSION checks. Reported, not fatal.
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":probe"))
    implementation(project(":measure"))
    implementation(project(":android:sensor-core"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
