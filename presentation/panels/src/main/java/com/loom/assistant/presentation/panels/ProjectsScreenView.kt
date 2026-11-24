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
import com.loom.assistant.execution.tasks.LoomProject
import com.loom.assistant.execution.tasks.ProjectStatus
import com.loom.assistant.execution.tasks.ProjectStore
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Projects — lockdown card 12.
 * Project list with search, + button, status pills, tap-to-open detail.
 */
class ProjectsScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val store = ProjectStore.get(activity)
    private lateinit var body: LinearLayout
    private var query: String = ""

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
        LoomUi.addCenteredPill(inner, "Projects")

        // Title row: "Projects" + search + "+" (lockdown card 12).
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            LoomUi.text(context, "Projects", R.style.LoomText_Display).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val addBtn = TextView(context).apply {
            text = "+"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_2, 16)
            val s = LoomUi.dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                LoomDialogs.input(
                    activity, "New project",
                    listOf("Name" to "", "Description" to ""),
                    "Create",
                ) { values ->
                    store.create(values[0], values[1])
                    refresh()
                }
            }
        }
        titleRow.addView(addBtn)
        inner.addView(
            titleRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )

        val search = EditText(context).apply {
            hint = "Search projects..."
            setHintTextColor(context.getColor(R.color.loom_text_muted))
            setTextColor(context.getColor(R.color.loom_text_primary))
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_2, 22)
            val h = LoomUi.dp(context, 16)
            val v = LoomUi.dp(context, 12)
            setPadding(h, v, h, v)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    refresh()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        inner.addView(
            search,
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
        body.removeAllViews()
        val items = if (query.isBlank()) store.list() else store.search(query)
        if (items.isEmpty()) {
            body.addView(
                LoomUi.text(
                    context,
                    if (query.isBlank()) "No projects yet. Tap + to create one."
                    else "No matches for \"$query\".",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 24), 0, 0) },
            )
            return
        }
        for (p in items) {
            body.addView(projectCard(p), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) })
        }
    }

    private fun projectCard(p: LoomProject): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            LoomUi.text(context, p.name, R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        titleRow.addView(statusPill(p.status))
        col.addView(titleRow)
        if (p.description.isNotBlank()) {
            col.addView(
                LoomUi.text(
                    context, p.description,
                    R.style.LoomText_Body, R.color.loom_text_secondary,
                ),
            )
        }
        col.addView(
            LoomUi.text(
                context, "Updated ${LoomUi.timeAgo(System.currentTimeMillis(), p.updatedAt)}",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        card.addView(col)
        card.setOnClickListener { showDetail(p) }
        return card
    }

    private fun statusPill(status: ProjectStatus): TextView {
        val color = when (status) {
            ProjectStatus.ACTIVE -> R.color.loom_success
            ProjectStatus.PAUSED -> R.color.loom_warning
            ProjectStatus.COMPLETED -> R.color.loom_accent_bright
        }
        return TextView(context).apply {
            text = status.label
            setTextAppearance(R.style.LoomText_Caption)
            setTextColor(context.getColor(color))
            background = LoomUi.bubbleDrawable(context, R.color.loom_surface_3, 12)
            val h = LoomUi.dp(context, 10)
            val v = LoomUi.dp(context, 6)
            setPadding(h, v, h, v)
        }
    }

    private fun showDetail(p: LoomProject) {
        val statusOptions = ProjectStatus.values().map { s ->
            "${if (s == p.status) "● " else "○ "}${s.label}" to {
                store.update(p.id, p.name, p.description, s)
                refresh()
            }
        }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add("Edit" to {
            LoomDialogs.input(
                activity, "Edit project",
                listOf("Name" to p.name, "Description" to p.description),
                "Save",
            ) { values ->
                store.update(p.id, values[0], values[1], p.status)
                refresh()
            }
        })
        actions.addAll(statusOptions.filter { !it.first.startsWith("●") })
        actions.add("Delete" to {
            LoomDialogs.confirm(
                activity, "Delete project?",
                "\"${p.name}\" will be removed permanently.",
                "Delete",
            ) {
                store.delete(p.id)
                refresh()
            }
        })
        LoomDialogs.options(activity, p.name, actions)
    }
}
