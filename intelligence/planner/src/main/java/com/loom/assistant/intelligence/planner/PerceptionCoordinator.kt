package com.loom.assistant.intelligence.planner

import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * AOIE Phase 4 — PerceptionCoordinator (blueprint §§3E, 4).
 *
 * The one owner of ScreenModel enrichment. It wraps the existing
 * accessibility-tree read (ScreenAwareness — reused unchanged, never
 * duplicated) and stamps each snapshot with confidence + source
 * provenance, plus the classified app context. It lives in
 * intelligence:planner (not execution:screenawareness) because the
 * planner already depends on screenawareness — the reverse edge
 * would be a module cycle.
 *
 * It performs NO visual perception: there is no screenshot /
 * MediaProjection / PixelCopy source in this build, and this
 * coordinator must never claim one. When a permitted vision source
 * is added later, it lands here as a second observation source with
 * its own confidence — the coordinator is the seam, not the tree
 * walk.
 *
 * Confidence rule (kept in one place here for snapshots): empty
 * tree → 0.3, UNKNOWN kind → 0.7, otherwise 1.0. A null snapshot →
 * null observation, never an empty screen.
 */
class PerceptionCoordinator {

    /** Enriches a raw snapshot with confidence + provenance. */
    fun observe(model: ScreenModel?): PerceptionObservation? {
        if (model == null) return null
        val confidence = confidenceFor(model)
        val context = AppContextProvider.fromSnapshot(model)
            ?: return null
        return context.observation.copy(confidence = confidence, source = "accessibility")
    }

    /** The enriched snapshot itself (same elements, stamped). */
    fun enrich(model: ScreenModel?): ScreenModel? {
        if (model == null) return null
        val observation = observe(model) ?: return model
        return model.copy(confidence = observation.confidence, source = observation.source)
    }

    /** Full app context for a snapshot (foreground package, kind,
     *  keyboard, focused field, target-app change). */
    fun contextFor(model: ScreenModel?, expectedPackage: String? = null): AppContext? =
        AppContextProvider.fromSnapshot(model, expectedPackage)

    companion object {
        /**
         * The stop threshold: below this confidence the executor
         * must stop and ask instead of guessing. 0.7 means "the
         * tree is readable but the screen kind is unknown" — still
         * usable for a re-observation, never for a blind tap.
         * An empty-tree observation (0.3) must never authorize an
         * action step.
         */
        const val MIN_ACTION_CONFIDENCE = 0.7

        /**
         * MASTER Wave 3 (F9) — the confidence of a raw snapshot,
         * without an [AppContext]: null → 0.0 (no observation),
         * empty tree → 0.3, UNKNOWN kind → 0.7, otherwise 1.0.
         * The live path (TaskPlanExecutor) reads this before every
         * action step; below [MIN_ACTION_CONFIDENCE] it stops
         * instead of guessing.
         */
        fun confidenceFor(model: ScreenModel?): Double {
            if (model == null) return 0.0
            return when {
                model.elements.isEmpty() -> 0.3
                ScreenClassifier.classify(model).kind == ScreenKind.UNKNOWN -> 0.7
                else -> 1.0
            }
        }
    }
}
