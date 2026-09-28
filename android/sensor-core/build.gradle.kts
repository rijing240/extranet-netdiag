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
        // Lint findings are reported but must not block the B0 build while the module is a
        // skeleton. Tighten to abortOnError = true once S1 has real listeners in B2.
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
