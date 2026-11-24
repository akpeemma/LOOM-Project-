package com.loom.assistant.core.common

/**
 * MASTER Wave 4 (F10) — helper lifecycle diagnostics + per-capability
 * liveness. Pure Kotlin, no android.* imports, so the JVM harness can
 * prove the decision logic (which capability is dead, why, and what
 * repairs it) without a phone.
 *
 * The video's helper failure (Nov 8, ~101–175s) is the case this
 * answers: Android said "malfunctioning" with the toggle ON, Device
 * Control said "Not running", yet the app still did some things. A
 * single `isBound` boolean cannot describe that state — and the old
 * repair ladder was one step (the off/on toggle) which the video
 * proves does not revive a dead helper.
 *
 * Three pieces:
 *  - [HelperStartupRecord]: per-section startup failures. A failure
 *    in one `onCreate` section must never kill the helper; each
 *    section is guarded separately and its failure recorded here.
 *  - [CapabilityLivenessChecker]: per-capability liveness (open /
 *    type / tap / scroll), each verified by its own probe of the
 *    live preconditions — never inferred from one boolean. When a
 *    capability is dead it names the cause and the repair step.
 *  - [HelperDownRecorder]: the helper-down event log (with battery
 *    state at the time), so a dead helper leaves a record instead
 *    of a Toast that vanishes.
 */

/** Why a capability is dead. Ordered most-actionable first. */
enum class HelperCause {
    /** The accessibility service is switched off in Settings. */
    SERVICE_OFF,

    /** Switched on but not running (bound check fails) — the video's case. */
    SERVICE_DOWN,

    /** Battery optimization is killing / will kill the helper. */
    BATTERY_OPTIMIZATION,

    /** Android's "pause app activity if unused" auto-revoke is on. */
    AUTO_REVOKE,

    /** The approval check (AuthGate) is not ready. */
    AUTH_GATE_NOT_READY,

    /** The screen cannot be read (no snapshot). */
    SCREEN_UNREADABLE,

    /** Dead for a reason the probes cannot name. */
    UNKNOWN,
}

/** One of the four hands Device Control reports on (F10 strengthening). */
enum class CapabilityId(val label: String) {
    OPEN("open"),
    TYPE("type"),
    TAP("tap"),
    SCROLL("scroll"),
}

sealed interface CapabilityState {
    data object Live : CapabilityState
    data class Dead(val cause: HelperCause, val repair: RepairStep) : CapabilityState
}

/** One step of the repair ladder beyond the toggle. */
data class RepairStep(
    /** Stable id: "toggle", "rebind", "battery", "auto-revoke", "app-info", "restart-phone", "reinstall". */
    val id: String,
    /** Plain-words title shown in Device Control. */
    val title: String,
    /** Plain-words detail: what David does / what LOOM does. */
    val detail: String,
)

/**
 * The repair ladder, ordered. The toggle is step 1 only — the video
 * proves it does not revive a dead helper, so the ladder continues:
 * rebind attempt, battery exemption, auto-revoke off, app-info
 * check, phone restart, reinstall. [repairLadder] returns the steps
 * from the first one that applies to [cause] onwards.
 */
fun repairLadder(cause: HelperCause): List<RepairStep> {
    val all = listOf(
        RepairStep(
            "toggle",
            "Turn the helper off and on again",
            "In Settings, under Accessibility, turn LOOM off, wait a few seconds, then turn it on again.",
        ),
        RepairStep(
            "rebind",
            "Ask LOOM to reconnect the helper",
            "LOOM tries to bind the helper again and reports whether it came back — no settings trip needed.",
        ),
        RepairStep(
            "battery",
            "Let LOOM run without battery limits",
            "Open the battery settings for LOOM and allow it to run in the background — battery optimization can switch the helper off overnight.",
        ),
        RepairStep(
            "auto-revoke",
            "Stop Android pausing LOOM when unused",
            "In LOOM's app info, turn off \"Pause app activity if unused\" — with it on, Android can revoke LOOM's permissions after a quiet period.",
        ),
        RepairStep(
            "app-info",
            "Check LOOM in app info",
            "Open LOOM's app info and check that all permissions are still granted and the app is not force-stopped.",
        ),
        RepairStep(
            "restart-phone",
            "Restart the phone",
            "A restart clears a stuck accessibility service when the toggle and a rebind do not.",
        ),
        RepairStep(
            "reinstall",
            "Reinstall LOOM",
            "Last resort: uninstall and reinstall LOOM, then turn the helper on again in Settings.",
        ),
    )
    val start = when (cause) {
        HelperCause.SERVICE_OFF -> 0
        HelperCause.SERVICE_DOWN -> 0
        HelperCause.BATTERY_OPTIMIZATION -> 2
        HelperCause.AUTO_REVOKE -> 3
        HelperCause.AUTH_GATE_NOT_READY -> 1
        HelperCause.SCREEN_UNREADABLE -> 1
        HelperCause.UNKNOWN -> 0
    }
    return all.drop(start)
}

