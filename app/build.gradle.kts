import java.net.URI

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
        versionCode = 3
        versionName = "0.3.0-B3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Pinned debug key, checked in: without it every CI runner signs with its own throwaway
    // key and Android refuses to install a differently-signed APK over the previous one
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE). AGP creates the 'debug' config itself, so it is
    // reconfigured in place rather than added. The password is the standard "android" pair
    // for a debug key; nothing here signs production.
    signingConfigs {
        named("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

// Inter (SIL Open Font License) is the UI typeface: the closest open font to the one Apple uses,
// which is licensed for Apple platforms only. The binary is fetched once on first build instead of
// being committed, and every later build finds it already in place.
val interFontFile = layout.projectDirectory.file("src/main/res/font/inter_variable.ttf").asFile
val fetchInterFont by tasks.registering {
    outputs.file(interFontFile)
    onlyIf { !interFontFile.exists() }
    doLast {
        interFontFile.parentFile.mkdirs()
        val source = "https://raw.githubusercontent.com/google/fonts/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf"
        val connection = URI(source).toURL().openConnection()
        connection.getInputStream().use { input ->
            interFontFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchInterFont) }

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
    // Bottom navigation needs real icons; material-icons-core does not carry NetworkCheck,
    // BarChart or Timeline, so the extended set is pulled in at the compose release-train version.
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
