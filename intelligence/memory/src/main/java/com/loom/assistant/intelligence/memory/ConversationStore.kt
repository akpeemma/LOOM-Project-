package com.loom.assistant.intelligence.memory

import android.content.Context
import android.content.SharedPreferences
import com.loom.assistant.platform.storage.ActivityLog
import java.io.File
import java.util.UUID

/**
 * THE shared conversation store. Home Quick Voice, full-screen voice and
 * full-screen text all read/write the same active conversation — switching
 * interface never restarts or disconnects it (lockdown decision).
 *
 * Storage: one JSON file per conversation + a small index file, all in
 * app-private storage. No Room on the manual build classpath.
 */
class ConversationStore private constructor(private val appContext: Context) {

    private val dir: File = File(appContext.filesDir, "loom_conversations").apply { mkdirs() }
    private val indexFile: File = File(dir, "index.json")
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("loom_conversation_prefs", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: ConversationStore? = null

        fun get(context: Context): ConversationStore {
            return instance ?: synchronized(this) {
                instance ?: ConversationStore(context.applicationContext).also { instance = it }
            }
        }
    }

    // ---- active conversation ----

    /** The single active thread shared by Home voice, chat text and chat voice. */
    fun getActive(): Conversation {
        val id = prefs.getString("active_id", null)
        if (id != null) {
            try {
                return get(id)
            } catch (_: Exception) {
                // fall through: recreate
            }
        }
        return create()
    }

    fun setActive(id: String) {
        prefs.edit().putString("active_id", id).apply()
    }

    // ---- CRUD ----

    @Synchronized
    fun create(): Conversation {
        val now = System.currentTimeMillis()
        val c = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New conversation",
            createdAt = now,
            updatedAt = now,
        )
        save(c)
        setActive(c.id)
        ActivityLog.get(appContext).record("conversation", "New conversation started")
        return c
    }

    @Synchronized
    fun get(id: String): Conversation {
        val f = File(dir, "conv_$id.json")
        if (!f.exists()) throw IllegalArgumentException("conversation not found: $id")
        return JsonUtil.parseConversation(f.readText())
    }

    /**
     * Appends a message. The first user message also derives the title.
     * Returns the updated conversation.
     */
    @Synchronized
    fun append(id: String, message: ChatMessage): Conversation {
        val c = get(id)
        val isFirstUser = c.messages.none { it.role == "user" } && message.role == "user"
        c.messages.add(message)
        c.updatedAt = message.timestamp
        if (isFirstUser) {
            c.title = deriveTitle(message.text)
        }
        save(c)
        return c
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val c = get(id)
        c.title = title.ifBlank { "Untitled" }
        c.updatedAt = System.currentTimeMillis()
        save(c)
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "conv_$id.json").delete()
        rebuildIndex()
        if (prefs.getString("active_id", null) == id) {
            prefs.edit().remove("active_id").apply()
        }
    }

    /** Recents, newest first. */
    @Synchronized
    fun list(): List<ConversationSummary> {
        return readIndex().sortedByDescending { it.updatedAt }
    }

    /** Case-insensitive search over titles and message text. */
    @Synchronized
    fun search(query: String): List<ConversationSummary> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        val hits = ArrayList<ConversationSummary>()
        for (s in readIndex()) {
            if (s.title.lowercase().contains(q) || s.preview.lowercase().contains(q)) {
                hits.add(s); continue
            }
            // deep search: scan message bodies of candidates not yet matched
            try {
                val c = get(s.id)
                if (c.messages.any { it.text.lowercase().contains(q) }) hits.add(s)
            } catch (_: Exception) {
            }
        }
        return hits.sortedByDescending { it.updatedAt }
    }

    // ---- internals ----

    private fun save(c: Conversation) {
        File(dir, "conv_${c.id}.json").writeText(JsonUtil.conversationToJson(c))
        rebuildIndex()
    }

    private fun rebuildIndex() {
        val summaries = dir.listFiles { f -> f.name.startsWith("conv_") && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    val c = JsonUtil.parseConversation(f.readText())
                    val last = c.messages.lastOrNull()
                    ConversationSummary(
                        id = c.id,
                        title = c.title,
                        updatedAt = c.updatedAt,
                        preview = last?.let {
                            if (it.modality == "image") "[image] ${it.text}".trim() else it.text
                        } ?: "",
                        messageCount = c.messages.size,
                    )
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()
        indexFile.writeText(JsonUtil.indexToJson(summaries))
    }

    private fun readIndex(): List<ConversationSummary> {
        if (!indexFile.exists()) return emptyList()
        return try {
            JsonUtil.parseIndex(indexFile.readText())
        } catch (_: Exception) {
            rebuildIndex()
            try {
                JsonUtil.parseIndex(indexFile.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun deriveTitle(text: String): String {
        val clean = text.replace("\\s+".toRegex(), " ").trim()
        if (clean.isEmpty()) return "New conversation"
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}
