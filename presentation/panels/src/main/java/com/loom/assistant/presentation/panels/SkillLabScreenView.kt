package com.loom.assistant.presentation.panels

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.loom.assistant.intelligence.skilllab.SkillCatalog
import com.loom.assistant.intelligence.skilllab.SkillRecord
import com.loom.assistant.intelligence.skilllab.SkillStatus
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R

/**
 * Skill Laboratory — lockdown card 9.
 * Catalog with the 7 locked statuses, install/enable/disable flows,
 * version display. The preserved shopping skill ships as optional,
 * default OFF.
 */
class SkillLabScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val catalog = SkillCatalog.get(activity)
    private lateinit var body: LinearLayout
    private lateinit var chipRow: LinearLayout
    private var filter: SkillStatus? = null

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
        LoomUi.addCenteredPill(inner, "SKILLS")

        val logoRow = LoomUi.logoRow(context, 56)
        logoRow.addView(
            LoomUi.text(context, "LOOM", R.style.LoomText_Title, gravity = Gravity.CENTER),
        )
        logoRow.addView(
            LoomUi.text(
                context, "Skill Laboratory",
                R.style.LoomText_Body, R.color.loom_accent_bright, Gravity.CENTER,
            ),
        )
        inner.addView(logoRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = LoomUi.dp(context, 12) })

        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val chipScroll = android.widget.HorizontalScrollView(context)
        chipScroll.addView(chipRow)
        inner.addView(
            chipScroll,
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
        chipRow.removeAllViews()
        addChip("All", filter == null) { filter = null; refresh() }
        for (s in SkillStatus.values()) {
            val n = catalog.countByStatus(s)
            addChip("${s.label} ($n)", filter == s) { filter = s; refresh() }
        }

        body.removeAllViews()
        val items = if (filter == null) catalog.list() else catalog.listByStatus(filter!!)
        if (items.isEmpty()) {
            body.addView(
                LoomUi.text(
                    context,
                    if (filter == null) "No skills yet."
                    else "No skills ${filter!!.label.lowercase()}.",
                    R.style.LoomText_Body, R.color.loom_text_muted, Gravity.CENTER,
                ).apply { setPadding(0, LoomUi.dp(context, 24), 0, 0) },
            )
            return
        }
        for (skill in items) {
            body.addView(skillCard(skill), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 10) })
        }
    }

    private fun addChip(label: String, selected: Boolean, onTap: () -> Unit) {
        val chip = TextView(context).apply {
            text = label
            setTextAppearance(R.style.LoomText_Caption)
            setTextColor(
                context.getColor(
                    if (selected) R.color.loom_silver else R.color.loom_text_muted,
                ),
            )
            background = LoomUi.bubbleDrawable(
                context,
                if (selected) R.color.loom_accent_dim else R.color.loom_surface_2,
                16,
            )
            val h = LoomUi.dp(context, 12)
            val v = LoomUi.dp(context, 8)
            setPadding(h, v, h, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
        chipRow.addView(
            chip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = LoomUi.dp(context, 8) },
        )
    }

    private fun skillCard(skill: SkillRecord): LoomCardView {
        val card = LoomCardView(context).apply { isClickable = true; isFocusable = true }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            LoomUi.text(context, skill.name, R.style.LoomText_Headline).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        titleRow.addView(statusPill(skill.status))
        col.addView(titleRow)
        col.addView(
            LoomUi.text(
                context, "v${skill.version} • ${skill.source}",
                R.style.LoomText_Caption, R.color.loom_text_muted,
            ),
        )
        if (skill.description.isNotBlank()) {
            col.addView(
                LoomUi.text(
                    context, skill.description,
                    R.style.LoomText_Body, R.color.loom_text_secondary,
                ),
            )
        }
        card.addView(col)
        card.setOnClickListener { showActions(skill) }
        return card
    }

    private fun statusPill(status: SkillStatus): TextView {
        val color = when (status) {
            SkillStatus.INSTALLED -> R.color.loom_success
            SkillStatus.DISABLED -> R.color.loom_text_muted
            SkillStatus.NEEDS_IMPROVEMENT -> R.color.loom_warning
            SkillStatus.TESTING -> R.color.loom_accent_bright
            else -> R.color.loom_accent
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

    private fun showActions(skill: SkillRecord) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        when (skill.status) {
            SkillStatus.DISCOVERED, SkillStatus.APPROVED -> actions.add(
                "Install (requires your approval)" to {
                    LoomDialogs.confirm(
                        activity, "Install skill?",
                        "\"${skill.name}\" v${skill.version} will be installed and enabled.",
                        "Install",
                    ) {
                        runCatching { catalog.install(skill.id) }
                            .onFailure { LoomDialogs.info(activity, "Cannot install", it.message ?: "Refused.") }
                        refresh()
                    }
                },
            )
            SkillStatus.INSTALLED -> {
                actions.add("Disable" to {
                    runCatching { catalog.disable(skill.id) }
                        .onFailure { LoomDialogs.info(activity, "Cannot disable", it.message ?: "Refused.") }
                    refresh()
                })
                actions.add("Flag needs improvement" to {
                    catalog.flagNeedsImprovement(skill.id)
                    refresh()
                })
            }
            SkillStatus.DISABLED -> {
                actions.add("Enable" to {
                    runCatching { catalog.enable(skill.id) }
                        .onFailure { LoomDialogs.info(activity, "Cannot enable", it.message ?: "Refused.") }
                    refresh()
                })
                if (skill.id != SkillCatalog.SHOPPING_SKILL_ID) {
                    actions.add("Remove" to {
                        LoomDialogs.confirm(
                            activity, "Remove skill?",
                            "\"${skill.name}\" will be removed from the catalog.",
                            "Remove",
                        ) {
                            runCatching { catalog.uninstall(skill.id) }
                                .onFailure { LoomDialogs.info(activity, "Cannot remove", it.message ?: "Refused.") }
                            refresh()
                        }
                    })
                }
            }
            SkillStatus.TESTING -> actions.add("Approve" to {
                runCatching { catalog.approve(skill.id) }
                    .onFailure { LoomDialogs.info(activity, "Cannot approve", it.message ?: "Refused.") }
                refresh()
            })
            SkillStatus.IN_DEVELOPMENT -> actions.add("Move to testing" to {
                catalog.markTesting(skill.id)
                refresh()
            })
            SkillStatus.NEEDS_IMPROVEMENT -> actions.add("Move back to testing" to {
                catalog.markTesting(skill.id)
                refresh()
            })
        }
        if (actions.isEmpty()) {
            LoomDialogs.info(activity, skill.name, "No actions available in status ${skill.status.label}.")
            return
        }
        LoomDialogs.options(activity, "${skill.name} v${skill.version}", actions)
    }
}
