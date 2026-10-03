import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The release signing key, read from a file that is never committed.
 *
 * An Android update has to be signed with the same key as the build already installed, which makes
 * the release key the app's identity rather than a build detail: lose it and nobody who installed
 * the app can ever update it again, leak it and somebody else can publish a build that installs
 * over it. So it lives outside the repository (`keystore/release.jks`), its passwords live in
 * `keystore.properties` beside it (git-ignored, with a documented template checked in), and CI
 * writes both from repository secrets.
 *
 * When the file is absent - a fresh clone, a pull request, a debug build - the release build is
 * unsigned rather than broken. That is the difference between "publishing needs a key" and
 * "everything needs a key", and only the first is true.
 */
val releaseKeystoreFile = rootProject.file("keystore.properties")
val releaseSigning: Properties? = if (releaseKeystoreFile.exists()) {
    Properties().apply { releaseKeystoreFile.inputStream().use { load(it) } }
} else {
    null
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
        // Created only when a key is configured, so nothing here can fail for want of a secret.
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
