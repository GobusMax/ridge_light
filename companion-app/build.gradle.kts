import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

// Release signing: a properties file outside the repo (storeFile, storePassword, keyAlias,
// keyPassword), by default ~/.android/ridgelight-release.properties, or the path in the Gradle
// property "ridgelight.signing". Without it, release builds are signed with the debug key.
val signingFile = file(
    providers.gradleProperty("ridgelight.signing").orNull
        ?: "${System.getProperty("user.home")}/.android/ridgelight-release.properties",
)
val releaseSigning = signingFile.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
if (releaseSigning == null) logger.warn("Ridge Light: no release key at $signingFile, release builds use the debug key.")

android {
    namespace = "net.ridgelight.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.ridgelight.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Debug builds run the engine several times slower (ART deoptimises debuggable apps,
            // and Compose is unoptimised), so phones should get this one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    androidResources {
        // Lets Android 13+ offer the app's languages under Settings → Apps → Language.
        generateLocaleConfig = true
        // Only the app's own languages, so library texts (dialog buttons…) never mix in a third one.
        localeFilters += listOf("en", "de")
    }
    // F-Droid: no encrypted dependency list in the APK's signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // AGP 9.5 compose previews need resources whenever host tests exist.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":dem-android"))
    implementation(project(":osmand-aidl"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
