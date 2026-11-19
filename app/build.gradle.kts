import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Signing config for release builds.
 *
 * The key lives in the repository at signing/stremio.keystore. That is a deliberate trade: the
 * alternative is the debug key, and because every machine and every CI runner generates its own
 * debug key, APKs built in different places refuse to install over one another. A single shared
 * key means every build is an update of the last. It protects nothing secret - this app is not
 * published to any store - and local.properties or the STREMIO_KEYSTORE environment variable
 * still override it with a private key when that is wanted.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** Password for the committed key. Not a secret: the keystore sits next to it in the repo. */
val DEFAULT_KEYSTORE_PASSWORD = "stremiolite"

fun signingValue(propertyName: String, environmentName: String): String? =
    keystoreProperties.getProperty(propertyName) ?: System.getenv(environmentName)

val releaseKeystorePath = signingValue("stremio.keystore", "STREMIO_KEYSTORE")
val releaseKeystore = (releaseKeystorePath?.let(::file) ?: rootProject.file("signing/stremio.keystore"))
    .takeIf { it.exists() }

android {
    namespace = "com.stremio.lite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stremio.lite"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "2.0"

        ndk {
            // libtorrent4j ships one native library per ABI; these cover every real
            // Android phone plus the 64-bit emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // libtorrent's native library dominates the APK size, so a per-ABI APK is roughly half the
    // universal one. Most phones want arm64-v8a; the universal APK stays available as a fallback.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("stremio.keystorePassword", "STREMIO_KEYSTORE_PASSWORD")
                    ?: DEFAULT_KEYSTORE_PASSWORD
                keyAlias = signingValue("stremio.keyAlias", "STREMIO_KEY_ALIAS") ?: "stremio"
                keyPassword = signingValue("stremio.keyPassword", "STREMIO_KEY_PASSWORD")
                    ?: DEFAULT_KEYSTORE_PASSWORD
                // Sign with every scheme: v1/v2 for older devices, v3 so recent Android versions
                // (and Samsung's installer checks) see a full-strength signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The shared release key when it is available, so updates install over each other;
            // otherwise the debug key, so the build still works with no setup.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // In-app BitTorrent engine (libtorrent via JNI) — the same one LibreTorrent uses.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
