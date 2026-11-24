plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.execution.integrations"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}
