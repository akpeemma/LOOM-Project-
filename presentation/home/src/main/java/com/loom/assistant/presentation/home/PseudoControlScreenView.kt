package com.loom.assistant.presentation.home

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.loom.assistant.intelligence.authgate.PseudoControlPolicy
import com.loom.assistant.intelligence.authgate.PseudoControlStore
import com.loom.assistant.presentation.designsystem.LoomCardView
import com.loom.assistant.presentation.designsystem.LoomDestination
import com.loom.assistant.presentation.designsystem.LoomUi
import com.loom.assistant.presentation.designsystem.R
import com.loom.assistant.presentation.panels.LoomDialogs

/**
 * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — the
 * in-app grant screen.
 *
 * David's requirement: pseudo control must have a place IN THE LOOM
 * APP where he approves it — a plain-words home-screen entry
 * ("Pseudo control") opening this screen with the approved app list,
 * a deliberate grant control, and a one-tap revoke. Never hidden in
 * Settings without a home-screen entry.
 *
 * What it is, in the words on the screen: LOOM asks once whether he
 * grants pseudo control. If he grants it, LOOM does ordinary things
 * inside the approved apps without bringing up approval cards
 * (approval cards waste time). Pseudo = LOOM acts in his place, like
 * he is doing it, but it is not him and it is not full control: only
 * what he granted, inside the apps he approved.
 *
 * Rules enforced here (the store is the only writer — no voice
 * command, plan, or model request can grant or revoke):
 *  - Granting is a deliberate tap: "Grant pseudo control" opens a
 *    confirmation naming the list, and only the confirm grants.
 *    Never default-on.
 *  - Revocation is one tap and immediate: "Turn off pseudo control"
 *    revokes at once, no dialog; the next in-scope action asks again.
 *  - The grant persists across restarts (SharedPreferences) and the
 *    state is shown here and on the home-screen entry.
 */
class PseudoControlScreenView(
    private val activity: Activity,
    private val navigate: (LoomDestination) -> Unit,
) : ScrollView(activity) {

    private val store = PseudoControlStore.get(activity)
    private lateinit var stateLine: TextView
    private lateinit var grantBtn: TextView
    private lateinit var revokeBtn: TextView

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
        LoomUi.addCenteredPill(inner, "PSEUDO CONTROL")
        inner.addView(
            LoomUi.text(context, "Pseudo control", R.style.LoomText_Display, gravity = Gravity.START),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 4) },
        )
        inner.addView(
            LoomUi.text(
                context, "LOOM acts in your place inside the apps below — " +
                    "ordinary things, without an approval card each time. " +
                    "It is not you and it is not full control: only what " +
                    "you grant here, inside only these apps.",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 16) },
        )

        stateLine = LoomUi.text(context, "", R.style.LoomText_Headline, gravity = Gravity.CENTER)
        inner.addView(
            stateLine,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 16) },
        )

        // The approved apps, with what LOOM may do in each.
        inner.addView(sectionLabel("Without an approval card, in these apps only"))
        val listCard = LoomCardView(context)
        val listCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (app in PseudoControlPolicy.FREE_APPS) {
            listCol.addView(
                LoomUi.text(context, app.label, R.style.LoomText_Body),
            )
            listCol.addView(
                LoomUi.text(
                    context, app.scope,
                    R.style.LoomText_Caption, R.color.loom_text_secondary,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = LoomUi.dp(context, 8) }
                },
            )
        }
        listCard.addView(listCol)
        inner.addView(
            listCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 16) },
        )

        // What still asks, and what never happens.
        inner.addView(sectionLabel("Still asks every time"))
        inner.addView(
            LoomUi.text(
                context, "Sending a WhatsApp message or a text, and placing " +
                    "a call — only when you name the exact person and the " +
                    "exact words. LOOM never guesses these.",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 16) },
        )
        inner.addView(sectionLabel("Never — even with pseudo control"))
        inner.addView(
            LoomUi.text(
                context, "Banking, financial and authenticator apps stay " +
                    "guidance-only forever. Nothing that spends money or " +
                    "buys anything. Nothing that deletes things.",
                R.style.LoomText_Body, R.color.loom_text_secondary,
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 20) },
        )

        // Deliberate grant: button → confirmation naming the list → grant.
        grantBtn = primaryButton("Grant pseudo control") { onGrantTap() }
        inner.addView(
            grantBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 8) },
        )
        // One-tap revoke: immediate, no dialog.
        revokeBtn = primaryButton("Turn off pseudo control") { onRevokeTap() }
        inner.addView(
            revokeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = LoomUi.dp(context, 16) },
        )

        val back = TextView(context).apply {
            text = "← Back to Home"
            setTextAppearance(R.style.LoomText_Label)
            setTextColor(context.getColor(R.color.loom_accent_bright))
            gravity = Gravity.CENTER
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { navigate(LoomDestination.Home) }
        }
        inner.addView(
            back,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        addView(root)
        refresh()
    }

    private fun refresh() {
        if (!::stateLine.isInitialized) return
        val granted = try {
            store.isGranted()
        } catch (_: Exception) {
            false
        }
        stateLine.text = if (granted) {
            "Pseudo control is ON — ${PseudoControlPolicy.FREE_APPS.size} apps"
        } else {
            "Pseudo control is OFF"
        }
        if (::grantBtn.isInitialized) {
            grantBtn.visibility = if (granted) View.GONE else View.VISIBLE
        }
        if (::revokeBtn.isInitialized) {
            revokeBtn.visibility = if (granted) View.VISIBLE else View.GONE
        }
    }

    private fun onGrantTap() {
        val apps = PseudoControlPolicy.FREE_APPS.joinToString(", ") { it.label }
        LoomDialogs.confirm(
            activity,
            "Grant pseudo control?",
            "LOOM will do ordinary things in these apps without " +
                "showing an approval card each time: $apps. " +
                "Messages, calls, money and deleting still ask every " +
                "time, and banking apps stay guidance-only. " +
                "You can turn it off here with one tap, anytime.",
            "Grant pseudo control",
        ) {
            try {
                store.grant()
            } catch (_: Exception) {
            }
            refresh()
            Toast.makeText(
                activity,
                "Pseudo control is on — ${PseudoControlPolicy.FREE_APPS.size} apps",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onRevokeTap() {
        try {
            store.revoke()
        } catch (_: Exception) {
        }
        refresh()
        Toast.makeText(
            activity,
            "Pseudo control is off — approval cards are back",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun sectionLabel(text: String): TextView =
        LoomUi.text(context, text, R.style.LoomText_Label, R.color.loom_text_secondary)
            .apply {
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                p.bottomMargin = LoomUi.dp(context, 8)
                layoutParams = p
            }

    /** Full-width primary button, same visual pattern as Home's. */
    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextAppearance(R.style.LoomText_Headline)
            setTextColor(context.getColor(R.color.loom_text_primary))
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.loom_button_primary)
            val v = LoomUi.dp(context, 12)
            setPadding(v, v, v, v)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
}
