package com.loom.assistant.execution.screenawareness

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Minimal facade the accessibility service implements. Defined here so this
 * module never imports platform:services (dependency direction stays clean).
 */
interface A11yNodeProvider {
    fun safeRootInActiveWindow(): AccessibilityNodeInfo?

    /**
     * App-aware execution (2026-11-07, David's directive §1): whether
     * an input-method window (the on-screen keyboard) is currently
     * present. Production: the service checks its windows for a
     * TYPE_INPUT_METHOD window. Default false so fakes and hosts that
     * cannot see windows keep compiling — callers must treat false as
     * "not known to be visible", never as proof the keyboard is gone.
     */
    fun isInputMethodVisible(): Boolean = false

    /**
     * POINTER EVERYWHERE (2026-11-09): the system Back button as
     * measured in the navigation-bar window, or null when there is
     * no Back node (gesture navigation) or the bar cannot be read.
     * Default null so fakes and hosts that cannot see windows keep
     * compiling — callers must treat null as "no Back control
     * established", never as proof of gesture navigation.
     */
    fun navBackButton(): MeasuredControl? = null

    /**
     * POINTER EVERYWHERE (2026-11-09): the keyboard's action key
     * (Search / Go / Done / Send / Next / Enter) as measured in the
     * input-method window, or null when the keyboard is hidden or
     * the key is not exposed as a node. Default null for the same
     * reason as [navBackButton].
     */
    fun keyboardActionKey(): MeasuredControl? = null
}

/**
 * Result of a screen read — see [ScreenRead] in ScreenModel.kt.
 * (Moved there 2026-11-11: pure Kotlin, no Android dependency.)
 */

/**
 * Phase 4 — screen awareness interface (arch §2.19).
 * Real interface + real tree-walking logic. Device verification (actual
 * AccessibilityNodeInfo trees on the Redmi 14C) is pending — on this VM
 * every call returns null and says so.
 */
interface ScreenAwareness {
    /** Latest redacted screen model, or null when unavailable. */
    fun snapshot(): ScreenModel?

    /**
     * Honest read: [ScreenRead.Available] with the redacted model, or
     * [ScreenRead.Insufficient] with the exact reason no model exists.
     * Default derives from [snapshot]; implementations that can tell
     * "not bound" apart from "tree unavailable" override it.
     */
    fun read(): ScreenRead {
        val model = snapshot()
        return if (model != null) ScreenRead.Available(model)
        else ScreenRead.Insufficient(
            "Could not read the screen — the accessibility tree is unavailable.",
        )
    }

    /**
     * Find the first visible, enabled element whose text, content-description,
     * hint or class name contains [query] (case-insensitive). Null when the
     * accessibility service is not bound or nothing matches.
     *
     * NAV-SCREEN fix (2026-11-11, N7): resolves through
     * [TargetAliases.resolveElements] — the X / perspective / voice-mic
     * rules apply on this interface too, not only on the plan/guidance
     * paths. The raw-substring bypass is gone.
     *
     * Screen fix (2026-11-11, CORRECTED): this keeps the FULL tree —
     * LOOM's own work must reach off-screen targets and bring them up
     * (scroll / navigate), never be limited to what the user sees.
     * Callers that speak to the user about what is ON the screen use
     * [findVisibleElement] / [isElementVisible] instead, so an
     * off-screen thing is never presented as if the user can see it.
     */
    fun findElement(query: String): ScreenElement? {
        val model = snapshot() ?: return null
        return TargetAliases.resolveElements(model.elements, query).firstOrNull()
    }

    /**
     * Visible-screen lookup (2026-11-11): same match as [findElement]
     * but only over [ScreenModel.visibleElements] — what the user can
     * actually see right now. Null when nothing VISIBLE matches, even
     * when the full tree holds the target below the fold: the caller
     * then says so ("that's not on your screen yet — I'll bring it
     * up") and scrolls / navigates, instead of claiming it is there.
     *
     * NAV-SCREEN fix (2026-11-11, N7): same TargetAliases alignment
     * as [findElement].
     */
    fun findVisibleElement(query: String): ScreenElement? {
        val model = snapshot() ?: return null
        return TargetAliases.resolveElements(model.visibleElements, query).firstOrNull()
    }

