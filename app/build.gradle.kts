plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.loom.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":presentation:designsystem"))
    implementation(project(":presentation:modes"))
}
