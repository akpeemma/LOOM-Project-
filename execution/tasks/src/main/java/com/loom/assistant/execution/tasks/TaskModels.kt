package com.loom.assistant.execution.tasks

/** The 4 locked task tabs (lockdown card 8). */
enum class TaskStatus(val label: String) {
    ACTIVE("Active"),
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    FAILED("Failed"),
}

data class LoomTask(
    val id: String,
    val title: String,
    val detail: String,
    val status: TaskStatus,
    /** 0–100. */
    val progress: Int,
    val scheduledAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val attempts: Int,
)

enum class ProjectStatus(val label: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
}

data class LoomProject(
    val id: String,
    val name: String,
    val description: String,
    val status: ProjectStatus,
    val updatedAt: Long,
    val createdAt: Long,
)
