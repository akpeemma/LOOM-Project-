package com.loom.assistant.execution.screenawareness

/**
 * NAV-SCREEN fix (2026-11-11, David's order ~19:29 WAT) — N4, the
 * navigation verifier (blueprint §8.2: "navigation verifier checks
 * active package/screen").
 *
 * The audit's Cause C: TAP_TARGET verified as generic
 * "screen changed", which PASSES for the wrong tab (Watch instead
 * of Reels — any content change counts) and FAILS for a correct tab
 * switch that changes only selection state (selected flag / tint,
 * no text/bounds change). This verifier is destination-aware:
 *
 * PASS only when, on a fresh observation,
 *   (i) the package is unchanged (navigation is not a launch), AND
 *   (ii) the destination tab is selected (N3 state), OR destination
 *        content is present in the non-navigation elements.
 * A selection-only change IS a pass. Any screen change alone is
 * NOT a pass. Tapping the wrong tab FAILS with "wrong destination"
 * naming the tab that IS selected — the wrong-tab negative is a
 * first-class verdict, not a silent pass.
 *
 * Destination content: the destination's words (canonical + synonyms)
 * appearing in a non-navigation element's label — e.g. a "Reels"
 * header or feed affordance on the landed screen. It is the fallback
 * when the tree reports no selection state at all (all tabs
 * unselected in the tree); it never overrides a selected WRONG tab.
 */
sealed class NavigationVerification {
    /** The destination was reached: [reason] names the evidence. */
    data class Passed(val reason: String) : NavigationVerification()

    /** The destination was not reached: [reason] names what was seen instead. */
    data class Failed(val reason: String) : NavigationVerification()
}

object NavigationVerificationChecker {

    /**
     * Verifies a navigation to [destination] from [before] to
     * [after]. [expectedPackage] is the package the step targeted
     * (blank = use [before]'s package). [after] null = no confirming
     * observation.
     */
    fun verifyNavigation(
        before: ScreenModel?,
        after: ScreenModel?,
        destination: String,
        expectedPackage: String = "",
    ): NavigationVerification {
        if (after == null) {
            return NavigationVerification.Failed(
                "no confirming observation after navigation — the screen went unreadable",
            )
        }
        val wantPkg = expectedPackage.ifBlank { before?.packageName.orEmpty() }
        if (wantPkg.isNotBlank() && after.packageName != wantPkg) {
            return NavigationVerification.Failed(
                "wrong app after navigation: expected $wantPkg but ${after.packageName} is in the foreground",
            )
        }
        if (before != null && before.packageName.isNotBlank() &&
            after.packageName != before.packageName
        ) {
            return NavigationVerification.Failed(
                "navigation left the app: ${before.packageName} → ${after.packageName}",
            )
        }
        // A different tab selected is a WRONG DESTINATION — it beats
        // any content evidence, so a wrong-tab tap can never pass.
        val selected = NavigationResolver.selectedTab(after)
        if (selected != null) {
            val resolved = NavigationResolver.resolveNavigationTarget(after, destination)
            if (resolved.any { it.element.id == selected.element.id }) {
                return NavigationVerification.Passed(
                    "the $destination tab is selected in ${after.packageName}",
                )
            }
            return NavigationVerification.Failed(
                "wrong destination: the ${selected.label} tab is selected, not $destination",
            )
        }
        // No selection state in the tree: destination content is the
        // evidence — a destination word in a non-navigation element.
        if (destinationContentPresent(after, destination)) {
            return NavigationVerification.Passed(
                "$destination content is showing in ${after.packageName}",
            )
        }
        return NavigationVerification.Failed(
            "no confirming observation after navigation — neither the $destination tab " +
                "selected nor $destination content on screen in ${after.packageName}",
        )
    }

    /** True when a destination word appears in a non-navigation element's label. */
    fun destinationContentPresent(screen: ScreenModel, destination: String): Boolean {
        val dest = NavigationResolver.canonicalDestination(destination)
            ?: destination.trim().lowercase()
        val words = listOf(dest) + (NavigationResolver.DESTINATION_SYNONYMS[dest].orEmpty())
        return screen.elements.any { el ->
            el.role != ElementRole.NAVIGATION &&
                words.any { w ->
                    el.label().lowercase().contains(w.lowercase())
                }
        }
    }

    /** True when the screen shows a navigation step is even applicable (a nav container exists). */
    fun hasNavigation(screen: ScreenModel): Boolean =
        NavigationResolver.navigationContainers(screen).isNotEmpty()
}
