import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

// Optional API keys read from local.properties (never committed):
//   TMDB_API_KEY, TVDB_API_KEY, OMDB_API_KEY, TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET, SIMKL_CLIENT_ID, SIMKL_CLIENT_SECRET
// They are the defaults; the Settings screen lets the user enter / override them.
// Release signing (also in local.properties): RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = "\"" + (localProps.getProperty(name) ?: System.getenv(name) ?: "") + "\""
// Keys are embedded in debug builds only. A release APK ships without any key (users enter theirs in
// Settings), unless RELEASE_EMBED_KEYS=true is set in local.properties for a personal build.
val embedKeysInRelease = localProps.getProperty("RELEASE_EMBED_KEYS")?.toBoolean() ?: false
val secretNames = listOf("TMDB_API_KEY", "TVDB_API_KEY", "OMDB_API_KEY", "TRAKT_CLIENT_ID", "TRAKT_CLIENT_SECRET", "SIMKL_CLIENT_ID", "SIMKL_CLIENT_SECRET")

android {
    namespace = "com.example.trackstuff"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.trackstuff"
        minSdk = 29
        targetSdk = 37
        versionCode = 7
        versionName = "0.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Release signing: only when a keystore is configured in local.properties; otherwise the release build
    // stays unsigned (it can still be built, just not installed).
    val storeFile = localProps.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }
    if (storeFile != null && storeFile.exists()) {
        signingConfigs {
            create("release") {
                this.storeFile = storeFile
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            secretNames.forEach { buildConfigField("String", it, secret(it)) }
        }
        release {
            optimization {
                enable = false
            }
            secretNames.forEach { buildConfigField("String", it, if (embedKeysInRelease) secret(it) else "\"\"") }
            if (storeFile != null && storeFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.coil.compose)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.logging.interceptor)
    implementation(libs.material)
    implementation(libs.commons.compress)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}
