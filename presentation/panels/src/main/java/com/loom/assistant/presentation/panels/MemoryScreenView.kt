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
import com.loom.assistant.intelligence.memory.MemoryCategory
import com.loom.assistant.intelligence.memory.MemoryItem
import com.loom.assistant.intelligence.memory.MemoryStore
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Memory Center — lockdown card 10.
 * The 6 locked categories, search, add/edit/delete per item.
 */
class MemoryScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val store = MemoryStore.get(activity)
    private lateinit var body: LinearLayout
    private var openCategory: MemoryCategory? = null
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
        LoomUi.addCenteredPill(inner, "MEMORY")

        val logoRow = LoomUi.logoRow(context, 56)
        logoRow.addView(
            LoomUi.text(context, "LOOM", R.style.LoomText_Title, gravity = Gravity.CENTER),
        )
        logoRow.addView(
            LoomUi.text(
                context, "Memory Center",
                R.style.LoomText_Body, R.color.loom_accent_bright, Gravity.CENTER,
            ),
        )
        inner.addView(logoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 12) })

        inner.addView(
            LoomUi.text(context, "Memory", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )

        val search = EditText(context).apply {
            hint = "Search memory..."
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
        if (query.isNotBlank()) {
            renderItems(store.search(query), "Results for \"$query\"")
            return
        }
        val cat = openCategory
        if (cat == null) {
            renderCategoryGrid()
        } else {
            renderItems(store.listByCategory(cat), cat.title, showBack = true)
        }
    }

    private fun renderCategoryGrid() {
        val cats = MemoryCategory.values()
        var i = 0
        while (i < cats.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            for (j in 0..1) {
                if (i + j >= cats.size) break
                val cat = cats[i + j]
                val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(LoomUi.text(context, cat.title, R.style.LoomText_Headline))
                col.addView(
                    LoomUi.text(
                        context, "${store.countByCategory(cat)}",
                        R.style.LoomText_Title, R.color.loom_accent_bright,
                    ),
                )
                col.addView(
                    LoomUi.text(context, cat.blurb, R.style.LoomText_Caption, R.color.loom_text_muted),
                )
                card.addView(col)
                card.setOnClickListener {
                    openCategory = cat
                    refresh()
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (j == 0) lp.marginEnd = LoomUi.dp(context, 6)
                else lp.marginStart = LoomUi.dp(context, 6)
                row.addView(card, lp)
            }
            body.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 12) },
            )
            i += 2
        }
    }

    private fun renderItems(items: List<MemoryItem>, title: String, showBack: Boolean = false) {
        if (showBack) {
            val back = TextView(context).apply {
                text = "‹ All categories"
                setTextAppearance(R.style.LoomText_Body)
                setTextColor(context.getColor(R.color.loom_accent_bright))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    openCategory = null
                    refresh()
                }
            }
            body.addView(back, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 8) })
        }
        body.addView(
            LoomUi.text(context, title, R.style.LoomText_Headline).apply {
                setPadding(0, 0, 0, LoomUi.dp(context, 8))
            },
        )

        val addBtn = TextView(context).apply {
            text = "+ Add memory"
            setTextAppearance(R.style.LoomText_Body)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { showAddDialog() }
        }
        body.addView(
            addBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 12) },
        )

        if (items.isEmpty()) {
            body.addView(
                LoomUi.text(
                    context, "Nothing here yet.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 16), 0, 0) },
            )
            return
        }
        val now = System.currentTimeMillis()
        for (item in items) {
            val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LoomUi.text(context, item.title, R.style.LoomText_Headline))
            if (item.body.isNotBlank()) {
                col.addView(
                    LoomUi.text(
                        context, item.body.take(140),
                        R.style.LoomText_Body, R.color.loom_text_secondary,
                    ),
                )
            }
            col.addView(
                LoomUi.text(
                    context, "${item.category.title} • ${LoomUi.timeAgo(now, item.updatedAt)}",
                    R.style.LoomText_Caption, R.color.loom_text_muted,
                ),
            )
            card.addView(col)
            card.setOnClickListener { showItemDialog(item) }
            body.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 10) },
            )
        }
    }

    private fun showAddDialog() {
        val cat = openCategory ?: MemoryCategory.DECISIONS
        LoomDialogs.input(
            activity, "Add to ${cat.title}",
            listOf("Title" to "", "Details" to ""),
            "Save",
        ) { values ->
            store.add(cat, values[0], values[1])
            refresh()
        }
    }

    private fun showItemDialog(item: MemoryItem) {
        LoomDialogs.options(
            activity, item.title,
            listOf(
                "Edit" to {
                    LoomDialogs.input(
                        activity, "Edit memory",
                        listOf("Title" to item.title, "Details" to item.body),
                        "Save",
                    ) { values ->
                        store.update(item.id, values[0], values[1])
                        refresh()
                    }
                },
                "Delete" to {
                    LoomDialogs.confirm(
                        activity, "Delete memory?",
                        "\"${item.title}\" will be removed permanently.",
                        "Delete",
                    ) {
                        store.delete(item.id)
                        refresh()
                    }
                },
            ),
        )
    }
}
