package com.loom.assistant.presentation.panels

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.execution.devicecontrol.DeviceControlCenter
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.core.common.OverlayPermission
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Device Control — the control surface for the Phase 4 accessibility
 * dispatcher. Real interface + real logic:
 * - reads the actual accessibility-service and overlay states,
 * - the action catalog shows honest per-action availability,
 * - tap execution is device-gated (Phase 6 AuthGate): attempting it
 *   reports the exact missing piece, never a fake success.
 */
class DeviceControlScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
    private val openOverlaySettings: () -> Unit,
) : ScrollView(activity) {

    private val center = DeviceControlCenter(activity)
    private lateinit var body: LinearLayout

    init {
        build()
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh()
            override fun onViewDetachedFromWindow(v: View) {
                removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun build() {
        val root = LoomUi.screenRoot(context)
        val inner = LoomUi.innerOf(root)
        LoomUi.addCenteredPill(inner, "DEVICE CONTROL")
        inner.addView(
            LoomUi.text(context, "Device Control", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 4) },
        )
        inner.addView(
            LoomUi.text(
                context, "LOOM helps you use your phone through its helper. " +
                    "Nothing happens unless you switch it on first.",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ).apply {
                setPadding(0, 0, 0, LoomUi.dp(context, 12))
            },
        )
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(root)
    }

    private fun refresh() {
        body.removeAllViews()

        // ---- status card (real device state) ----
        val a11yOn = center.isAccessibilityEnabled()
        val helperRunning = center.isServiceBound()
        val overlayOk = OverlayPermission.granted(context)
        val statusCard = LoomCardView(context)
        val statusCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        statusCol.addView(LoomUi.text(context, "Status", R.style.LoomText_Headline))
        // Blank-reply fix (2026-11-06): plain-words labels. 'Service
        // bound' was the row David asked about ('what does this service
        // bound represent') — the old label named an internal state and
        // said nothing about what to do. 'Helper running' says what it
        // is, and the hint below says how to fix it.
        statusCol.addView(statusRow("LOOM helper", a11yOn, "On", "Off"))
        statusCol.addView(statusRow("Helper running", helperRunning, "Running", "Not running"))
        statusCol.addView(statusRow("Draw over other apps", overlayOk, "Allowed", "Not allowed"))
        // Device-evidence fix (2026-11-05, item 1) + accessibility fix
        // (2026-11-05, audit §7.2): the pointer's live state — the row
        // the diagnosis asked for, in plain words only. "Not on screen"
        // is the stale-renderer symptom: the service says on, the
        // window is not on screen, and the pointer cannot draw.
        val guidanceState = center.guidanceWindowState()
        statusCol.addView(
            statusRow(
                "Pointer on screen",
                guidanceState == "On screen",
                "On screen",
                guidanceState,
            ),
        )
        // MASTER Wave 4 (F10): battery + auto-revoke state, in plain
        // words. The video shows "Pause app activity if unused" ON
        // with no battery-exemption request ever made and no
        // auto-revoke handling in code — both are now visible rows,
        // not hidden causes. "Unknown" means the state could not be
        // read (never guessed).
        val batteryOk = center.batteryIgnoringOptimizations()
        statusCol.addView(
            statusRow(
                "Battery optimization",
                batteryOk == true,
                "Off for LOOM",
                if (batteryOk == false) "ON — can switch LOOM off" else "Unknown",
            ),
        )
        val revokeOk = center.autoRevokeWhitelisted()
        statusCol.addView(
            statusRow(
                "Pause if unused (auto-revoke)",
                revokeOk == true,
                "Off for LOOM",
                if (revokeOk == false) "ON — can revoke LOOM" else "Unknown",
            ),
        )
        statusCard.addView(statusCol)
        body.addView(statusCard, cardParams())

        // MASTER Wave 4 (F10 strengthening, C-04): per-capability
        // liveness — open / type / tap / scroll each verified by its
        // own probe, not inferred from the one "Helper running"
        // boolean above. A dead capability names its cause and the
        // repair step; a live one says so plainly.
        val livenessCard = LoomCardView(context)
        val liveCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        liveCol.addView(LoomUi.text(context, "Hands — what works right now", R.style.LoomText_Headline))
        val liveness = center.capabilityLiveness()
        for ((id, state) in liveness) {
            when (state) {
                is com.loom.assistant.core.common.CapabilityState.Live ->
                    liveCol.addView(statusRow(id.label.replaceFirstChar { it.uppercase() }, true, "Working", "Working"))
                is com.loom.assistant.core.common.CapabilityState.Dead ->
                    liveCol.addView(
                        statusRow(
                            id.label.replaceFirstChar { it.uppercase() },
                            false,
                            "Working",
                            "Not working — ${state.cause.name.lowercase().replace('_', ' ')}",
                        ),
                    )
            }
            if (state is com.loom.assistant.core.common.CapabilityState.Dead) {
                liveCol.addView(
                    LoomUi.text(
                        context,
                        "${id.label.replaceFirstChar { it.uppercase() }}: ${state.repair.title} — ${state.repair.detail}",
                        R.style.LoomText_Body, R.color.loom_warning,
                    ).apply {
                        setPadding(0, 0, 0, LoomUi.dp(context, 8))
                    },
                )
            }
        }
        val lastDown = center.lastHelperDownSummary()
        if (lastDown != null) {
            liveCol.addView(
                LoomUi.text(context, lastDown, R.style.LoomText_Body, R.color.loom_text_muted),
            )
        }
        livenessCard.addView(liveCol)
        body.addView(livenessCard, cardParams())

        // MASTER Wave 4 (F10): startup-section failures — a section
        // of onCreate that failed is shown here instead of killing
        // the helper silently. Empty in the normal case.
        val startupFailures = com.loom.assistant.core.common.HelperStartupRecord.failures()
        if (startupFailures.isNotEmpty()) {
            body.addView(
                LoomUi.text(
                    context,
                    "Startup problems: " + startupFailures.joinToString("; ") { "${it.section} (${it.error})" },
                    R.style.LoomText_Body, R.color.loom_warning,
                ).apply {
                    setPadding(0, 0, 0, LoomUi.dp(context, 8))
                },
            )
        }

        // Blank-reply fix (2026-11-06, David Nov 6 09:24): the remedy is
        // always tappable. The old screen only showed a settings button
        // when the switch read off — with the switch on but the helper
        // not running (David's exact state) there was no button at all,
        // only words sending him to Settings. Now the hint names the fix
        // and the button opens Settings directly in both down states.
        if (!helperRunning) {
            body.addView(
                LoomUi.text(
                    context,
                    if (!a11yOn) {
                        "LOOM's helper is off — turn it on below."
                    } else {
                        "LOOM's helper is switched on but not running — " +
                            "open Settings and turn LOOM off and on again."
                    },
                    R.style.LoomText_Body, R.color.loom_warning,
                ).apply {
                    setPadding(0, 0, 0, LoomUi.dp(context, 8))
                },
            )
            body.addView(actionButton("Open Accessibility settings") {
                activity.startActivity(center.accessibilitySettingsIntent())
            })
            // MASTER Wave 4 (F10): the repair ladder beyond the
            // toggle — the video proves off/on does not always revive
            // a dead helper. Each button is one ladder step: rebind
            // (LOOM tries to reconnect, no settings trip), battery
            // exemption, and app info (auto-revoke lives there).
            body.addView(actionButton("Try reconnecting the helper") {
                try {
                    com.loom.assistant.platform.services.LoomOverlayService.start(context)
                } catch (_: Exception) {
                }
                refresh()
            })
            if (center.batteryIgnoringOptimizations() == false) {
                body.addView(actionButton("Let LOOM run without battery limits") {
                    activity.startActivity(center.batteryExemptionIntent())
                })
            }
            if (center.autoRevokeWhitelisted() == false) {
                body.addView(actionButton("Stop Android pausing LOOM when unused") {
                    activity.startActivity(center.appInfoIntent())
                })
            }
        }

        // ---- enablement buttons (real system intents) ----
        if (!overlayOk) {
            body.addView(actionButton("Allow drawing over apps") {
                openOverlaySettings()
            })
        }

        // ---- action catalog with honest availability ----
        body.addView(
            LoomUi.text(context, "Capabilities", R.style.LoomText_Headline).apply {
                setPadding(0, LoomUi.dp(context, 8), 0, LoomUi.dp(context, 8))
            },
        )
        for (action in DeviceControlCenter.ACTIONS) {
            val result = center.availability(action)
            val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(LoomUi.text(context, action.title, R.style.LoomText_Headline))
            col.addView(
                LoomUi.text(context, action.description, R.style.LoomText_Caption, R.color.loom_text_muted),
            )
            // Blank-reply fix (2026-11-06): a Gated row explains itself
            // ON the row — why it is gated and what turns it Ready.
            // The reason used to appear only after tapping the card;
            // David's Nov 6 08:01 screenshot showed four Gated rows
            // with no reason in sight.
            when (result) {
                is DeviceControlCenter.ActionResult.Refused -> col.addView(
                    LoomUi.text(
                        context, "Gated — ${result.reason}",
                        R.style.LoomText_Caption, R.color.loom_warning,
                    ),
                )
                is DeviceControlCenter.ActionResult.Unavailable -> col.addView(
                    LoomUi.text(
                        context, "Not built yet — ${result.reason}",
                        R.style.LoomText_Caption, R.color.loom_text_muted,
                    ),
                )
                is DeviceControlCenter.ActionResult.Ready -> {}
            }
            row.addView(col)
            val state = TextView(context).apply {
                when (result) {
                    is DeviceControlCenter.ActionResult.Ready -> {
                        text = "Ready"
                        setTextColor(context.getColor(R.color.loom_success))
                    }
                    is DeviceControlCenter.ActionResult.Refused -> {
                        text = "Gated"
                        setTextColor(context.getColor(R.color.loom_warning))
                    }
                    is DeviceControlCenter.ActionResult.Unavailable -> {
                        text = "Unavailable"
                        setTextColor(context.getColor(R.color.loom_text_muted))
                    }
                }
                setTextAppearance(R.style.LoomText_Caption)
            }
            row.addView(state)
            card.addView(row)
            card.setOnClickListener {
                when (val r = center.availability(action)) {
                    is DeviceControlCenter.ActionResult.Ready ->
                        LoomDialogs.info(
                            activity, action.title,
                            "This works. When LOOM taps something for you, " +
                                "it asks you first.",
                        )
                    is DeviceControlCenter.ActionResult.Refused ->
                        LoomDialogs.info(activity, "${action.title} — gated", r.reason)
                    is DeviceControlCenter.ActionResult.Unavailable ->
                        LoomDialogs.info(activity, "${action.title} — not yet implemented", r.reason)
                }
            }
            body.addView(card, cardParams())
        }

        // ---- screen snapshot (real, when bound) ----
        body.addView(actionButton("See what LOOM sees") {
            navigate(LoomDestination.WhatLoomSees)
        })
        body.addView(actionButton("Snapshot current screen") {
            val model = try {
                ScreenAwarenessHolder.current.snapshot()
            } catch (_: Exception) {
                null
            }
            if (model == null) {
                LoomDialogs.info(
                    activity, "No snapshot",
                    "LOOM's helper isn't running, so there's nothing to read. " +
                        "Turn it on above, then try again.",
                )
            } else {
                LoomDialogs.info(
                    activity, "Screen snapshot",
                    "App: ${model.packageName}\nItems found: ${model.elements.size}\n" +
                        "(Private details are hidden before anything is stored.)",
                )
            }
        })
    }

    private fun statusRow(label: String, ok: Boolean, okText: String, badText: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            LoomUi.text(context, label, R.style.LoomText_Body, R.color.loom_text_muted).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            LoomUi.text(
                context, if (ok) okText else badText,
                R.style.LoomText_Body,
                if (ok) R.color.loom_success else R.color.loom_warning,
            ),
        )
        return row
    }

    private fun actionButton(label: String, onTap: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) }
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = LoomUi.dp(context, 12) }
}
