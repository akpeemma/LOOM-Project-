package com.loom.assistant.presentation.dashboard

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.execution.tasks.TaskStatus
import com.loom.assistant.execution.tasks.TaskStore
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.intelligence.memory.MemoryStore
import com.loom.assistant.intelligence.skilllab.SkillCatalog
import com.loom.assistant.intelligence.skilllab.SkillStatus
import com.loom.assistant.platform.storage.ActivityLog
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.ModelPickerDialog
import com.loom.assistant.presentation.designsystem.R

/**
 * LOOM Intelligence dashboard — lockdown card 13.
 *
 * Rule (arch §2.16): no fabricated statistics — EVERY number queries a
 * real store. Empty stores render as empty states, never invented figures.
 */
class DashboardScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val modelSelection = ModelSelection(activity)
    private val memoryStore = MemoryStore.get(activity)
    private val skillCatalog = SkillCatalog.get(activity)
    private val taskStore = TaskStore.get(activity)
    private val conversationStore = ConversationStore.get(activity)
    private val activityLog = ActivityLog.get(activity)

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
        LoomUi.addCenteredPill(inner, "LOOM INTELLIGENCE")

        // Logo + title + subtitle (lockdown card 13 header).
        val logoRow = LoomUi.logoRow(context, 64)
        logoRow.addView(
            LoomUi.text(context, "LOOM INTELLIGENCE", R.style.LoomText_Title, gravity = Gravity.CENTER),
        )
        logoRow.addView(
            LoomUi.text(
                context, "Dashboard • Live Overview",
                R.style.LoomText_Body, R.color.loom_accent_bright, Gravity.CENTER,
            ),
        )
        inner.addView(logoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 16) })

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
        addCardRow(
            modelCard(),
            memoryCard(),
        )
        addCardRow(
            skillsCard(),
            tasksCard(),
        )
        addCardRow(
            healthCard(),
            activityCard(),
        )
        body.addView(sectionTitle("Recent Activity"))
        val entries = activityLog.list(8)
        if (entries.isEmpty()) {
            val card = LoomCardView(context)
            card.addView(
                LoomUi.text(
                    context,
                    "No activity yet. Use LOOM and real events will appear here.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ),
            )
            body.addView(card, cardParams())
        } else {
            val now = System.currentTimeMillis()
            val card = LoomCardView(context)
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (e in entries) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dot = TextView(context).apply {
                    text = "• "
                    setTextColor(context.getColor(R.color.loom_accent_bright))
                    textSize = 16f
                }
                row.addView(dot)
                val textCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(LoomUi.text(context, e.text, R.style.LoomText_Body))
                textCol.addView(
                    LoomUi.text(
                        context, LoomUi.timeAgo(now, e.timestamp),
                        R.style.LoomText_Caption, R.color.loom_text_muted,
                    ),
                )
                row.addView(textCol)
                col.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 10) })
            }
            card.addView(col)
            body.addView(card, cardParams())
        }

        // Manual refresh (no androidx SwipeRefreshLayout on this classpath).
        val refreshBtn = TextView(context).apply {
            text = "↻ Refresh"
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, LoomUi.dp(context, 16), 0, 0)
            setOnClickListener { refresh() }
        }
        body.addView(refreshBtn)
    }

    // ---------- the 6 cards (all numbers from real stores) ----------

    private fun modelCard(): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "AI Model", R.style.LoomText_Headline))
        col.addView(
            LoomUi.text(
                context, modelSelection.label,
                R.style.LoomText_Title, R.color.loom_text_primary,
            ),
        )
        val status = if (modelSelection.hasKeyConfigured) "Key configured" else "No key configured"
        col.addView(
            LoomUi.text(
                context, status,
                R.style.LoomText_Caption,
                if (modelSelection.hasKeyConfigured) R.color.loom_success else R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        card.setOnClickListener {
            ModelPickerDialog.show(activity, modelSelection) { refresh() }
        }
        return card
    }

    private fun memoryCard(): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "Memory", R.style.LoomText_Headline))
        val count = memoryStore.count()
        col.addView(
            LoomUi.text(
                context, "$count ${if (count == 1) "entry" else "entries"}",
                R.style.LoomText_Title, R.color.loom_text_primary,
            ),
        )
        col.addView(
            LoomUi.text(
                context, "Knowledge: ${formatBytes(memoryStore.storageBytes())}",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        card.setOnClickListener { navigate(LoomDestination.Memory) }
        return card
    }

    private fun skillsCard(): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "Skills", R.style.LoomText_Headline))
        val installed = skillCatalog.countByStatus(SkillStatus.INSTALLED)
        val developing = skillCatalog.countByStatus(SkillStatus.IN_DEVELOPMENT) +
            skillCatalog.countByStatus(SkillStatus.TESTING)
        col.addView(
            LoomUi.text(
                context, "$installed Installed",
                R.style.LoomText_Title, R.color.loom_text_primary,
            ),
        )
        col.addView(
            LoomUi.text(
                context, "$developing Developing",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        card.setOnClickListener { navigate(LoomDestination.Skills) }
        return card
    }

    private fun tasksCard(): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "Tasks", R.style.LoomText_Headline))
        val active = taskStore.countByStatus(TaskStatus.ACTIVE) +
            taskStore.countByStatus(TaskStatus.SCHEDULED)
        val completed = taskStore.countByStatus(TaskStatus.COMPLETED)
        col.addView(
            LoomUi.text(
                context, "$active Active • $completed Completed",
                R.style.LoomText_Title, R.color.loom_text_primary,
            ),
        )
        col.addView(
            LoomUi.text(
                context, "Today: +${completedToday()} tasks done",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        card.setOnClickListener { navigate(LoomDestination.Tasks) }
        return card
    }

    private fun healthCard(): LoomCardView {
        val card = LoomCardView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "System Health", R.style.LoomText_Headline))
        col.addView(healthRow("Storage", if (storageOk()) "OK" else "Degraded"))
        col.addView(healthRow("Memory", "${memoryStore.count()} entries"))
        col.addView(healthRow("Chats", "${conversationStore.list().size} threads"))
        card.addView(col)
        return card
    }

    private fun activityCard(): LoomCardView {
        val card = LoomCardView(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, "Activity", R.style.LoomText_Headline))
        val total = activityLog.totalCount()
        col.addView(
            LoomUi.text(
                context, "$total ${if (total == 1) "event" else "events"}",
                R.style.LoomText_Title, R.color.loom_text_primary,
            ),
        )
        col.addView(
            LoomUi.text(
                context, "${activityLog.todayCount()} today",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        return card
    }

    // ---------- helpers ----------

    private fun addCardRow(left: LoomCardView, right: LoomCardView) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = LoomUi.dp(context, 6)
        }
        val rp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LoomUi.dp(context, 6)
        }
        row.addView(left, lp)
        row.addView(right, rp)
        body.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = LoomUi.dp(context, 12) }

    private fun sectionTitle(text: String): TextView =
        LoomUi.text(context, text, R.style.LoomText_Headline).apply {
            setPadding(0, LoomUi.dp(context, 8), 0, LoomUi.dp(context, 8))
        }

    private fun healthRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            LoomUi.text(context, label, R.style.LoomText_Body, R.color.loom_text_muted).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(LoomUi.text(context, value, R.style.LoomText_Body, R.color.loom_success))
        return row
    }

    private fun storageOk(): Boolean {
        return try {
            val probe = java.io.File(context.filesDir, ".loom_health_probe")
            probe.writeText("ok")
            val ok = probe.exists()
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun completedToday(): Int {
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return taskStore.listByStatus(TaskStatus.COMPLETED).count { it.updatedAt >= startOfDay }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }
}
