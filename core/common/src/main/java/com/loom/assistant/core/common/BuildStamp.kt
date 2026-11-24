package com.loom.assistant.core.common

/**
 * Device-evidence fix (2026-11-05, item 10): the build stamp shown in
 * Settings → Privacy & Security, so a device report can name the exact
 * build ("which commit are you on?" is otherwise unanswerable — every
 * build ships versionName "0.1.0-m0" / versionCode 1).
 *
 * Lives in core:common (dependency-neutral) because the Settings screen
 * cannot depend on the app module.
 *
 * [COMMIT] is a manual constant: bump it to the new HEAD short hash on
 * every release build. The version NAME is read live from the
 * PackageManager in SettingsScreenView (it reflects the APK that was
 * actually installed); only the commit needs this constant, because
 * neither Gradle nor the manual aapt2 build stamps it anywhere the app
 * can read at runtime.
 */
object BuildStamp {
    /** Short hash of the HEAD the APK was built from. */
    const val COMMIT: String = "8140344"
}
