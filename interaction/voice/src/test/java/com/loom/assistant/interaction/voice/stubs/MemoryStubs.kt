// Test-local stub — see VoiceSessionManagerTestStubs.kt.
package com.loom.assistant.intelligence.memory

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long,
    val modality: String = "text",
    val imagePath: String? = null,
)

data class Conversation(
    val id: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)

class ConversationStore {
    private val conv = Conversation("test")
    fun getActive(): Conversation = conv
    fun append(id: String, message: ChatMessage): Conversation {
        conv.messages.add(message)
        return conv
    }
}
