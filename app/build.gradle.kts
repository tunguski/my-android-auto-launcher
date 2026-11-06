import java.util.Properties

plugins {
    id("com.android.application")
    // Coverage of the unit tests only. The on-device tests are journeys: what
    // they prove is that a screen comes up and answers, not which lines ran, and
    // instrumenting the APK to find out would slow down the slowest part of the
    // build to measure the part that matters least.
    jacoco
}

val appVersionName = "2.4"
val appVersionCode = 15

// Output file names carry the version, e.g. navi-1.8-debug.apk.
base {
    archivesName = "navi-$appVersionName"
}

// Release signing: the key never goes into the repository. Path and passwords come
// from keystore.properties (locally) or from the NAVI_KEYSTORE, NAVI_KEYSTORE_PASSWORD,
// NAVI_KEY_ALIAS and NAVI_KEY_PASSWORD environment variables (GitHub Actions).
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun secret(
    key: String,
    env: String,
): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseKeystore = secret("storeFile", "NAVI_KEYSTORE")

android {
    namespace = "pl.matsuo.navi"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.matsuo.navi"
        minSdk = 26
        // Deliberately 34, not 35: avoids the forced edge-to-edge of Android 15.
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Unsplash, for the pictures in the settings. Declared here rather than
        // in strings.xml so that the repository, which is public, never holds a
        // key: it comes from keystore.properties locally or NAVI_UNSPLASH_KEY in
        // CI, and is empty in a build made from the source alone. Empty means
        // the pictures come from the ones bundled in the APK, which is the whole
        // reason they are bundled.
        resValue("string", "unsplash_key", secret("unsplashKey", "NAVI_UNSPLASH_KEY") ?: "")
    }

    sourceSets {
        // The trip fixtures are shared: the unit tests check the recording rules
        // against them, the on-device tests show them on screen.
        getByName("test") { java.srcDir("src/testShared/java") }
        getByName("androidTest") {
            java.srcDir("src/testShared/java")
            // The canned Open-Meteo readings travel with the test APK, so the
            // on-device tests see exactly the weather the unit tests do.
            assets.srcDir("../tools/wx")
        }
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = secret("storePassword", "NAVI_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "NAVI_KEY_ALIAS")
                keyPassword = secret("keyPassword", "NAVI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // No applicationIdSuffix: the debug APK is the one sideloaded onto the head
            // unit for testing, so it must carry the real application id.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

// The app itself has zero dependencies: no AndroidX, no third-party libraries.
// Nothing below ships inside the APK - it is all test-only.
dependencies {
    testImplementation("junit:junit:4.13.2")
    // The org.json in android.jar is a stub that throws; the real one is needed to
    // run the weather rules against the canned readings on a plain JVM. The
    // mockable android jar comes last on the test classpath, so this one wins.
    testImplementation("org.json:json:20231013")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Sources are UTF-8 regardless of the platform default (Windows would use CP-1250).
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// ───────────────────────────── coverage ─────────────────────────────

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Needed for anything built against the Android plugin: without it the
        // agent trips over the classes the mockable android.jar generates.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

/**
 * What the unit tests cover, as HTML and XML in build/reports/jacoco.
 *
 * The screens are left out on purpose. MainActivity builds its views in code and
 * can only be exercised on a device, so counting its lines here would report a
 * number that falls whenever a screen grows, and say nothing about the rules the
 * unit tests exist to pin down.
 */
tasks.register<JacocoReport>("coverage") {
    group = "verification"
    description = "Coverage of the unit tests."
    dependsOn("testDebugUnitTest", "compileDebugJavaWithJavac")

    reports {
        html.required.set(true)
        xml.required.set(true)
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.*",
                "**/MainActivity*.class",
                "**/TripService*.class",
                "**/BootReceiver*.class",
                "**/RouteView*.class",
                "**/Glyph*.class",
                "**/WxIcon*.class",
                "**/ApkProvider*.class",
            )
        },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    // The one file, not a tree over the whole build directory: a tree there makes
    // Gradle believe this task consumes the output of every other one.
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
}
