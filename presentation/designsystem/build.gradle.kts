plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.presentation.designsystem"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
    }
}
