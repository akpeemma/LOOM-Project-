package com.loom.assistant.core.common

/**
 * AOIE Phase 5 — process-wide plan-run state (core:common, so
 * every module can read it with no new module edge).
 *
 * LoomPlanRunner sets [isPlanRunning] while a plan executes; the
 * voice-stop handler reads it to say "Stopped." instead of
 * "Nothing is running", and the Stop window shows while it is
 * true. It lives here — not in platform:services — because
 * interaction:voice already depends on platform:services in the
 * other direction, and a voice → services edge would be a module
 * cycle.
 */
object PlanRunState {
    @Volatile
    var isPlanRunning: Boolean = false
}
