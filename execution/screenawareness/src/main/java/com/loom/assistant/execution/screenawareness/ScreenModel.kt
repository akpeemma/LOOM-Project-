package com.loom.assistant.execution.screenawareness

import android.graphics.Rect

/**
 * Phase 4 — screen awareness data model (arch §2.19).
 * Pure data; no Android service coupling so it stays unit-testable.
 *
 * Master implementation §3 — tree-derived enrichment: roles and fields
 * below expose only what the accessibility tree actually provides.
 * Anything the tree does not report is absent (null / false), never
 * invented — callers must treat a missing value as "unknown", not fill
 * it in. See [ScreenRead.Insufficient] for the honest-unavailable path.
 */
enum class ElementRole {
    BUTTON, TOGGLE, INPUT, MENU_ITEM, TEXT, IMAGE, OTHER,
    /** Icon-only button (ImageButton): role + class, no text of its own. */
    ICON,
    /** Search field: SearchView, or an EditText whose hint asks to search. */
    SEARCH_FIELD,
    /** Dialog / popup window (AlertDialog, Dialog, PopupWindow, ...). */
    DIALOG,
    /** In-app navigation chrome (BottomNavigation, NavigationView, TabLayout). */
    NAVIGATION,
    /** Scrollable list container (ListView, RecyclerView, GridView). */
    LIST,
}

/**
 * POINTER EVERYWHERE (2026-11-09): one control measured in a
 * non-app window (navigation bar, input method) — the real bounds,
 * label and class name of a node the service actually walked, never
 * invented. Carried across the layer boundary so the executor can
 * drive the pointer to a control the app-window tree cannot see.
 * Lives here (with the model) so runners compiling ScreenModel
 * alone can use it.
 */
data class MeasuredControl(
    val bounds: Rect,
    val label: String,
    val className: String,
)

data class ScreenElement(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val bounds: Rect,
    val role: ElementRole,
    val enabled: Boolean,
    val isPassword: Boolean,
    /** Placeholder / hint text from the tree (node.hintText), if any. */
    val hintText: String? = null,
    /** Whether the tree reports this node as scrollable. */
    val scrollable: Boolean = false,
    /**
     * App-aware execution (2026-11-07, David's directive §1): whether
     * the tree reports this node as the focused node (node.isFocused).
     * The focused field is the one the keyboard is typing into — the
     * submit/verify path must read THIS field's text, never the first
     * field on screen. False when the tree does not report focus.
     */
    val focused: Boolean = false,
    /**
     * NAV-SCREEN fix (2026-11-11, N3): whether the tree reports
     * this node as selected (node.isSelected). The active tab in a
     * bottom nav is told apart from its siblings by this state —
     * without it a selection-only tab switch is invisible, and the
     * navigation verifier cannot tell the right tab from the wrong
     * one. False when the tree does not report selection.
     */
    val selected: Boolean = false,
) {
    /**
     * Human-readable label used for target queries and tooltips.
     * Falls back through text → content-description → hint → class name.
     * Never invents a label: when the tree provides nothing, the caller
     * sees the class name, which is all LOOM actually knows.
     */
    fun label(): String = text?.takeIf { it.isNotBlank() }
        ?: contentDescription?.takeIf { it.isNotBlank() }
        ?: hintText?.takeIf { it.isNotBlank() }
        ?: className.substringAfterLast('.')
}

