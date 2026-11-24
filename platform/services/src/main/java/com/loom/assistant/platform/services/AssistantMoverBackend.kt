package com.loom.assistant.platform.services

import android.content.Context
import android.view.View
import android.view.WindowManager
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.interaction.pointer.FloatingPositionStore

/**
 * Master implementation §7 — the production [AssistantMover.Backend].
 *
 * Bridges the mover's pure logic to the live overlay: WindowManager for
 * the safe area + view updates, the overlay view's layout params (kept in
 * `view.tag`, exactly as LoomOverlayService's binders do) for the current
 * rect, ScreenAwarenessHolder for the tree, FloatingPositionStore for
 * persistence. Display-only: applies layout-param changes, never a tap
 * target, never an AuthGate call.
 *
 * HOST WIRING (LoomOverlayService owner — this file must NOT be edited
 * into the service; the service adds these lines):
 *
 *   // onCreate, after `wm` is initialised:
 *   AssistantMover.register(
 *       AssistantMoverBackend(
 *           context = this,
 *           windowManager = wm,
 *           viewProvider = { characterView ?: orbView },
 *           paramsProvider = {
 *               (characterView ?: orbView)?.tag as? WindowManager.LayoutParams
 *           },
 *           overlayIdProvider = {
 *               when {
 *                   characterView != null ->
 *                       FloatingPositionStore.OverlayId.CHARACTER
 *                   orbView != null -> FloatingPositionStore.OverlayId.ORB
 *                   else -> null
 *               }
 *           },
 *       ),
 *   )
 *   // onDestroy:
 *   AssistantMover.register(null)
 *
 * The view/params/id providers are lambdas (not captured values) because
 * the attached view changes on mode switch — the backend always reads
 * the CURRENT view. A detached view (null provider) makes the mover
 * fail honestly instead of moving a stale window.
 */
class AssistantMoverBackend(
    private val context: Context,
    private val windowManager: WindowManager,
    private val viewProvider: () -> View?,
    private val paramsProvider: () -> WindowManager.LayoutParams?,
    private val overlayIdProvider: () -> FloatingPositionStore.OverlayId?,
) : AssistantMover.Backend {

    override fun currentRect(): AssistantMover.AssistantRect? {
        viewProvider() ?: return null
        val params = paramsProvider() ?: return null
        return AssistantMover.AssistantRect(
            params.x, params.y,
            params.x + params.width, params.y + params.height,
        )
    }

    override fun safeArea(): AssistantMover.AssistantRect {
        val r = FloatingPositionStore.safeArea(windowManager)
        return AssistantMover.AssistantRect(r.left, r.top, r.right, r.bottom)
    }

    override fun density(): Float =
        context.resources.displayMetrics.density

    override fun screenModel(): ScreenModel? =
        ScreenAwarenessHolder.current.snapshot()

    override fun applyMove(x: Int, y: Int): Boolean {
        val view = viewProvider() ?: return false
        val params = paramsProvider() ?: return false
        params.x = x
        params.y = y
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun persistPosition(x: Int, y: Int) {
        val id = overlayIdProvider() ?: return
        FloatingPositionStore.save(context, id, x, y)
    }
}
