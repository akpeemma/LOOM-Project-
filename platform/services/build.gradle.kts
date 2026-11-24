plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.platform.services"
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
    implementation(project(":presentation:modes"))
    implementation(project(":presentation:designsystem"))
    implementation(project(":interaction:edgelight"))
    implementation(project(":interaction:pointer"))
    implementation(project(":interaction:voice"))
    implementation(project(":intelligence:llm"))
    implementation(project(":intelligence:memory"))
    implementation(project(":execution:screenawareness"))
    implementation(project(":intelligence:authgate"))
    // Stage 2C (F): the service hosts the interaction layer (one-way:
    // execution:interaction never depends back on platform:services).
    implementation(project(":execution:interaction"))
    // Master §2 (items 3–4): the search host path — SearchExecutor
    // (execution:search) and its AppCatalogue resolver. AppCatalogue lives
    // in this module (platform.services .../platform/apps); there is no
    // separate :platform:apps module — the duplicate created during the
    // master build was deleted in integration and the wiring uses the
    // shared catalogue. Acyclic: execution:search depends only on
    // execution:screenawareness + core:common, never back on services.
    implementation(project(":execution:search"))
    // Dictated messaging + calls (2026-11-05, fix-plan item 12): the
    // message/call host path — MessageExecutor + CallExecutor
    // (execution:messaging). Acyclic: execution:messaging depends only
    // on execution:screenawareness + core:common, never back on
    // services.
    implementation(project(":execution:messaging"))
    // Stage 2D (Worker B): the plan host path — the planner core
    // (intelligence:planner) + the executor/queue (execution:tasks).
    // Acyclic (verified by inspection): intelligence:planner depends
    // only on core:common + execution:screenawareness; execution:tasks
    // depends only on core:common + execution:screenawareness +
    // intelligence:planner; neither depends back on platform:services.
    implementation(project(":intelligence:planner"))
    implementation(project(":execution:tasks"))
    // AOIE Phase 7 — the verified-workflow promotion bridge
    // (SkillIntegrationAdapter). skilllab depends only on
    // platform:storage, so this edge cannot cycle.
    implementation(project(":intelligence:skilllab"))
}
