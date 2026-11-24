package com.loom.assistant.intelligence.planner

import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * AOIE Phase 4 — AppContextProvider (blueprint §3C) + the
 * confidence-scored perception observation (blueprint §§3E, 4).
 *
 * Before this, the fragments existed separately (ScreenAwareness
 * for the tree, AppCatalogue for resolution, ConversationContext
 * for the last app, ScreenClassifier for the kind) but no single
 * component could answer "which app is foreground, what screen is
 * this, is the keyboard up, what field has focus, did the target
 * app change". This provider composes those fragments — it does not
 * duplicate ScreenAwareness or the catalogue — and it is also the
 * seam that feeds grounding to the model (PlanJsonSource).
 *
 * Perception output carries confidence + source provenance
 * (blueprint §4). There is still no vision source: source is always
 * "accessibility" until a permitted screenshot/vision path exists
 * with David's explicit approval (AT-09 stays NOT TESTED without
 * it). Confidence is deliberately coarse and honest:
 *  - 1.0: non-empty tree, known package, classification made.
 *  - 0.7: non-empty tree but the screen kind is UNKNOWN.
 *  - 0.3: empty tree (the app is foreground but its tree is
 *    unreadable — the executor's stable-foreground rule may still
 *    proceed, but nothing here claims the screen is understood).
 *  - 0.0: no snapshot at all (Insufficient).
 * Confidence is never invented from a guess: an UNKNOWN kind caps
 * it at 0.7 even on a full tree.
 */
data class PerceptionObservation(
    val packageName: String,
    /** Activity / window identity when the tree reports it — null
     *  today: the accessibility tree as read here carries the
     *  package only, and this field stays null rather than guessed. */
    val activityName: String? = null,
    val screenKind: ScreenKind,
    val keyboardVisible: Boolean,
    /** Label of the focused field, if any (hint/text, redacted by
     *  the snapshot layer for password fields). */
    val focusedFieldLabel: String?,
    val affordanceKeys: Set<String>,
    /** App version when known — null today (no version source is
     *  wired; the field exists so recipes can record it once one
     *  is). */
    val appVersion: String? = null,
    val confidence: Double,
    /** Provenance: "accessibility" today; "accessibility+vision"
     *  only once a vision source exists. */
    val source: String = "accessibility",
    val capturedAt: Long = System.currentTimeMillis(),
)

/** One app-context answer: foreground package, screen, keyboard,
 *  focused field, and whether the target app changed. */
data class AppContext(
    val observation: PerceptionObservation,
    /** True when the foreground package differs from [expectedPackage]. */
    val targetAppChanged: Boolean,
    /** The package LOOM expected to be foreground, or null. */
    val expectedPackage: String?,
)

object AppContextProvider {

    /** Builds the context from a fresh snapshot. Null snapshot =
     *  no context (the caller must fail honestly, never an empty
     *  screen). */
    fun fromSnapshot(
        model: ScreenModel?,
        expectedPackage: String? = null,
    ): AppContext? {
        if (model == null) return null
        val signature = ScreenClassifier.classify(model)
        val focused = model.elements.firstOrNull { it.focused }
        val observation = PerceptionObservation(
            packageName = model.packageName,
            screenKind = signature.kind,
            keyboardVisible = model.keyboardVisible,
            focusedFieldLabel = focused?.let {
                it.hintText?.takeIf { h -> h.isNotBlank() }
                    ?: it.text?.takeIf { t -> t.isNotBlank() }
                    ?: it.contentDescription?.takeIf { d -> d.isNotBlank() }
            },
            affordanceKeys = signature.affordanceKeys,
            confidence = confidenceFor(model, signature.kind),
            source = "accessibility",
            capturedAt = model.capturedAt,
        )
        return AppContext(
            observation = observation,
            targetAppChanged = expectedPackage != null &&
                model.packageName != expectedPackage,
            expectedPackage = expectedPackage,
        )
    }

    /** The grounding line the model gets at plan time: package,
     *  screen kind, affordances, focused field, keyboard state —
     *  redacted (no element text beyond the focused field's hint,
     *  no passwords — the snapshot layer already redacts those). */
    fun groundingText(context: AppContext, resolvedAppPackage: String?): String {
        val o = context.observation
        return buildString {
            append("Current screen: app=${o.packageName}")
            if (!resolvedAppPackage.isNullOrBlank() && resolvedAppPackage != o.packageName) {
                append(" (task app=$resolvedAppPackage, not foreground yet)")
            }
            append(", screen=${o.screenKind}")
            append(", keyboard=${if (o.keyboardVisible) "visible" else "hidden"}")
            append(", affordances=${o.affordanceKeys.sorted().joinToString(",").ifBlank { "none" }}")
            o.focusedFieldLabel?.let { append(", focused field=\"$it\"") }
            append(", confidence=${o.confidence}")
        }
    }

    private fun confidenceFor(model: ScreenModel, kind: ScreenKind): Double {
        if (model.elements.isEmpty()) return 0.3
        return if (kind == ScreenKind.UNKNOWN) 0.7 else 1.0
    }

    /** DETAIL production (audit §G): a screen is a detail screen
     *  when it carries one long article-like text element and no
     *  list / navigation chrome — e.g. an opened video description
     *  or article. Conservative on purpose: a feed with a long
     *  caption still has a LIST, so it never classifies DETAIL. */
    fun isDetailScreen(model: ScreenModel): Boolean {
        if (model.elements.any { it.role == ElementRole.LIST }) return false
        if (model.elements.any { it.role == ElementRole.NAVIGATION }) return false
        return model.elements.any { el ->
            el.role == ElementRole.TEXT &&
                (el.text?.length ?: 0) >= 120
        }
    }
}