    /**
     * Visible-screen check (2026-11-11): true when [element] is in
     * the current snapshot's visible subset — the user can see it
     * right now. False for an off-screen element, an element from a
     * stale snapshot, or no snapshot at all. Guidance and narration
     * use this before claiming anything is on the user's screen.
     */
    fun isElementVisible(element: ScreenElement): Boolean =
        snapshot()?.isVisibleElement(element) == true
}

/** Fallback used when the accessibility service is not bound. */
object NoopScreenAwareness : ScreenAwareness {
    override fun snapshot(): ScreenModel? = null

    override fun read(): ScreenRead = ScreenRead.Insufficient(
        // Binding survival (2026-11-06): never send David to Settings
        // with nothing to tap — the message he reported has no button
        // where he stands. Route to Home → Show pointer, the one
        // surface that carries the settings link and the exact steps.
        "Screen reading is not enabled — open Home and tap Show " +
            "pointer, and LOOM will walk you through turning it back on.",
    )

    override fun findElement(query: String): ScreenElement? = null
}

/**
 * Holder the [LoomAccessibilityService] registers into on connect.
 * Consumers never touch the service directly.
 */
object ScreenAwarenessHolder {
    @Volatile
    var current: ScreenAwareness = NoopScreenAwareness
        private set

    /**
     * Binding-survival diagnostics (2026-11-06): wall-clock time of
     * the last bind / unbind in this process (0 = never). Read from
     * logcat alongside LoomAccessibilityService.lastUnbindWhy to tell
     * a system unbind apart from an in-app clear. Never user-facing.
     */
    @Volatile
    var lastBindAtMs: Long = 0L
        private set

    @Volatile
    var lastUnbindAtMs: Long = 0L
        private set

    fun bind(provider: A11yNodeProvider) {
        current = AccessibilityScreenAwareness(provider)
        lastBindAtMs = System.currentTimeMillis()
    }

    fun unbind() {
        current = NoopScreenAwareness
        lastUnbindAtMs = System.currentTimeMillis()
    }

    val isBound: Boolean get() = current !== NoopScreenAwareness
}

