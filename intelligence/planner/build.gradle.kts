plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.intelligence.planner"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Stage 2D (Worker A) — matches the actual source imports only:
    // RequestOrigin (core:common); ScreenModel + ScreenDataPolicy
    // (execution:screenawareness). No intelligence:authgate edge —
    // PlanAuthCover is the planner's own seam; the gate is Worker B's
    // dependency. No execution:interaction edge — the PlanStepKind
    // mapping is Worker B's translation (see TaskStepKind KDoc).
    // Graph stays acyclic: nothing depends on intelligence:planner.
    implementation(project(":core:common"))
    implementation(project(":execution:screenawareness"))
}
