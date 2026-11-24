package com.loom.assistant.core.common

/**
 * Device-test failures (2026-11-11, D-05) — pure-JVM checks for the
 * last-task memory: "execute the task" / "do it" resolves to the task
 * David already gave, even after it ended.
 *
 * Run: kotlinc ConversationContext.kt TaskRecord.kt RequestOrigin.kt
 *   ConversationContextTest.kt -d out && java -cp out
 *   com.loom.assistant.core.common.ConversationContextTestKt
 * (No android.* imports in these sources, so no fakes are needed.)
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun storyRecord() = TaskRecord(
    instruction = "open my story",
    app = "Facebook",
    appPackage = "com.facebook.katana",
    query = null,
    origin = RequestOrigin.DIRECT_USER,
)

fun main() {
    // A started task is both the active task and the last task.
    val ctx = ConversationContext()
    check("no task at first", ctx.referencedTask() == null)
    ctx.startTask(storyRecord())
    checkEq("active task is the story", ctx.activeTask?.instruction, "open my story")
    checkEq("last task is the story", ctx.lastTask?.instruction, "open my story")
    checkEq("referenced task is the story", ctx.referencedTask()?.instruction, "open my story")

    // The outcome rides onto both records — a completed task is
    // named as done, never re-run.
    ctx.noteTaskOutcome(true)
    checkEq("active task completed", ctx.activeTask?.status, TaskRecordStatus.COMPLETED)
    checkEq("last task completed", ctx.lastTask?.status, TaskRecordStatus.COMPLETED)
    checkEq("referenced task completed", ctx.referencedTask()?.status, TaskRecordStatus.COMPLETED)

    // clearTask drops the ACTIVE record but KEEPS the last one —
    // "execute the task" after a failure still resolves.
    val ctx2 = ConversationContext()
    ctx2.startTask(storyRecord())
    ctx2.noteTaskOutcome(false)
    checkEq("failed task status", ctx2.activeTask?.status, TaskRecordStatus.FAILED)
    ctx2.clearTask()
    check("active task cleared", ctx2.activeTask == null)
    checkEq("last task survives clearTask", ctx2.lastTask?.instruction, "open my story")
    checkEq("last task still failed", ctx2.lastTask?.status, TaskRecordStatus.FAILED)
    checkEq("referenced task is the failed story", ctx2.referencedTask()?.instruction, "open my story")

    // A new task replaces both slots.
    val ctx3 = ConversationContext()
    ctx3.startTask(storyRecord())
    ctx3.noteTaskOutcome(false)
    ctx3.clearTask()
    val search = TaskRecord(
        instruction = "search for Spider-Man in YouTube",
        app = "YouTube",
        appPackage = "com.google.android.youtube",
        query = "Spider-Man",
        origin = RequestOrigin.DIRECT_USER,
    )
    ctx3.startTask(search)
    checkEq("new task replaces the last task", ctx3.lastTask?.instruction, "search for Spider-Man in YouTube")
    checkEq("referenced task is the new task", ctx3.referencedTask()?.instruction, "search for Spider-Man in YouTube")

    // noteLastTask (D-05 refined): a launch is remembered as the
    // LAST task only — it never becomes the active task and never
    // promotes the last-turn app (F3c: a launch is an attempt until
    // confirmed). A later "do it" still resolves to it, and the
    // outcome rides onto it.
    val ctx4 = ConversationContext()
    val launch = TaskRecord(
        instruction = "open my story",
        app = null,
        appPackage = null,
        query = null,
        origin = RequestOrigin.DIRECT_USER,
    )
    ctx4.noteLastTask(launch)
    check("launch note leaves no active task", ctx4.activeTask == null)
    checkEq("launch note sets the last task", ctx4.lastTask?.instruction, "open my story")
    check("launch note promotes no app", ctx4.appForFollowUpSearch() == null)
    checkEq("referenced task is the launch", ctx4.referencedTask()?.instruction, "open my story")
    ctx4.noteTaskOutcome(true)
    checkEq("launch last task completed", ctx4.lastTask?.status, TaskRecordStatus.COMPLETED)
    // A later active task wins the reference; the launch stays last.
    ctx4.startTask(search)
    checkEq("active task wins the reference", ctx4.referencedTask()?.instruction, "search for Spider-Man in YouTube")
    checkEq("startTask replaces the last task", ctx4.lastTask?.instruction, "search for Spider-Man in YouTube")

    // clear() drops everything — a fresh session has no task.
    ctx3.clear()
    check("clear drops the last task", ctx3.lastTask == null)
    check("clear drops the referenced task", ctx3.referencedTask() == null)

    println("ConversationContextTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
