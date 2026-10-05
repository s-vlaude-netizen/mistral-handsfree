import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Signing key.
 *
 * Android only accepts an update when the new APK is signed with the same key
 * as the installed one. Without a fixed key every CI run would sign with a
 * fresh throw-away key and no update would ever install over the previous one.
 * So a key ships in the repo (see keystore/keystore.properties and the
 * "Signing" section of the README for what that means).
 *
 * The SIGNING_* environment variables take precedence, so CI - or anyone who
 * does not want to trust the public key - can supply a private one instead.
 */
val signingProperties = Properties().apply {
    rootProject.file("keystore/keystore.properties").inputStream().use { load(it) }
}

fun signing(env: String, property: String): String =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProperties.getProperty(property)

/** CI passes the run number so that the version code only ever goes up. */
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "de.localvoice.mistralhandsfree"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.localvoice.mistralhandsfree"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            val externalStore = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
            storeFile = if (externalStore != null) file(externalStore)
            else rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signing("SIGNING_STORE_PASSWORD", "storePassword")
            keyAlias = signing("SIGNING_KEY_ALIAS", "keyAlias")
            keyPassword = signing("SIGNING_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        // Both variants share the key, otherwise a locally built APK could not
        // be installed over the one from CI (or the other way round).
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // No shrinking: the goal is just that "debuggable" is off. R8 would
            // make the APK smaller but adds a class of failures that only show
            // up on a device.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // A single lint finding should not turn CI red.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // Lets tested code call android.util.Log & co. without blowing up.
            isReturnDefaultValues = true
            // Robolectric needs the app's resources and manifest to run the screens on the JVM.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Adds the empty activity that Compose UI tests host their content in.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // The screens, run on the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
