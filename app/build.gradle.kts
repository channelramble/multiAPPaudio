import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.channelramble.multiappaudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.channelramble.multiappaudio"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // The helper daemon is loaded reflectively by Shizuku and reaches framework internals
            // through reflection, so keep the code un-obfuscated.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Hidden-API access is deliberate (it runs in the shell-uid helper, not the app).
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Stubs for AudioPolicy / AudioFocusInfo (@SystemApi); never packaged.
    compileOnly(project(":hidden-api"))
}
