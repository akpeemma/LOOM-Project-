// Test-local shadow of production OverlayPermission (core:common) for the
// vmgr runner ONLY. The real file needs android.provider.Settings,
// android.net.Uri and android.os.Build, which have no harness fakes, and
// the manager calls just granted() — so this stub shadows it the same way
// ModesStubs shadows ModeController. The permission decision itself is
// tested at the planner level (AssistantActivationTest); this stub exists
// so VoiceSessionManager.kt compiles in the JVM harness. It must NOT be
// added to any runner that also compiles the real OverlayPermission.kt.
package com.loom.assistant.core.common

object OverlayPermission {
    fun granted(context: android.content.Context): Boolean = true
}
