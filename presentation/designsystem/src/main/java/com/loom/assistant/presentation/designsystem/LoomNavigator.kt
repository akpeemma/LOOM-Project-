package com.loom.assistant.presentation.designsystem

import android.view.View
import android.view.ViewGroup

/**
 * Minimal stack navigator over [LoomDestination] routes.
 * Screen content is supplied by [screenFactory] (Phases 3–5 own the factories).
 */
class LoomNavigator(
    private val container: ViewGroup,
    private val screenFactory: (LoomDestination) -> View,
) {
    private val backStack = ArrayDeque<LoomDestination>()
    var onDestinationChanged: ((LoomDestination) -> Unit)? = null

    val current: LoomDestination? get() = backStack.lastOrNull()

    fun navigate(destination: LoomDestination) {
        if (current == destination) return
        backStack.addLast(destination)
        render()
    }

    /** Bottom-nav tabs reset the stack instead of piling up. */
    fun navigateTab(destination: LoomDestination) {
        require(destination in LoomDestination.bottomNav) { "not a tab: ${destination.route}" }
        backStack.clear()
        backStack.addLast(destination)
        render()
    }

    fun goBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeLast()
        render()
        return true
    }

    private fun render() {
        val destination = current ?: return
        container.removeAllViews()
        container.addView(screenFactory(destination))
        onDestinationChanged?.invoke(destination)
    }
}
