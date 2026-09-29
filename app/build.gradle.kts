import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (set by .github/workflows/release.yml). Without it,
// local release builds are simply left unsigned.
val releaseKeystore: String? = System.getenv("MMA_KEYSTORE")

android {
    namespace = "io.github.channelramble.multiappaudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.channelramble.multiappaudio"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MMA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MMA_KEY_ALIAS")
                keyPassword = System.getenv("MMA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // DUMP / QUERY_ALL_PACKAGES are deliberate (sideloaded app, DUMP granted over ADB).
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
