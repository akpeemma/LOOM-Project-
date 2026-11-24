package com.loom.assistant.presentation.chat

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.intelligence.memory.ConversationStore
import com.loom.assistant.intelligence.memory.ConversationSummary
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Conversations — lockdown card 7.
 * Titles, recents, search, tap to reopen and continue where it stopped.
 */
class ConversationsScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val store = ConversationStore.get(activity)
    private lateinit var listBox: LinearLayout
    private lateinit var searchInput: EditText
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
        LoomUi.addCenteredPill(inner, "Conversations")

        // Search.
        searchInput = EditText(context).apply {
            hint = "Search conversations..."
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
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_16)
            },
        )

        // New conversation button.
        val newBtn = TextView(context).apply {
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            text = "+ New conversation"
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 14)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                store.create()
                navigate(LoomDestination.ChatText)
            }
        }
        inner.addView(
            newBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.loom_space_16)
            },
        )

        listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(
            listBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(root)
    }

    private fun refresh() {
        listBox.removeAllViews()
        val items: List<ConversationSummary> =
            if (query.isBlank()) store.list() else store.search(query)
        if (items.isEmpty()) {
            listBox.addView(
                LoomUi.text(
                    context,
                    if (query.isBlank()) "No conversations yet." else "No matches for \"$query\".",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 24), 0, 0) },
            )
            return
        }
        val now = System.currentTimeMillis()
        for (s in items) {
            val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(LoomUi.text(context, s.title, R.style.LoomText_Headline))
            val meta = buildString {
                append(s.preview.take(80))
                if (s.preview.isNotEmpty()) append(" • ")
                append(LoomUi.timeAgo(now, s.updatedAt))
                append(" • ${s.messageCount} msgs")
            }
            col.addView(
                LoomUi.text(context, meta, R.style.LoomText_Caption, R.color.loom_text_muted),
            )
            card.addView(col)
            card.setOnClickListener {
                // Reopen and continue where it stopped — same thread.
                store.setActive(s.id)
                navigate(LoomDestination.ChatText)
            }
            card.setOnLongClickListener {
                confirmDelete(s)
                true
            }
            listBox.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = LoomUi.dp(context, 10) },
            )
        }
    }

    private fun confirmDelete(s: ConversationSummary) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Delete conversation?")
            .setMessage("\"${s.title}\" will be removed permanently.")
            .setPositiveButton("Delete") { d, _ ->
                store.delete(s.id)
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }
}
