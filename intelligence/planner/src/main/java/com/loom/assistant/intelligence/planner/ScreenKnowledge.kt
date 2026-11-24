package com.loom.assistant.intelligence.planner

import com.loom.assistant.core.common.SubmitMechanism
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.TargetAliases

/**
 * KNOWLEDGE BASE (2026-11-09, David's correction) — not session
 * learning. LOOM keeps a durable base of how screens and apps work
 * — what each screen is, where its controls are, how its search
 * works, how to go back, how to clear a search — built from what it
 * observes, consulted BEFORE it acts, and surviving restarts. It
 * must not re-learn the same screen every time it restarts.
 *
 * What this is NOT: the AppProfile seam (per-package submit orders
 * + mechanism wins) cannot carry it — it has no per-screen shape,
 * no control locations, no back/clear behaviour, and nothing reads
 * it before planning except the submit order. The profile store
 * stays as it is (package-level learning); this store is the
 * screen-level knowledge base beside it, in the same module, under
 * the same rules (pure data + a persistence seam, AuthGate and the
 * covered plan path untouched — knowledge informs decisions, it
 * never dispatches anything).
 *
 * Honesty rules, enforced by construction:
 *  - Coordinates are NEVER taken from knowledge. Stored bounds are
 *    last-seen information for the record and for deciding WHICH
 *    recovery to attempt; every tap/pointer move resolves its
 *    target from the LIVE screen. A stale bound must never become
 *    a tap point.
 *  - Absence is never recorded as a claim. A missing Back button
 *    is not proof of gesture navigation (the windows read may
 *    have failed), so [BackBehaviour] only ever records BUTTON
 *    when a button was positively measured; otherwise it stays
 *    UNKNOWN. The live pointer drive decides button-vs-edge from
 *    the live snapshot, not from this store.
 *  - A failed persistence backend never loses the session's
 *    learning and never crashes the run: loads and saves are
 *    guarded, and the in-memory map always holds the truth for
 *    this process (same fail-safe as the profile store).
 */

/** How the back control works on a screen — only ever positively measured. */
enum class BackBehaviour {
    UNKNOWN,
    /** A system Back button was measured in the nav-bar window. */
    BUTTON,
    /** Reserved: gesture nav cannot be proven from a missing measurement, so it is never recorded. */
    GESTURE_EDGE,
}

/** One control as last observed: what it is, where it was, how it is operated. */
data class KnownControl(
    val label: String,
    val role: String,
    val bounds: TargetBounds,
    /** "tap" | "type" | "scroll" — how LOOM operates this control. */
    val howOperated: String,
)

/**
 * NAV-SCREEN fix (2026-11-11, N10) — one navigation tab as last
 * observed: its label (as the tree gave it, or its class when
 * icon-only), its 0-based position in the navigation bar, and
 * whether it was the selected tab. Bounds are last-seen information
 * for the record only — NEVER a tap point (every tap resolves from
 * the live screen). A labelless tab is recorded under its class
 * name, exactly as the tree knows it.
 */
data class KnownNavTab(
    val label: String,
    val position: Int,
    val role: String,
    val bounds: TargetBounds,
    val selected: Boolean,
)

/**
 * Everything LOOM knows about one screen, keyed by
 * "$packageName|$structureHash" (the structure hash identifies the
 * layout; the kind names what it is). Built from observations, never
 * invented: every field is either measured from a live tree or
 * absent (null / UNKNOWN / empty).
 */
