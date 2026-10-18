import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional release signing. Drop a `keystore.properties` next to settings.gradle.kts
// (or set the same keys as Gradle properties / CI secrets) and the release build
// will be signed with it. Without it, release falls back to the debug key so the
// APK still installs for testing.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ks(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(key) ?: providers.gradleProperty(key).orNull

val releaseStoreFile = ks("KEYSTORE_FILE")?.let { rootProject.file(it) }
val hasReleaseKey = releaseStoreFile != null && releaseStoreFile.exists() &&
    ks("KEYSTORE_PASSWORD") != null && ks("KEY_ALIAS") != null

android {
    namespace = "com.ioskeyboard.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ioskeyboard.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        setProperty("archivesBaseName", "iOS-Keyboard")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = ks("KEYSTORE_PASSWORD")
                keyAlias = ks("KEY_ALIAS")
                keyPassword = ks("KEY_PASSWORD") ?: ks("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
        viewBinding = true
    }

    androidResources {
        // Keep the bundled dictionaries & emoji data untouched.
        noCompress += listOf("txt")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
