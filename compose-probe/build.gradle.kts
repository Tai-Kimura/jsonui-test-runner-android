// Not published. An app whose screens are Compose, for the arms that need a
// Compose control the driver acts on (a Material3 Slider, as kjui emits it):
// the driver module cannot host them — its androidTest classpath is held to
// the driver's own lifecycle 2.3.1, which Compose's activity cannot run on,
// and the Compose compiler would need Compose on the driver's unit-test
// classpath. The driver reaches these tests as an androidTest dependency.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jsonui.testrunner.composeprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jsonui.testrunner.composeprobe"
        minSdk = 24
        // As the driver's androidTest: an older target greets every launch
        // with the "built for an older version of Android" dialog on API 34+.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        // The Compose compiler for the build's Kotlin (1.9.21).
        kotlinCompilerExtensionVersion = "1.5.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")

    androidTestImplementation(project(":jsonuitestrunner"))
    androidTestImplementation(libs.kotlinx.serialization.json)
}
