package com.loom.assistant.presentation.panels

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.intelligence.llm.ModelSelection
import com.loom.assistant.platform.storage.LoomPreferences
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.ModelPickerDialog
import com.loom.assistant.presentation.designsystem.R

/**
 * More — lockdown card 11.
 * Profile header + the 8 menu entries. LOOM Intelligence and Projects
 * open from here (never bottom-nav). Every row does something real.
 */
class MoreScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val prefs = LoomPreferences.get(activity)
    private val modelSelection = ModelSelection(activity)
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
        LoomUi.addCenteredPill(inner, "MORE")

        val logoRow = LoomUi.logoRow(context, 56)
        logoRow.addView(
            LoomUi.text(context, "LOOM", R.style.LoomText_Title, gravity = Gravity.CENTER),
        )
        inner.addView(logoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 12) })

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

        // Profile header.
        val profileCard = LoomCardView(context)
        val profileRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(context).apply {
            text = prefs.profileName.firstOrNull()?.uppercase() ?: "L"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_silver))
            gravity = Gravity.CENTER
            background = LoomUi.bubbleDrawable(context, R.color.loom_accent_dim, 28)
            val s = LoomUi.dp(context, 56)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = LoomUi.dp(context, 12)
            }
        }
        profileRow.addView(avatar)
        val nameCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val displayName = prefs.profileName.ifBlank { "LOOM user" }
        nameCol.addView(LoomUi.text(context, displayName, R.style.LoomText_Headline))
        nameCol.addView(
            LoomUi.text(context, "Tap to set your name", R.style.LoomText_Caption, R.color.loom_text_muted),
        )
        profileRow.addView(nameCol)
        profileCard.addView(profileRow)
        profileCard.apply { isClickable = true; isFocusable = true }
        profileCard.setOnClickListener {
            LoomDialogs.input(
                activity, "Profile",
                listOf("Your name" to prefs.profileName),
                "Save",
            ) { values ->
                prefs.profileName = values[0].trim()
                refresh()
            }
        }
        body.addView(profileCard, rowParams())

        // Menu rows (lockdown card 11 order).
        addRow("LOOM Intelligence", "Dashboard and live overview") {
            navigate(LoomDestination.Intelligence)
        }
        addRow("Projects", "Your active projects") {
            navigate(LoomDestination.Projects)
        }
        addRow("AI Connections", modelSelection.label) {
            ModelPickerDialog.show(activity, modelSelection) { refresh() }
        }
        addRow("Appearance", "Theme, Character, Orb") {
            navigate(LoomDestination.Settings)
        }
        addRow("Assistant Modes", "Character, Orb, Invisible") {
            navigate(LoomDestination.ModeCharacter)
        }
        addRow("Voice & Speech", "Voice settings") {
            navigate(LoomDestination.Settings)
        }
        addRow("Automation", "Automated tasks") {
            navigate(LoomDestination.Settings)
        }
        addRow("Privacy & Security", "Your data") {
            navigate(LoomDestination.Settings)
        }
    }

    private fun addRow(title: String, subtitle: String, onTap: () -> Unit) {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(LoomUi.text(context, title, R.style.LoomText_Headline))
        col.addView(
            LoomUi.text(context, subtitle, R.style.LoomText_Caption, R.color.loom_text_muted),
        )
        row.addView(col)
        val chevron = TextView(context).apply {
            text = "›"
            setTextAppearance(R.style.LoomText_Title)
            setTextColor(context.getColor(R.color.loom_accent_bright))
        }
        row.addView(chevron)
        card.addView(row)
        card.setOnClickListener { onTap() }
        body.addView(card, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = LoomUi.dp(context, 10) }
}
