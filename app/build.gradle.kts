plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.extranet.netdiag.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.extranet.netdiag"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-B0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        // NewApi and MissingPermission findings are deliberate: this app exists to interrogate
        // APIs above minSdk behind explicit version checks. Reported, not fatal.
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":probe"))
    implementation(project(":measure"))
    implementation(project(":android:sensor-core"))
    implementation(project(":android:measurement"))
    implementation(project(":android:inference"))
    implementation(project(":android:decision-sdk"))

    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
