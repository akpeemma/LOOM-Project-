plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.interaction.voice"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Stage 2A: VoiceSessionManager orchestrates the shared conversation
    // pipeline (engine + store) and local voice commands.
    // Correction pass: VoiceCommands moved to core:common, so the
    // platform:services dependency (a module cycle) is gone.
    // Stage 2A.1 (D1): the manager calls ModeController.applyMode directly —
    // interaction:voice → presentation:modes is acyclic (modes depends only
    // on core:common + presentation:designsystem; nothing depends back on
    // interaction:voice except platform:services).
    implementation(project(":core:common"))
    implementation(project(":intelligence:llm"))
    implementation(project(":intelligence:memory"))
    implementation(project(":presentation:modes"))
    // Stage 2B (D3a): the manager persists the pointer policy on voice
    // command. Acyclic — interaction:pointer's deps (core:common,
    // presentation:designsystem, execution:screenawareness,
    // intelligence:authgate) never point back at interaction:voice.
    implementation(project(":interaction:pointer"))
    // Stage 2D (Worker C): VoiceSessionManager.Host.onPlanRequested
    // takes the planner's PlanRequest. Acyclic — intelligence:planner
    // depends only on core:common + execution:screenawareness,
    // neither points back at interaction:voice.
    implementation(project(":intelligence:planner"))
    // Build-foundation (LIFECYCLE-PLAN.md Addendum A): VoiceSessionManager
    // imports InteractionLayer + halt types from execution:interaction
    // (voice routing seams). Acyclic — execution:interaction's deps
    // (core:common, execution:screenawareness, intelligence:authgate,
    // interaction:pointer) never point back at interaction:voice.
    implementation(project(":execution:interaction"))
}
