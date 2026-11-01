import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional first-run seed for a "Home" place. Read from local.properties (gitignored) or,
// in CI, from the HOME_LAT / HOME_LON environment variables (repo secrets). Never committed.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun seedValue(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "dev.nick.raincheck"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.nick.raincheck"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "HOME_LAT", "\"${seedValue("HOME_LAT")}\"")
        buildConfigField("String", "HOME_LON", "\"${seedValue("HOME_LON")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // A committed debug key (not a secret) so every CI build installs over the previous one
    // without wiping saved places. Don't use it for anything you publish.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications.datasource)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.activity.compose)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
}
