plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.presentation.settings"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":platform:storage"))
    implementation(project(":platform:services"))
    implementation(project(":presentation:designsystem"))
    implementation(project(":presentation:panels"))
    implementation(project(":presentation:modes"))
    implementation(project(":intelligence:llm"))
    implementation(project(":intelligence:memory"))
    // Master §8: "always allow" grant revocation row in Settings → Privacy.
    implementation(project(":intelligence:authgate"))
    // Stage 2B (D3a): pointer visibility policy row in Settings → Automation.
    implementation(project(":interaction:pointer"))
}
