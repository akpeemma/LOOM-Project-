plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loom.assistant.presentation.home"
    compileSdk = 35

    defaultConfig {
        
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m0"
    }
}

dependencies {
    // Pointer failure fix (2026-11-05): Home reads the live a11y host
    // (LoomAccessibilityService.isLive), the reason-carrying seam
    // (ShowPointerFailure) and the overlay service from
    // :platform:services — the same module HomeScreenView already
    // reaches for LoomOverlayService. Acyclic: :platform:services
    // never depends back on :presentation:home.
    implementation(project(":platform:services"))
}
