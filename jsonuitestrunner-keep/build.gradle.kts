// No code: the consumer rules that keep, in the app, what the test APK takes from it. An app depends on this for the
// build type its UI tests run on, so the rules reach that build's R8 and never the one that ships. The driver cannot
// carry them: an androidTest dependency's consumer rules reach the test APK's R8 only.
plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("signing")
}

android {
    namespace = "com.jsonui.testrunner.keep"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
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

    // The driver's version: KeepRulesTest holds the two together.
    coordinates("io.github.tai-kimura", "jsonui-test-runner-android-keep", "1.21.1")

    pom {
        name.set("JsonUI Test Runner (Android): keep rules")
        description.set("R8 rules for an app whose JsonUI UI tests run on a minified build type")
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
