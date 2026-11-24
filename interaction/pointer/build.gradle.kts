plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.interaction.pointer"
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
    implementation(project(":presentation:designsystem"))
    implementation(project(":execution:screenawareness"))
    implementation(project(":intelligence:authgate"))
    // Stage 2D (Worker C): PlanPointerGuide implements the planner's
    // PointerGuide. Acyclic — intelligence:planner depends only on
    // core:common + execution:screenawareness, neither points back.
    implementation(project(":intelligence:planner"))
}