data class ScreenKnowledge(
    val packageName: String,
    val screenKind: ScreenKind,
    val structureHash: Int,
    val affordances: Set<String> = emptySet(),
    /** Hint text of the search entry on this screen, when seen. */
    val searchEntryHint: String? = null,
    /** The submit mechanism that last verifiably ran a search here. */
    val winningSubmitMechanism: SubmitMechanism? = null,
    /** The X / clear control on this screen, when seen. */
    val clearControl: KnownControl? = null,
    val backBehaviour: BackBehaviour = BackBehaviour.UNKNOWN,
    /** Last-seen Back button bounds (information only — never a tap point). */
    val backButtonBounds: TargetBounds? = null,
    /** Notable controls observed, most recent first, bounded. */
    val controls: List<KnownControl> = emptyList(),
    /**
     * NAV-SCREEN fix (2026-11-11, N10): the navigation tabs of
     * this screen, in bar order, as last observed. Consulted before
     * planning NAVIGATE_TAB steps (preferred tab reading first).
     * Empty = no navigation container seen on this screen.
     */
    val navTabs: List<KnownNavTab> = emptyList(),
    val observations: Int = 0,
    val lastSeenAt: Long = 0L,
    /**
     * A GO_BACK recovery already failed on this screen (the screen
     * did not change / was still wrong afterwards). The next replan
     * for this screen skips the back recovery instead of repeating
     * a proven-futile step. Cleared by a verified back recovery.
     */
    val backRecoveryFailed: Boolean = false,
)

/** Persistence seam for the knowledge base. Default is in-memory (no disk). */
interface ScreenKnowledgePersistence {
    fun load(): Map<String, ScreenKnowledge>
    fun save(knowledge: Map<String, ScreenKnowledge>)
}

/** In-memory persistence: knowledge lives for the process only. */
class InMemoryScreenKnowledgePersistence : ScreenKnowledgePersistence {
    private var stored: Map<String, ScreenKnowledge> = emptyMap()
    override fun load(): Map<String, ScreenKnowledge> = stored
    override fun save(knowledge: Map<String, ScreenKnowledge>) {
        stored = knowledge.toMap()
    }
}

/**
 * The knowledge base: one [ScreenKnowledge] per screen, read before
 * planning and written on observation. The store loads persisted
 * knowledge over the (empty) map at construction, so a new store
 * instance over the same backing file sees everything the previous
 * process learned — that is the restart proof.
 */
