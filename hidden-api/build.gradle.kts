// Compile-only stubs for the few @SystemApi classes the helper daemon subclasses or receives
// (AudioPolicy, AudioPolicy.AudioPolicyFocusListener, AudioFocusInfo). The app depends on this
// module with `compileOnly`, so nothing here is packaged: at runtime the device framework's real
// classes are used. Everything else hidden is reached through reflection.
plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.channelramble.multiappaudio.hiddenapi"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
