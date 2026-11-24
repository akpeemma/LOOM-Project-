package com.loom.assistant.intelligence.memory

/** A single message in a conversation. role = "user" | "assistant". */
data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long,
    val modality: String = "text",
    /** App-private file path when modality == "image". Null otherwise. */
    val imagePath: String? = null,
)

data class Conversation(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val messageCount: Int,
)
