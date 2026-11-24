plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.presentation.chat"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // In-app routing (fix plan item 5, pointer H4): typed GUIDE_TO
    // starts guidance through InteractionLayerRegistry. Acyclic —
    // execution:interaction's deps (core:common,
    // execution:screenawareness, intelligence:authgate,
    // interaction:pointer) never point back at presentation:chat.
    implementation(project(":execution:interaction"))
}
