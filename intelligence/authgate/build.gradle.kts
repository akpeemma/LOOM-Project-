plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.intelligence.authgate"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Declared to match actual source imports (build-foundation rule):
    // AuthGate.kt imports com.loom.assistant.lifecycle.* (core:common)
    // and com.loom.assistant.platform.storage.* (platform:storage).
    implementation(project(":core:common"))
    implementation(project(":platform:storage"))
}
