import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// versionName is the single source of truth -- versionCode is derived from it (major*1_000_000 +
// minor*1_000 + patch) so there's no separate counter to remember to bump every release. CI
// additionally checks the derived code against the latest published release before tagging (see
// .github/workflows/release.yml) as a safety net against a version number that was never meant
// to be lower ending up lower anyway.
val appVersionName = "0.1.0"

fun versionCodeFor(versionName: String): Int {
    val (major, minor, patch) = versionName.split(".").map { it.toInt() }
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "at.sushi.handoff"
    compileSdk = 37

    defaultConfig {
        applicationId = "at.sushi.handoff"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeFor(appVersionName)
        versionName = appVersionName
    }

    signingConfigs {
        // Populated from RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD/RELEASE_KEY_ALIAS/
        // RELEASE_KEY_PASSWORD env vars in CI (see .github/workflows/release.yml). Falls back to
        // an unsigned release build when unset, so `assembleRelease` still works for anyone
        // without the release keystore -- Android just won't accept it as an update over a
        // signed install.
        val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("junit:junit:4.13.2")
}
