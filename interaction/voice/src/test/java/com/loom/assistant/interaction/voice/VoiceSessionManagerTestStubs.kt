// Test-local stubs for VoiceSessionManagerTest. These declare ONLY the
// surface VoiceSessionManager.kt actually uses (signatures copied from the
// real classes); the real Gradle build compiles the manager against the real
// implementations. Kept in the test source set — never shipped.

package com.loom.assistant.intelligence.llm

interface ResponseEngine {
    fun generate(
        userText: String,
        historySize: Int,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
        systemContext: String? = null,
    )
}
