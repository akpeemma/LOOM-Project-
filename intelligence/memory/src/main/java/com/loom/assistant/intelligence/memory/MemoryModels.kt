package com.loom.assistant.intelligence.memory

/**
 * The 6 locked memory categories (lockdown card 10, arch §2.11).
 * Selective retention — never log everything.
 */
enum class MemoryCategory(val title: String, val blurb: String) {
    PREFERENCES("Personal Preferences", "How you like things done"),
    PROJECT_KNOWLEDGE("Project Knowledge", "Facts about your projects"),
    WORKFLOWS("Workflows", "Repeated ways of working"),
    SKILLS_EXPERIENCE("Skills & Experience", "What LOOM has learned to do"),
    DECISIONS("Decisions", "Choices you've made"),
    CORRECTIONS_LESSONS("Corrections & Lessons", "Mistakes corrected, lessons kept"),
}

data class MemoryItem(
    val id: String,
    val category: MemoryCategory,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)