class ScreenKnowledgeStore(
    private val persistence: ScreenKnowledgePersistence = InMemoryScreenKnowledgePersistence(),
) {
    private val entries: MutableMap<String, ScreenKnowledge> = LinkedHashMap()

    init {
        try {
            entries.putAll(persistence.load())
        } catch (_: Exception) {
            // Disk unreadable: start empty, keep learning in memory.
        }
    }

    companion object {
        fun keyFor(packageName: String, structureHash: Int): String = "$packageName|$structureHash"

        /** Cap on stored controls per screen — the record stays small. */
        const val MAX_CONTROLS = 12
    }

    fun knowledgeFor(packageName: String, structureHash: Int): ScreenKnowledge? =
        entries[keyFor(packageName, structureHash)]

    /** The knowledge for the screen [model] shows, or null when unknown. */
    fun knowledgeForScreen(model: ScreenModel): ScreenKnowledge? {
        val signature = ScreenClassifier.classify(model)
        return knowledgeFor(signature.packageName, signature.structureHash)
    }

    fun allEntries(): Map<String, ScreenKnowledge> = entries.toMap()

    /**
     * Records one observation of [model] — what the screen is, what
     * it affords, where its clear control and Back button are, how
     * its controls are operated. Merges with what is already known
     * (a win, a back-recovery flag and a back-button measurement are
     * never clobbered by a thinner observation). Saves only when
     * something changed. Never throws: a recording failure must
     * never fail the run it observes.
     */
    fun recordObservation(model: ScreenModel): ScreenKnowledge {
        return try {
            recordObservationInner(model)
        } catch (_: Exception) {
            knowledgeForScreen(model) ?: ScreenKnowledge(
                packageName = model.packageName,
                screenKind = ScreenKind.UNKNOWN,
                structureHash = ScreenClassifier.structureHash(model),
            )
        }
    }

    private fun recordObservationInner(model: ScreenModel): ScreenKnowledge {
        val signature = ScreenClassifier.classify(model)
        val key = keyFor(signature.packageName, signature.structureHash)
        val existing = entries[key]
        val clear = model.elements
            .firstOrNull { TargetAliases.matchesTargetQuery(it, "clear") }
            ?.let { toKnownControl(it) }
        val entryHint = model.elements
            .firstOrNull { it.role == ElementRole.SEARCH_FIELD }
            ?.hintText?.takeIf { it.isNotBlank() }
            ?: model.elements.firstOrNull {
                it.role == ElementRole.INPUT &&
                    (it.hintText?.contains("search", ignoreCase = true) == true)
            }?.hintText?.takeIf { it.isNotBlank() }
        val controls = model.elements
            .filter { it.enabled && !it.bounds.isEmpty && it.label().isNotBlank() }
            .filter {
                it.role in setOf(
                    ElementRole.BUTTON, ElementRole.ICON, ElementRole.MENU_ITEM,
                    ElementRole.INPUT, ElementRole.SEARCH_FIELD,
                    ElementRole.NAVIGATION, ElementRole.LIST,
                )
            }
            .take(MAX_CONTROLS)
            .map { toKnownControl(it) }
        // Back behaviour: only a positively measured button is ever
        // recorded. A missing measurement proves nothing (the windows
        // read may have failed), so it never flips a known BUTTON
        // back to an assumption — see the file header.
        val backBehaviour = if (model.backButton != null) BackBehaviour.BUTTON
        else existing?.backBehaviour ?: BackBehaviour.UNKNOWN
        val backBounds = model.backButton?.let {
            TargetBounds(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
        } ?: existing?.backButtonBounds
        // NAV-SCREEN fix (2026-11-11, N10): record the navigation
        // tabs (label / position / selected) in bar order. A fresh
        // observation replaces the tab list — tabs are layout, and a
        // thinner observation must not clobber a fuller one, so an
        // empty tab list keeps the existing record.
        val navTabs = try {
            com.loom.assistant.execution.screenawareness.NavigationResolver
                .enumerateTabs(model).map { tab ->
                    KnownNavTab(
                        label = tab.label,
                        position = tab.index,
                        role = tab.element.role.name,
                        bounds = TargetBounds(
                            tab.element.bounds.left, tab.element.bounds.top,
                            tab.element.bounds.right, tab.element.bounds.bottom,
                        ),
                        selected = tab.element.selected,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        val updated = ScreenKnowledge(
            packageName = signature.packageName,
            screenKind = signature.kind,
            structureHash = signature.structureHash,
            affordances = signature.affordanceKeys,
            searchEntryHint = entryHint ?: existing?.searchEntryHint,
            winningSubmitMechanism = existing?.winningSubmitMechanism,
            clearControl = clear ?: existing?.clearControl,
            backBehaviour = backBehaviour,
            backButtonBounds = backBounds,
            controls = controls.ifEmpty { existing?.controls ?: emptyList() },
            navTabs = navTabs.ifEmpty { existing?.navTabs ?: emptyList() },
            observations = (existing?.observations ?: 0) + 1,
            lastSeenAt = System.currentTimeMillis(),
            backRecoveryFailed = existing?.backRecoveryFailed ?: false,
        )
        if (updated != existing) {
            entries[key] = updated
            saveQuietly()
        }
        return updated
    }

    /**
     * Records a verified submit win on [model]'s screen: the
     * mechanism that ran the search here. A failure records nothing
     * (one failure does not disprove the winner — the profile
     * learner owns win/loss accounting at package level).
     */
    fun recordSubmitWin(model: ScreenModel, mechanism: SubmitMechanism, succeeded: Boolean) {
        if (!succeeded) return
        try {
            val signature = ScreenClassifier.classify(model)
            val key = keyFor(signature.packageName, signature.structureHash)
            val existing = entries[key] ?: return
            if (existing.winningSubmitMechanism == mechanism) return
            entries[key] = existing.copy(winningSubmitMechanism = mechanism)
            saveQuietly()
        } catch (_: Exception) {
        }
    }

    /**
     * Records the outcome of a GO_BACK recovery on [model]'s
     * screen. A failed recovery is remembered so the next replan
     * for this screen does not repeat it; a verified one clears
     * the flag.
     */
    fun recordBackRecovery(model: ScreenModel, succeeded: Boolean) {
        try {
            val signature = ScreenClassifier.classify(model)
            val key = keyFor(signature.packageName, signature.structureHash)
            val existing = entries[key] ?: return
            if (existing.backRecoveryFailed == !succeeded) return
            entries[key] = existing.copy(backRecoveryFailed = !succeeded)
            saveQuietly()
        } catch (_: Exception) {
        }
    }

    /** Whether a GO_BACK recovery is worth attempting on [model]'s screen. */
    fun shouldAttemptBackRecovery(model: ScreenModel): Boolean =
        knowledgeForScreen(model)?.backRecoveryFailed != true

    /** The clear control known for [model]'s screen, or null. */
    fun clearControlFor(model: ScreenModel): KnownControl? =
        knowledgeForScreen(model)?.clearControl

    /**
     * NAV-SCREEN fix (2026-11-11, N10) — CONSULTATION (read before
     * planning): the navigation tabs known for [model]'s screen, in
     * bar order, or empty when unknown. Callers use the POSITION
     * (which tab) and the LABEL (what it is called) — never the
     * stored bounds as tap coordinates.
     */
    fun navTabsFor(model: ScreenModel): List<KnownNavTab> =
        knowledgeForScreen(model)?.navTabs ?: emptyList()

    /**
     * The known tab for [destination] on [model]'s screen, or null:
     * the tab whose recorded label matches the destination (via
     * TargetAliases), else the tab at the destination's known
     * position in a known per-app layout. Null = unknown — the live
     * resolver decides, never a guessed tab.
     */
    fun preferredNavTabFor(model: ScreenModel, destination: String): KnownNavTab? {
        val tabs = navTabsFor(model)
        if (tabs.isEmpty()) return null
        val labelMatch = tabs.firstOrNull { tab ->
            com.loom.assistant.execution.screenawareness.TargetAliases.matchesTargetQuery(
                com.loom.assistant.execution.screenawareness.ScreenElement(
                    id = "known:${tab.position}",
                    text = tab.label,
                    contentDescription = null,
                    className = tab.role,
                    bounds = android.graphics.Rect(
                        tab.bounds.left, tab.bounds.top, tab.bounds.right, tab.bounds.bottom,
                    ),
                    role = com.loom.assistant.execution.screenawareness.ElementRole.OTHER,
                    enabled = true,
                    isPassword = false,
                    selected = tab.selected,
                ),
                destination,
            )
        }
        if (labelMatch != null) return labelMatch
        val dest = com.loom.assistant.execution.screenawareness.NavigationResolver
            .canonicalDestination(destination) ?: return null
        val layout = com.loom.assistant.execution.screenawareness.NavigationResolver
            .POSITIONAL_LAYOUTS[model.packageName] ?: return null
        val index = layout[dest] ?: return null
        return tabs.firstOrNull { it.position == index }
    }

    /**
     * CONSULTATION (read before planning): the submit order for
     * [model]'s screen — the mechanism that last verifiably worked
     * HERE goes first, then the rest of [fallback] (the package
     * profile order) in order. Unknown screen → [fallback]
     * unchanged, so behaviour without knowledge is identical.
     */
    fun preferredSubmitOrderForScreen(
        model: ScreenModel,
        fallback: List<SubmitMechanism>,
    ): List<SubmitMechanism> {
        val winner = knowledgeForScreen(model)?.winningSubmitMechanism ?: return fallback
        if (winner !in fallback) return listOf(winner) + fallback
        return listOf(winner) + fallback.filter { it != winner }
    }

    private fun toKnownControl(el: com.loom.assistant.execution.screenawareness.ScreenElement): KnownControl {
        val how = when (el.role) {
            ElementRole.INPUT, ElementRole.SEARCH_FIELD -> "type"
            ElementRole.LIST -> "scroll"
            else -> "tap"
        }
        return KnownControl(
            label = el.label(),
            role = el.role.name,
            bounds = TargetBounds(el.bounds.left, el.bounds.top, el.bounds.right, el.bounds.bottom),
            howOperated = how,
        )
    }

    private fun saveQuietly() {
        try {
            persistence.save(entries)
        } catch (_: Exception) {
            // Disk unwritable: the in-memory map still holds the
            // session's learning; the next save retries.
        }
    }
}