data class ScreenModel(
    val packageName: String,
    val elements: List<ScreenElement>,
    val capturedAt: Long = System.currentTimeMillis(),
    /**
     * App-aware execution (2026-11-07, David's directive §1): whether
     * an input-method window (the on-screen keyboard) was present when
     * this snapshot was taken. Lets the executor tell "text typed but
     * keyboard still open, search not submitted" apart from "search
     * executed, keyboard gone" — the exact failure in David's
     * screenshot. False when unknown / not present; never inferred
     * from the element list.
     */
    val keyboardVisible: Boolean = false,
    /**
     * POINTER EVERYWHERE (2026-11-09, David's direction): the pointer
     * must USE the screen to navigate, not just watch it. The three
     * fields below carry measurements the app-window tree cannot
     * supply — they are read from the service's OTHER windows at
     * snapshot time and are null when that window is absent or the
     * control cannot be found. Null means "unknown / not present",
     * never a guessed position; callers narrate instead of drawing
     * a fake target.
     *  - [screenBounds]: the active window's root bounds — the real
     *    screen size as the tree sees it. Swipe start points and the
     *    gesture-nav back edge are computed from these, never from
     *    invented coordinates.
     *  - [backButton]: the system Back button in the navigation-bar
     *    window (3-button navigation). Null on gesture navigation
     *    (no Back node exists) or when the bar cannot be read.
     *  - [keyboardActionKey]: the keyboard's action key (Search / Go
     *    / Done / Send / Next / Enter) in the input-method window.
     *    Null when the keyboard is hidden or the key is not exposed
     *    as a node (some keyboards draw keys without nodes).
     */
    val screenBounds: Rect? = null,
    val backButton: MeasuredControl? = null,
    val keyboardActionKey: MeasuredControl? = null,
    /**
     * AOIE Phase 4 — confidence that this snapshot describes the
     * screen (0.0–1.0), set by PerceptionCoordinator. 1.0 = a
     * non-empty tree from the accessibility service for a known
     * package; lower when the tree is thin or the kind is UNKNOWN.
     * 0.0 = no usable observation. Callers must stop, not guess,
     * when confidence is below their threshold.
     */
    val confidence: Double = 1.0,
    /**
     * AOIE Phase 4 — provenance of this observation. "accessibility"
     * today (the tree is the only source). "accessibility+vision"
     * only once a permitted screenshot/vision source exists.
     * Never claims a source that was not used.
     *
     * NAV-SCREEN fix (2026-11-11, N5): [visual] carries the
     * screenshot frame that accompanies this observation, when one
     * was captured under the ScreenshotCaptureGate (consent-gated,
     * never silent). Null = tree only, source stays "accessibility".
     * When a frame is present the source is "accessibility+vision".
     * Frames are held in memory only, downscaled at capture, never
     * written to disk or attached to logs.
     */
    val source: String = "accessibility",
    val visual: VisualObservation? = null,
    /**
     * Mark 57 transfer (2026-11-11, M1) — walk-budget outcome.
     * The tree walk is bounded three ways (900 elements / 1 s
     * clock / depth 24 — see [AccessibilityScreenAwareness]).
     * [degraded] is true when a bound was hit ([degradedReason]
     * names it) or the tree came back THIN (fewer than 3
     * interactable controls on a screen ≥ 400×300 — a window that
     * draws its own controls, not an empty screen). A degraded
     * model is a NAMED failure to escalate (e.g. to the screenshot
     * channel), never proof the button does not exist. False +
     * null on a full, in-budget walk.
     */
    val degraded: Boolean = false,
    val degradedReason: String? = null,
) {
    /**
     * Visible-screen model (2026-11-11, David's design — binding):
     * the subset of [elements] the USER can actually see right now —
     * bounds intersect the viewport ([screenBounds], the root bounds
     * = display size) and non-empty. [elements] stays the FULL tree
     * for LOOM's own work (search, guidance, navigation): LOOM's
     * reach is never limited to what the user sees, and an off-screen
     * target is found and brought up, not dropped. Callers that
     * describe or show the screen TO the user read this, never
     * [elements] — that is what the "135 things" description got
     * wrong. isVisibleToUser is already applied at capture (walk),
     * so every element here passed it; a model built by hand filters
     * on bounds only. A null / empty viewport means "unknown screen
     * size" — the visible subset is the whole tree rather than
     * nothing, so a bad root never blanks the description.
     *
     * Manual intersection (no Rect.intersects()) so it works on the
     * JVM-test fake Rect too. Touching edges do not count — a node
     * must overlap the viewport by at least one pixel.
     */
    val visibleElements: List<ScreenElement>
        get() {
            val vp = screenBounds?.takeIf { !it.isEmpty } ?: return elements
            return elements.filter { el ->
                !el.bounds.isEmpty &&
                    el.bounds.left < vp.right && el.bounds.right > vp.left &&
                    el.bounds.top < vp.bottom && el.bounds.bottom > vp.top
            }
        }

    /** True when [el] is in the visible subset (see [visibleElements]). */
    fun isVisibleElement(el: ScreenElement): Boolean =
        visibleElements.any { it.id == el.id }
}

/**
 * Result of a screen read — the honest-availability wrapper around
 * a [ScreenModel]. Master implementation §3: when the tree cannot
 * supply what a task needs, the API reports [Insufficient] with the
 * exact reason instead of returning an invented or empty model.
 *  - [Available]: a redacted model was read from the live tree.
 *  - [Insufficient]: no model — the service is not bound, the tree is
 *    unavailable, or the tree was read and contained nothing visible.
 *
 * Moved here from ScreenAwareness.kt (2026-11-11): this class is pure
 * Kotlin with no Android dependency, and [ScreenDescriber] (which
 * every pointer runner compiles) needs it without the tree-reading
 * half of ScreenAwareness, whose Rect use the pointer-tree Rect fake
 * cannot satisfy. Same package, same meaning — only the file moved.
 */
sealed class ScreenRead {
    data class Available(val model: ScreenModel) : ScreenRead()
    data class Insufficient(val reason: String) : ScreenRead()
}
