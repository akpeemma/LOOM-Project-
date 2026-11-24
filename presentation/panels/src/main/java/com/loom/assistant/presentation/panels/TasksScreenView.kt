package com.loom.assistant.presentation.panels

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.execution.tasks.LoomTask
import com.loom.assistant.execution.tasks.TaskStatus
import com.loom.assistant.execution.tasks.TaskStore
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Tasks & Automation — lockdown card 8.
 * The 4 locked tabs, task cards with progress, create/complete flows,
 * scheduling hooks (stored run time; background execution is Phase 6).
 */
class TasksScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val store = TaskStore.get(activity)
    private lateinit var body: LinearLayout
    private lateinit var tabRow: LinearLayout
    private var tab: TaskStatus = TaskStatus.ACTIVE

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
        LoomUi.addCenteredPill(inner, "TASKS")

        val logoRow = LoomUi.logoRow(context, 56)
        logoRow.addView(
            LoomUi.text(context, "LOOM", R.style.LoomText_Title, gravity = Gravity.CENTER),
        )
        logoRow.addView(
            LoomUi.text(
                context, "Tasks & Automation",
                R.style.LoomText_Body, R.color.loom_accent_bright, Gravity.CENTER,
            ),
        )
        inner.addView(logoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 12) })

        inner.addView(
            LoomUi.text(context, "Tasks", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 8) },
        )

        tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        inner.addView(
            tabRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )

        val addBtn = TextView(context).apply {
            text = "+ New task"
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                LoomDialogs.input(
                    activity, "New task",
                    listOf("Title" to "", "Details" to ""),
                    "Create",
                ) { values ->
                    store.create(values[0], values[1])
                    tab = TaskStatus.ACTIVE
                    refresh()
                }
            }
        }
        inner.addView(
            addBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
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
        tabRow.removeAllViews()
        for (s in TaskStatus.values()) {
            val selected = tab == s
            val tv = TextView(context).apply {
                text = "${s.label}\n${store.countByStatus(s)}"
                setTextAppearance(R.style.LoomText_Caption)
                gravity = Gravity.CENTER
                setTextColor(
                    context.getColor(
                        if (selected) R.color.loom_accent_bright else R.color.loom_text_muted,
                    ),
                )
                background = LoomUi.bubbleDrawable(
                    context,
                    if (selected) R.color.loom_accent_dim else R.color.loom_surface_2,
                    14,
                )
                val v = LoomUi.dp(context, 10)
                setPadding(0, v, 0, v)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    tab = s
                    refresh()
                }
            }
            tabRow.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = LoomUi.dp(context, 6)
                },
            )
        }

        body.removeAllViews()
        val items = store.listByStatus(tab)
        if (items.isEmpty()) {
            body.addView(
                LoomUi.text(
                    context, "No ${tab.label.lowercase()} tasks.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 24), 0, 0) },
            )
            return
        }
        for (task in items) {
            body.addView(taskCard(task), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) })
        }
    }

    private fun taskCard(task: LoomTask): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LoomUi.text(context, task.title, R.style.LoomText_Headline))
        if (task.detail.isNotBlank()) {
            col.addView(
                LoomUi.text(
                    context, task.detail.take(120),
                    R.style.LoomText_Body, R.color.loom_text_secondary,
                ),
            )
        }
        // Progress bar.
        val barBg = LinearLayout(context).apply {
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_3, 8)
            orientation = LinearLayout.HORIZONTAL
        }
        val fill = View(context).apply {
            background = LoomUi.bubbleDrawable(context, R.color.loom_accent, 8)
        }
        val empty = View(context)
        barBg.addView(fill, LinearLayout.LayoutParams(0, LoomUi.dp(context, 8), task.progress.toFloat()))
        barBg.addView(empty, LinearLayout.LayoutParams(0, LoomUi.dp(context, 8), (100 - task.progress).toFloat()))
        col.addView(barBg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = LoomUi.dp(context, 8)
            bottomMargin = LoomUi.dp(context, 4)
        })
        val meta = buildString {
            append("${task.progress}%")
            if (task.status == TaskStatus.SCHEDULED && task.scheduledAt > 0) {
                append(" • runs ${LoomUi.timeAgo(task.scheduledAt, System.currentTimeMillis()).replace(" ago", "")} from now")
            } else {
                append(" • ${LoomUi.timeAgo(System.currentTimeMillis(), task.updatedAt)}")
            }
            if (task.attempts > 0) append(" • ${task.attempts} ${if (task.attempts == 1) "attempt" else "attempts"}")
        }
        col.addView(
            LoomUi.text(context, meta, R.style.LoomText_Caption, R.color.loom_text_muted),
        )
        card.addView(col)
        card.setOnClickListener { showActions(task) }
        return card
    }

    private fun showActions(task: LoomTask) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when (task.status) {
            TaskStatus.ACTIVE -> {
                actions.add("Set progress" to {
                    LoomDialogs.input(
                        activity, "Progress for \"${task.title}\"",
                        listOf("Percent (0-100)" to task.progress.toString()),
                        "Set",
                    ) { values ->
                        val p = values[0].toIntOrNull()
                        if (p == null) {
                            LoomDialogs.info(activity, "Invalid", "Enter a number 0–100.")
                        } else {
                            store.setProgress(task.id, p)
                            refresh()
                        }
                    }
                })
                actions.add("Mark completed" to {
                    store.complete(task.id)
                    refresh()
                })
                actions.add("Schedule for later" to {
                    LoomDialogs.input(
                        activity, "Schedule task",
                        listOf("Minutes from now" to "60"),
                        "Schedule",
                    ) { values ->
                        val mins = values[0].toLongOrNull()
                        if (mins == null || mins <= 0) {
                            LoomDialogs.info(activity, "Invalid", "Enter minutes in the future.")
                        } else {
                            runCatching {
                                store.schedule(task.id, System.currentTimeMillis() + mins * 60_000L)
                            }.onFailure {
                                LoomDialogs.info(activity, "Cannot schedule", it.message ?: "Refused.")
                            }
                            refresh()
                        }
                    }
                })
                actions.add("Mark failed" to {
                    store.fail(task.id)
                    refresh()
                })
            }
            TaskStatus.SCHEDULED -> {
                actions.add("Unschedule (back to Active)" to {
                    runCatching { store.unschedule(task.id) }
                        .onFailure { LoomDialogs.info(activity, "Cannot unschedule", it.message ?: "Refused.") }
                    tab = TaskStatus.ACTIVE
                    refresh()
                })
            }
            TaskStatus.FAILED -> {
                actions.add("Retry" to {
                    runCatching { store.retry(task.id) }
                        .onFailure { LoomDialogs.info(activity, "Cannot retry", it.message ?: "Refused.") }
                    refresh()
                })
            }
            TaskStatus.COMPLETED -> {
                // nothing except edit/delete below
            }
        }
        actions.add("Edit" to {
            LoomDialogs.input(
                activity, "Edit task",
                listOf("Title" to task.title, "Details" to task.detail),
                "Save",
            ) { values ->
                store.rename(task.id, values[0], values[1])
                refresh()
            }
        })
        actions.add("Delete" to {
            LoomDialogs.confirm(
                activity, "Delete task?",
                "\"${task.title}\" will be removed permanently.",
                "Delete",
            ) {
                store.delete(task.id)
                refresh()
            }
        })
        LoomDialogs.options(activity, task.title, actions)
    }
}
