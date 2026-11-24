plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.execution.tasks"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Stage 2D (Worker B) — the plan executor + queue: Worker A's planner
    // core (intelligence:planner) + the screen model (execution:
    // screenawareness) + RequestOrigin (core:common). Acyclic: the
    // planner depends only on core:common + execution:screenawareness,
    // and nothing in this module is depended on by the planner.
    implementation(project(":core:common"))
    implementation(project(":execution:screenawareness"))
    implementation(project(":intelligence:planner"))
}