class AccessibilityScreenAwareness(
    private val provider: A11yNodeProvider,
) : ScreenAwareness {

    companion object {
        /**
         * Mark 57 transfer (2026-11-11, M1) — the tree walk is
         * bounded three ways, like screenview.look(): a wall clock
         * (1 s — the same discipline as the <1 s detection budget),
         * an element count (900), and a tree depth (24). When a
         * bound is hit the walk stops and the model is flagged
         * degraded ([ScreenModel.degraded]) with the reason named —
         * a bounded partial tree is never presented as a complete
         * one. Breadth is not enforced here (the walk is
         * depth-first, as before); the clock and the count bound
         * the total work regardless of shape.
         */
        const val MAX_WALK_ELEMENTS: Int = 900
        const val WALK_BUDGET_MS: Long = 1000L
        const val MAX_WALK_DEPTH: Int = 24

        /** Roles that count as interactable for the thin-tree check. */
        private val INTERACTABLE_ROLES: Set<ElementRole> = setOf(
            ElementRole.BUTTON,
            ElementRole.ICON,
            ElementRole.INPUT,
            ElementRole.SEARCH_FIELD,
            ElementRole.MENU_ITEM,
            ElementRole.TOGGLE,
        )

        /** A window this big with almost nothing interactable in it. */
        const val THIN_TREE_MIN_INTERACTABLE: Int = 3
        const val THIN_TREE_MIN_WIDTH: Int = 400
        const val THIN_TREE_MIN_HEIGHT: Int = 300
    }

    /** Mutable walk state: which bound (if any) stopped the walk. */
    private class WalkBudget {
        var exceeded: Boolean = false
        var reason: String? = null

        /** A subtree was pruned by the depth cap — the walk itself
         *  continued with the siblings (depth is a per-branch
         *  bound, not a global stop). */
        var depthPruned: Boolean = false
        var depthReason: String? = null
    }

    override fun read(): ScreenRead {
        val model = snapshot()
        return when {
            model == null -> ScreenRead.Insufficient(
                "Could not read the screen — the accessibility tree is unavailable.",
            )
            model.elements.isEmpty() -> ScreenRead.Insufficient(
                "The screen tree is empty — nothing visible to read on the current screen.",
            )
            else -> ScreenRead.Available(model)
        }
    }

    override fun snapshot(): ScreenModel? {
        val root = provider.safeRootInActiveWindow() ?: return null
        return try {
            val pkg = root.packageName?.toString() ?: return null
            val elements = ArrayList<ScreenElement>()
            val budget = WalkBudget()
            val walkStartMs = System.currentTimeMillis()
            walk(root, elements, 0, budget, walkStartMs)
            // POINTER EVERYWHERE (2026-11-09): the root bounds are
            // the real screen size for gesture geometry; the Back
            // button and keyboard key live in other windows, so they
            // come from the provider (null when absent — never
            // guessed). Each read is guarded: a throwing provider
            // must never take the whole snapshot down.
            val rootBounds = Rect().also { root.getBoundsInScreen(it) }
            // Mark 57 transfer (M1) — thin tree = named failure:
            // a big window with almost no interactable controls
            // draws its own controls; the tree cannot speak for it.
            val interactable = elements.count { it.role in INTERACTABLE_ROLES }
            val thinTree = !rootBounds.isEmpty &&
                rootBounds.width() >= THIN_TREE_MIN_WIDTH &&
                rootBounds.height() >= THIN_TREE_MIN_HEIGHT &&
                interactable < THIN_TREE_MIN_INTERACTABLE
            val degraded = budget.exceeded || budget.depthPruned || thinTree
            val degradedReason = budget.reason ?: budget.depthReason ?: if (thinTree) {
                "thin tree: $interactable interactable controls on a " +
                    "${rootBounds.width()}x${rootBounds.height()} screen — " +
                    "the window draws its own controls; escalate to the screenshot channel"
            } else {
                null
            }
            ScreenDataPolicy.redact(
                ScreenModel(
                    pkg,
                    elements,
                    keyboardVisible = try {
                        provider.isInputMethodVisible()
                    } catch (_: Exception) {
                        false
                    },
                    screenBounds = if (rootBounds.isEmpty) null else Rect(
                        rootBounds.left, rootBounds.top,
                        rootBounds.right, rootBounds.bottom,
                    ),
                    backButton = try {
                        provider.navBackButton()
                    } catch (_: Exception) {
                        null
                    },
                    keyboardActionKey = try {
                        provider.keyboardActionKey()
                    } catch (_: Exception) {
                        null
                    },
                    degraded = degraded,
                    degradedReason = degradedReason,
                ),
            )
        } finally {
            root.recycle()
        }
    }

    override fun findElement(query: String): ScreenElement? {
        // NAV-SCREEN fix (2026-11-11, N7): resolve through
        // TargetAliases — the X / perspective / voice-mic rules apply
        // here too, not only on the plan/guidance paths.
        val model = snapshot() ?: return null
        return TargetAliases.resolveElements(model.elements, query).firstOrNull()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElement>,
        depth: Int,
        budget: WalkBudget,
        startMs: Long,
    ) {
        // Mark 57 transfer (M1): the walk is bounded three ways.
        // A hit stops the walk HERE (no more nodes, no more
        // recursion) and names the bound — the caller flags the
        // model degraded instead of presenting a partial tree as
        // complete. The clock is read once per node; on a huge
        // tree that is the cost of the bound itself.
        if (budget.exceeded) return
        if (depth > MAX_WALK_DEPTH) {
            // Depth is a PER-BRANCH bound: prune this subtree
            // only, flag it, and let the walk continue with the
            // siblings — a deep Litho tree must not blank the
            // shallow controls beside it. (Mark 57: when the
            // budget runs out, what you have is the shallow, big,
            // obvious controls.)
            budget.depthPruned = true
            budget.depthReason =
                "tree walk pruned a subtree past the depth cap ($MAX_WALK_DEPTH) — deep nodes unread"
            return
        }
        if (out.size >= MAX_WALK_ELEMENTS) {
            budget.exceeded = true
            budget.reason = "tree walk hit the element cap ($MAX_WALK_ELEMENTS) — tree larger than LOOM reads in one pass"
            return
        }
        if (System.currentTimeMillis() - startMs >= WALK_BUDGET_MS) {
            budget.exceeded = true
            budget.reason = "tree walk hit the 1 s clock budget — tree slower than LOOM reads in one pass"
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Screen fix (2026-11-11, CORRECTED): the walk keeps the FULL
        // tree — off-screen nodes included. LOOM's own work (search,
        // guidance, navigation) needs the broader reach: a target
        // below the fold is found and brought up, not dropped. The
        // user's point of view lives in ScreenModel.visibleElements,
        // derived from this full snapshot — never by shrinking the
        // snapshot itself. Only isVisibleToUser + non-empty bounds
        // filter here, as before.
        if (!bounds.isEmpty && node.isVisibleToUser) {
            val className = node.className?.toString() ?: "unknown"
            val hint = node.hintText?.toString()
            out.add(
                ScreenElement(
                    id = "${node.className}@${bounds.flattenToString()}",
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = className,
                    bounds = bounds,
                    role = inferRole(className, hint),
                    enabled = node.isEnabled,
                    isPassword = node.isPassword,
                    hintText = hint,
                    scrollable = node.isScrollable,
                    focused = node.isFocused,
                    // NAV-SCREEN fix (2026-11-11, N3): read the
                    // node's selected state — the active tab.
                    selected = node.isSelected,
                ),
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, depth + 1, budget, startMs)
            child.recycle()
            if (budget.exceeded) return
        }
    }

    /**
     * Role inference from tree data ONLY (class name + hint text).
     * Order matters: the most specific match wins (a SearchView is a
     * search field, not a generic input; an ImageButton is an icon,
     * not a text button). Anything unrecognised stays OTHER — never
     * guessed from position, size, or neighbouring elements.
     */
    private fun inferRole(className: String, hintText: String?): ElementRole = when {
        className.contains("SearchView", ignoreCase = true) -> ElementRole.SEARCH_FIELD
        className.contains("EditText", ignoreCase = true) &&
            hintText?.contains("search", ignoreCase = true) == true -> ElementRole.SEARCH_FIELD
        className.contains("Dialog", ignoreCase = true) ||
            className.contains("PopupWindow", ignoreCase = true) -> ElementRole.DIALOG
        className.contains("BottomNavigation", ignoreCase = true) ||
            className.contains("NavigationView", ignoreCase = true) ||
            className.contains("TabLayout", ignoreCase = true) -> ElementRole.NAVIGATION
        className.contains("ListView", ignoreCase = true) ||
            className.contains("RecyclerView", ignoreCase = true) ||
            className.contains("GridView", ignoreCase = true) -> ElementRole.LIST
        className.contains("ImageButton", ignoreCase = true) -> ElementRole.ICON
        className.contains("Button", ignoreCase = true) -> ElementRole.BUTTON
        className.contains("Switch", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) -> ElementRole.TOGGLE
        className.contains("EditText", ignoreCase = true) -> ElementRole.INPUT
        className.contains("MenuItem", ignoreCase = true) -> ElementRole.MENU_ITEM
        className.contains("TextView", ignoreCase = true) -> ElementRole.TEXT
        className.contains("Image", ignoreCase = true) -> ElementRole.IMAGE
        else -> ElementRole.OTHER
    }
}
