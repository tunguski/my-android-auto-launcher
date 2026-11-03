import java.util.Properties

plugins {
    id("com.android.application")
}

val appVersionName = "2.2"
val appVersionCode = 13

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
