plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("signing")
}

android {
    namespace = "com.jsonui.testrunner"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // The androidTest APK's own targetSdk. Unset, it defaults to minSdk
        // (24), and API 34+ greets such an APK with the system's "built for
        // an older version of Android" dialog on every launch of the probe
        // activity. That dialog is an `android`-owned window that outlives
        // the instrumentation process and becomes the ACTIVE a11y window,
        // so any on-device test that needs the app window (AppWindow root,
        // scroll surface) sees a system dialog instead. Measured 2026-09-03
        // on conf_ci (API 35): rootInActiveWindow = android/FrameLayout
        // 1160x413, the probe activity absent from uiAutomation.windows.
        targetSdk = 34
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(libs.kotlinx.serialization.json)

    // Test dependencies (exposed as API for consumers)
    api(libs.espresso.core)
    api(libs.uiautomator)
    api(libs.androidx.test.core)
    api(libs.androidx.test.runner)
    api(libs.androidx.test.rules)
    api(libs.androidx.test.ext.junit)
    api(libs.junit)
}

signing {
    val signingKey = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String?

    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("io.github.tai-kimura", "jsonui-test-runner-android", "1.8.5")

    pom {
        name.set("JsonUI Test Runner (Android)")
        description.set("Android (UIAutomator) driver for running JsonUI JSON-based UI tests")
        url.set("https://github.com/Tai-Kimura/jsonui-test-runner-android")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("tai-kimura")
                name.set("Taichiro Kimura")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/Tai-Kimura/jsonui-test-runner-android.git")
            developerConnection.set("scm:git:ssh://github.com/Tai-Kimura/jsonui-test-runner-android.git")
            url.set("https://github.com/Tai-Kimura/jsonui-test-runner-android")
        }
    }
}