/** The live preconditions one liveness probe reads. */
interface LivenessEnv {
    /** The service is enabled in Settings.Secure. */
    val isAccessibilityEnabled: Boolean

    /** The service is bound right now (live holder signal). */
    val isServiceBound: Boolean

    /** AuthGate is ready to issue tokens. */
    val authGateReady: Boolean

    /** A fresh screen snapshot can be read. */
    val snapshotReadable: Boolean

    /** True when the system ignores battery optimizations for LOOM. Null when unreadable. */
    val batteryIgnoringOptimizations: Boolean?

    /** True when LOOM is whitelisted from auto-revoke. Null when unreadable (API < 30 or no answer). */
    val autoRevokeWhitelisted: Boolean?
}

/**
 * Per-capability liveness from the probe environment. Rules:
 *  - Service off → everything dead, cause SERVICE_OFF.
 *  - Service on but unbound → everything dead. Cause is the most
 *    actionable named one: battery optimization (system is NOT
 *    ignoring optimizations) beats auto-revoke (not whitelisted)
 *    beats plain SERVICE_DOWN — each names a different repair.
 *  - Bound but AuthGate not ready → tap/type/scroll dead
 *    (AUTH_GATE_NOT_READY); open stays live (launch is an intent,
 *    not a gesture sink).
 *  - Bound + gate ready but no readable snapshot → tap/type/scroll
 *    dead (SCREEN_UNREADABLE); open stays live.
 *  - Otherwise all four live.
 */
object CapabilityLivenessChecker {
    fun evaluate(env: LivenessEnv): Map<CapabilityId, CapabilityState> {
        if (!env.isAccessibilityEnabled) {
            val repair = repairLadder(HelperCause.SERVICE_OFF).first()
            return CapabilityId.entries.associateWith {
                CapabilityState.Dead(HelperCause.SERVICE_OFF, repair)
            }
        }
        if (!env.isServiceBound) {
            val cause = when {
                env.batteryIgnoringOptimizations == false -> HelperCause.BATTERY_OPTIMIZATION
                env.autoRevokeWhitelisted == false -> HelperCause.AUTO_REVOKE
                else -> HelperCause.SERVICE_DOWN
            }
            val repair = repairLadder(cause).first()
            return CapabilityId.entries.associateWith {
                CapabilityState.Dead(cause, repair)
            }
        }
        if (!env.authGateReady) {
            val repair = repairLadder(HelperCause.AUTH_GATE_NOT_READY).first()
            val dead = CapabilityState.Dead(HelperCause.AUTH_GATE_NOT_READY, repair)
            return mapOf(
                CapabilityId.OPEN to CapabilityState.Live,
                CapabilityId.TYPE to dead,
                CapabilityId.TAP to dead,
                CapabilityId.SCROLL to dead,
            )
        }
        if (!env.snapshotReadable) {
            val repair = repairLadder(HelperCause.SCREEN_UNREADABLE).first()
            val dead = CapabilityState.Dead(HelperCause.SCREEN_UNREADABLE, repair)
            return mapOf(
                CapabilityId.OPEN to CapabilityState.Live,
                CapabilityId.TYPE to dead,
                CapabilityId.TAP to dead,
                CapabilityId.SCROLL to dead,
            )
        }
        return CapabilityId.entries.associateWith { CapabilityState.Live }
    }

    /** True when every capability is live. */
    fun allLive(states: Map<CapabilityId, CapabilityState>): Boolean =
        states.values.all { it is CapabilityState.Live }

    /** The first dead capability, if any — the one the helper-down gate reports. */
    fun firstDead(states: Map<CapabilityId, CapabilityState>): Pair<CapabilityId, CapabilityState.Dead>? {
        for ((id, state) in states) {
            if (state is CapabilityState.Dead) return id to state
        }
        return null
    }
}

