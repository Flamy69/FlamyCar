import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from gradle.properties, or from the release tag on CI (-PappVersion=1.2.3).
// "1.2.3" becomes version code 10203, so every release installs over the previous one.
val appVersion: String = providers.gradleProperty("appVersion").get()
val appVersionCode: Int = appVersion.split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Release signing is configured outside the repository: in a keystore.properties file next to
// settings.gradle.kts, or in environment variables of the same names on CI.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: keystoreProperties.getProperty(name)

val releaseKeystore: String? = signingValue("FLAMY_KEYSTORE_FILE")

android {
    namespace = "io.github.flamy69.flamycar"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.flamy69.flamycar"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("FLAMY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("FLAMY_KEY_ALIAS")
                keyPassword = signingValue("FLAMY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the build falls back to the debug key, so that
            // `assembleRelease` still yields an installable APK on a fresh checkout. Such a
            // build cannot be installed over one signed with the real key, and vice versa.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // The test that draws the gauges needs the texts of the app.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.coroutines.android)

    // Android for Cars: the screen on the display of the car.
    implementation(libs.car.app)

    testImplementation(libs.junit)
    // Android's own drawing code on the desktop, to see the gauges without a phone or a car.
    testImplementation(libs.robolectric)
    testImplementation(libs.car.app.testing)
}
