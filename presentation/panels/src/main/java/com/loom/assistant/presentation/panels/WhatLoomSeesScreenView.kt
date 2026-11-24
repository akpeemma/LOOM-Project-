package com.loom.assistant.presentation.panels

import android.app.Activity
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.execution.devicecontrol.DeviceControlCenter
import com.loom.assistant.execution.screenawareness.ScreenAwarenessHolder
import com.loom.assistant.execution.screenawareness.ScreenModelText
import com.loom.assistant.execution.screenawareness.ScreenRead
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Master implementation §3 — "What LOOM Sees".
 *
 * A read-only render of the redacted [ScreenModel] LOOM reads from the
 * accessibility tree: app name, then one row per element (label + role +
 * bounds). This is exactly what LOOM understands about the current
 * screen — nothing more, nothing less. It answers "did it see the
 * search bar?" and makes guidance failures diagnosable (empty or wrong
 * tree → the pointer cannot work).
 *
 * Privacy, by construction:
 *  - the snapshot comes from [ScreenAwarenessHolder.current], whose
 *    [ScreenDataPolicy.redact] already stripped password text and, in
 *    sensitive apps, all readable labels — this screen can only ever
 *    display the redacted model;
 *  - no pixels anywhere: no MediaProjection, no PixelCopy, no
 *    screenshots. The tree is a structure, not a picture.
 *
 * Honest limits: when the tree is unavailable or empty, the screen
 * says so with the exact reason ([ScreenRead.Insufficient]) instead of
 * showing a stale or invented snapshot.
 */
class WhatLoomSeesScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val center = DeviceControlCenter(activity)
    private lateinit var body: LinearLayout

    /** Cap rendered rows so a dense screen stays scrollable; the rest is counted, not hidden silently. */
    companion object {
        const val MAX_ROWS = 200
    }

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
        LoomUi.addCenteredPill(inner, "WHAT LOOM SEES")
        inner.addView(
            LoomUi.text(context, "What LOOM Sees", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 4) },
        )
        inner.addView(
            LoomUi.text(
                context, "Exactly what LOOM reads from your screen — the accessibility " +
                    "tree, redacted for privacy. No pixels, no screenshots: LOOM " +
                    "sees structure (labels, roles, positions), not a picture.",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ).apply {
                setPadding(0, 0, 0, LoomUi.dp(context, 12))
            },
        )
        inner.addView(actionButton("Refresh snapshot") { refresh() })
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
        when (val read = try {
            ScreenAwarenessHolder.current.read()
        } catch (_: Exception) {
            ScreenRead.Insufficient("Could not read the screen — the snapshot failed.")
        }) {
            is ScreenRead.Insufficient -> {
                val card = LoomCardView(context)
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(LoomUi.text(context, "Nothing to show yet", R.style.LoomText_Headline))
                col.addView(
                    LoomUi.text(context, read.reason, R.style.LoomText_Body, R.color.loom_text_muted),
                )
                card.addView(col)
                body.addView(card, cardParams())
                if (!center.isAccessibilityEnabled()) {
                    body.addView(actionButton("Enable accessibility service") {
                        activity.startActivity(center.accessibilitySettingsIntent())
                    })
                }
            }
            is ScreenRead.Available -> {
                val model = read.model

                // ---- app card ----
                val appCard = LoomCardView(context)
                val appCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                appCol.addView(LoomUi.text(context, "App", R.style.LoomText_Headline))
                appCol.addView(
                    LoomUi.text(context, model.packageName, R.style.LoomText_Body),
                )
                appCol.addView(
                    LoomUi.text(
                        context,
                        "${model.elements.size} elements · read at " +
                            DateFormat.format("HH:mm:ss", model.capturedAt),
                        R.style.LoomText_Caption, R.color.loom_text_muted,
                    ),
                )
                ScreenModelText.sensitiveNotice(model.packageName)?.let { notice ->
                    appCol.addView(
                        LoomUi.text(context, notice, R.style.LoomText_Caption, R.color.loom_warning)
                            .apply { setPadding(0, LoomUi.dp(context, 6), 0, 0) },
                    )
                }
                appCard.addView(appCol)
                body.addView(appCard, cardParams())

                // ---- element rows (label + role + bounds, one card each) ----
                val shown = model.elements.take(MAX_ROWS)
                for (el in shown) {
                    val card = LoomCardView(context)
                    val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(
                        LoomUi.text(context, ScreenModelText.describeElement(el), R.style.LoomText_Body),
                    )
                    col.addView(
                        LoomUi.text(
                            context, el.className,
                            R.style.LoomText_Caption, R.color.loom_text_muted,
                        ),
                    )
                    card.addView(col)
                    body.addView(card, cardParams())
                }
                if (model.elements.size > MAX_ROWS) {
                    body.addView(
                        LoomUi.text(
                            context,
                            "+ ${model.elements.size - MAX_ROWS} more elements (showing first $MAX_ROWS)",
                            R.style.LoomText_Caption, R.color.loom_text_muted,
                        ).apply { gravity = Gravity.CENTER },
                    )
                }
                if (model.elements.isEmpty()) {
                    body.addView(
                        LoomUi.text(
                            context, "The tree was read but contains no visible elements.",
                            R.style.LoomText_Body, R.color.loom_text_muted,
                        ),
                    )
                }
            }
        }
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
