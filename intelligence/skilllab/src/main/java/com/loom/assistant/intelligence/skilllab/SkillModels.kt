package com.loom.assistant.intelligence.skilllab

/**
 * The 7 locked skill lifecycle statuses (lockdown card 9, arch §2.10).
 * Transitions are validated in [SkillCatalog] — illegal jumps are refused.
 */
enum class SkillStatus(val label: String) {
    DISCOVERED("Discovered"),
    IN_DEVELOPMENT("In development"),
    TESTING("Testing"),
    APPROVED("Approved"),
    INSTALLED("Installed"),
    NEEDS_IMPROVEMENT("Needs improvement"),
    DISABLED("Disabled"),
}

data class SkillRecord(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val status: SkillStatus,
    val source: String,
    val installedAt: Long,
    val updatedAt: Long,
)
