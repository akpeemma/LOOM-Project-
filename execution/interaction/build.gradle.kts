plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.execution.interaction"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Build-foundation (LIFECYCLE-PLAN.md Addendum A): the guidance rendering
    // contract moved to interaction:pointer, so the module edge runs one
    // direction only — execution:interaction → interaction:pointer. These
    // edges match the actual source imports; previously this block was
    // empty while sources imported from four other modules.
    implementation(project(":core:common"))
    implementation(project(":execution:screenawareness"))
    implementation(project(":intelligence:authgate"))
    implementation(project(":interaction:pointer"))
}
