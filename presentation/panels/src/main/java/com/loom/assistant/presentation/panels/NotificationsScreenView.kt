package com.loom.assistant.presentation.panels

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.interaction.notifications.NotificationCenter
import com.loom.assistant.platform.storage.LoomPreferences
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Notifications — notification groups, the discipline policy, real
 * outbound history, and a test post. Meaningful only: every post needs
 * an allowed reason code and passes the per-group rate limit.
 */
class NotificationsScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
    private val requestPostPermission: ((Boolean) -> Unit) -> Unit,
) : ScrollView(activity) {

    private val center = NotificationCenter.get(activity)
    private val prefs = LoomPreferences.get(activity)
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
        LoomUi.addCenteredPill(inner, "NOTIFICATIONS")
        inner.addView(
            LoomUi.text(context, "Notifications", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 4) },
        )
        inner.addView(
            LoomUi.text(
                context, "Meaningful only — task completions, errors needing attention, " +
                    "authorization requests, significant progress. Never a notification speaker.",
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

        // ---- permission state ----
        val permCard = LoomCardView(context)
        val permRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hasPerm = center.hasPostPermission()
        permRow.addView(
            LoomUi.text(
                context,
                if (hasPerm) "Notification permission granted" else "Notification permission not granted",
                R.style.LoomText_Body,
                if (hasPerm) R.color.loom_success else R.color.loom_warning,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (!hasPerm) {
            val grant = TextView(context).apply {
                text = "Grant"
                setTextAppearance(R.style.LoomText_Body)
                setTextColor(context.getColor(R.color.loom_accent_bright))
                isClickable = true
                isFocusable = true
                setOnClickListener { requestPostPermission { refresh() } }
            }
            permRow.addView(grant)
        }
        permCard.addView(permRow)
        body.addView(permCard, cardParams())

        // ---- groups ----
        body.addView(
            LoomUi.text(context, "Groups", R.style.LoomText_Headline).apply {
                setPadding(0, LoomUi.dp(context, 4), 0, LoomUi.dp(context, 8))
            },
        )
        for (g in NotificationCenter.Group.values()) {
            val card = LoomCardView(context)
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LoomUi.text(context, g.title, R.style.LoomText_Headline))
            col.addView(
                LoomUi.text(
                    context, groupBlurb(g),
                    R.style.LoomText_Caption, R.color.loom_text_muted,
                ),
            )
            card.addView(col)
            body.addView(card, cardParams())
        }

        // ---- digest mode ----
        val digestCard = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val digestRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        digestRow.addView(
            LoomUi.text(context, "Digest mode", R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        digestRow.addView(
            LoomUi.text(
                context, if (prefs.digestMode) "ON" else "OFF",
                R.style.LoomText_Body,
                if (prefs.digestMode) R.color.loom_success else R.color.loom_text_muted,
            ),
        )
        digestCard.addView(digestRow)
        digestCard.setOnClickListener {
            prefs.digestMode = !prefs.digestMode
            refresh()
        }
        body.addView(digestCard, cardParams())
        body.addView(
            LoomUi.text(
                context, "Digest mode batches low-priority updates instead of posting each one.",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ).apply {
                setPadding(0, 0, 0, LoomUi.dp(context, 8))
            },
        )

        // ---- test post ----
        val testBtn = TextView(context).apply {
            text = "Send test notification"
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val delivered = center.post(
                    NotificationCenter.Group.SYSTEM,
                    NotificationCenter.Reason.PROGRESS_MILESTONE,
                    "LOOM test",
                    "Notifications are working.",
                )
                if (!delivered) {
                    LoomDialogs.info(
                        activity, "Not delivered",
                        "The post was refused — either the permission is missing or the " +
                            "group hit its hourly rate limit. The attempt is in the history below.",
                    )
                }
                refresh()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) }
        }
        body.addView(testBtn)

        // ---- history ----
        body.addView(
            LoomUi.text(context, "History", R.style.LoomText_Headline).apply {
                setPadding(0, LoomUi.dp(context, 4), 0, LoomUi.dp(context, 8))
            },
        )
        val history = center.history(20)
        if (history.isEmpty()) {
            body.addView(
                LoomUi.text(
                    context, "No notifications sent yet.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 16), 0, 0) },
            )
        } else {
            val now = System.currentTimeMillis()
            for (h in history) {
                val card = LoomCardView(context)
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(
                    LoomUi.text(context, h.title, R.style.LoomText_Headline).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                titleRow.addView(
                    LoomUi.text(
                        context, if (h.delivered) "sent" else "refused",
                        R.style.LoomText_Caption,
                        if (h.delivered) R.color.loom_success else R.color.loom_warning,
                    ),
                )
                col.addView(titleRow)
                col.addView(
                    LoomUi.text(
                        context, "${h.group.title} • ${h.reason.label} • ${LoomUi.timeAgo(now, h.timestamp)}",
                        R.style.LoomText_Caption, R.color.loom_text_muted,
                    ),
                )
                card.addView(col)
                body.addView(card, cardParams())
            }
            val clearBtn = TextView(context).apply {
                text = "Clear history"
                setTextAppearance(R.style.LoomText_Body)
                setTextColor(context.getColor(R.color.loom_text_muted))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(0, LoomUi.dp(context, 12), 0, 0)
                setOnClickListener {
                    LoomDialogs.confirm(
                        activity, "Clear history?",
                        "The notification history will be removed.",
                        "Clear",
                    ) {
                        center.clearHistory()
                        refresh()
                    }
                }
            }
            body.addView(clearBtn)
        }
    }

    private fun groupBlurb(g: NotificationCenter.Group): String = when (g) {
        NotificationCenter.Group.TASKS -> "Completions, failures, milestones"
        NotificationCenter.Group.AUTHORIZATIONS -> "Approvals LOOM is waiting on"
        NotificationCenter.Group.SYSTEM -> "Health and service updates"
        NotificationCenter.Group.INTELLIGENCE -> "Learning and memory digests"
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = LoomUi.dp(context, 10) }
}