/**
 * The helper-down gate (F10 strengthening, C-04): before attempting
 * a task that needs the helper, LOOM checks liveness and says so
 * instead of attempting — it never claims success for an action it
 * could not perform. Returns the plain-words refusal when any
 * capability the task needs is dead, else null. [needed] defaults
 * to all four capabilities.
 */
object HelperDownGate {
    fun decide(
        states: Map<CapabilityId, CapabilityState>,
        needed: Set<CapabilityId> = CapabilityId.entries.toSet(),
    ): String? {
        for (id in CapabilityId.entries) {
            if (id !in needed) continue
            val state = states[id] as? CapabilityState.Dead ?: continue
            val causeWords = when (state.cause) {
                HelperCause.SERVICE_OFF -> "the helper is switched off"
                HelperCause.SERVICE_DOWN -> "the helper is switched on but not running"
                HelperCause.BATTERY_OPTIMIZATION ->
                    "battery optimization is switching the helper off"
                HelperCause.AUTO_REVOKE ->
                    "Android paused LOOM for being unused"
                HelperCause.AUTH_GATE_NOT_READY -> "LOOM's approval check isn't ready"
                HelperCause.SCREEN_UNREADABLE -> "LOOM can't read the screen right now"
                HelperCause.UNKNOWN -> "the helper isn't responding"
            }
            return "I can't do that right now — $causeWords, so ${id.label} isn't available. " +
                "${state.repair.title}: ${state.repair.detail}"
        }
        return null
    }
}

/** One recorded startup-section failure (F10: per-section guard). */
data class StartupSectionFailure(
    val section: String,
    val error: String,
    val atMs: Long,
)

/**
 * The process-wide startup record. `onCreate` sections report here
 * instead of killing the process: a failure in one section must not
 * kill the helper. Kept in-memory (a startup record is diagnostic,
 * not user data) and capped so it cannot grow unboundedly.
 */
object HelperStartupRecord {
    private const val MAX_FAILURES = 20
    private val failures = ArrayDeque<StartupSectionFailure>()

    @Synchronized
    fun record(section: String, error: Throwable) {
        failures.addLast(
            StartupSectionFailure(section, error.javaClass.simpleName + ": " + (error.message ?: ""), System.currentTimeMillis()),
        )
        while (failures.size > MAX_FAILURES) failures.removeFirst()
    }

    @Synchronized
    fun record(section: String, error: String) {
        failures.addLast(StartupSectionFailure(section, error, System.currentTimeMillis()))
        while (failures.size > MAX_FAILURES) failures.removeFirst()
    }

    @Synchronized
    fun failures(): List<StartupSectionFailure> = failures.toList()

    @Synchronized
    fun failedSections(): List<String> = failures.map { it.section }.distinct()

    @Synchronized
    fun clear() = failures.clear()
}

/**
 * Runs one `onCreate` section guarded: any throw is recorded in
 * [HelperStartupRecord] and the section reports false — the caller
 * continues with the next section instead of dying. Returns true
 * when the section ran clean.
 */
fun runStartupSection(section: String, block: () -> Unit): Boolean {
    return try {
        block()
        true
    } catch (e: Exception) {
        HelperStartupRecord.record(section, e)
        false
    } catch (t: Throwable) {
        HelperStartupRecord.record(section, t.javaClass.simpleName + ": " + (t.message ?: ""))
        false
    }
}

/** One helper-down event, with the battery state at the time (F10). */
data class HelperDownEvent(
    val cause: HelperCause,
    val batteryIgnoringOptimizations: Boolean?,
    val autoRevokeWhitelisted: Boolean?,
    val atMs: Long,
)

/** The helper-down event log — a dead helper leaves a record. */
object HelperDownRecorder {
    private const val MAX_EVENTS = 20
    private val events = ArrayDeque<HelperDownEvent>()

    @Synchronized
    fun record(cause: HelperCause, batteryIgnoringOptimizations: Boolean?, autoRevokeWhitelisted: Boolean?) {
        events.addLast(HelperDownEvent(cause, batteryIgnoringOptimizations, autoRevokeWhitelisted, System.currentTimeMillis()))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun events(): List<HelperDownEvent> = events.toList()

    @Synchronized
    fun last(): HelperDownEvent? = events.lastOrNull()

    @Synchronized
    fun clear() = events.clear()
}
