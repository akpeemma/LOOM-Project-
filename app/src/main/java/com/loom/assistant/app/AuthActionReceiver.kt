package com.loom.assistant.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.loom.assistant.core.common.LoomIntents
import com.loom.assistant.interaction.notifications.NotificationCenter
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * Phase 6 — handles the Approve/Deny actions on the AuthGate confirmation
 * fallback notification (arch §2.14). Runs in LOOM's own process, so it
 * resolves the gate's pending confirmation directly. Declared in the app
 * manifest (exported=false).
 *
 * Device-evidence fix (2026-11-05, item 10): the "Always allow" action
 * resolves with alwaysAllow=true — the gate re-validates origin, key,
 * scope and strictness before storing any grant, so a notification
 * approval can create a grant only where the overlay card could.
 */
class AuthActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getStringExtra(LoomIntents.EXTRA_AUTH_PENDING_ID) ?: return
        val alwaysAllow = intent.action == LoomIntents.ACTION_AUTH_APPROVE_ALWAYS
        val accepted = alwaysAllow || intent.action == LoomIntents.ACTION_AUTH_APPROVE
        try {
            AuthGate.getOrNull()?.resolve(pendingId, accepted, alwaysAllow)
        } catch (_: Exception) {
        }
        try {
            NotificationCenter.get(context).dismissAuthRequest(pendingId)
        } catch (_: Exception) {
        }
    }
}
