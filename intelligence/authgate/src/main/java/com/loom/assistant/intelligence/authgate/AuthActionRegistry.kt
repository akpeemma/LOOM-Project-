package com.loom.assistant.intelligence.authgate

import com.loom.assistant.intelligence.authgate.AuthGate.AuthAction
import com.loom.assistant.intelligence.authgate.AuthGate.RiskClass

/**
 * Phase 6 — named, registered consequential actions (arch §2.14, §6).
 *
 * Port of the registry pattern in repo/core/protocols.py (registered_protocols
 * dict): every gated action is a named entry with a declared risk class —
 * never an ad-hoc string at the call site. Risk classes follow the arch §6
 * auth-gate matrix.
 */
object AuthActionRegistry {

    // ---- action keys (stable identifiers used by gated sinks) ----
    const val ACTION_TAP = "tap.device"
    const val ACTION_TAP_GUIDANCE = "tap.guidance"

    // Stage 2C (§3.2) — demonstration + sensitive-app + financial keys.
    // NOTE: there is deliberately NO "tap.guidance.sensitive" key. Sensitive
    // packages are guidance-only and tokenless (§4 hard rule); no AuthGate
    // action key may exist that could authorize execution inside a sensitive
    // package, so there is nothing for confirmation to override.
    const val ACTION_DEMO_SEQUENCE = "demo.sequence"
    const val ACTION_TAP_GUIDANCE_OUTOFSCOPE = "tap.guidance.outofscope"
    const val ACTION_TAP_FINANCIAL = "tap.financial_transaction"
    const val ACTION_SENSITIVE_APP = "automation.sensitive_app"

    // Stage 2C (decision 4) — incremental catalog gesture keys. Each is
    // consumed by its LoomAccessibilityGestures extension before dispatch.
    const val ACTION_DEVICE_TYPE = "device.type"
    const val ACTION_DEVICE_SUBMIT = "device.submit"
    const val ACTION_DEVICE_SCROLL = "device.scroll"
    const val ACTION_DEVICE_BACK = "device.back"
    const val ACTION_APP_LAUNCH = "app.launch"

    // Dictated messaging + calls (2026-11-05, fix-plan item 12):
    // per-message / per-call CONFIRM keys. Neither is ever grant-eligible
    // (AuthGrantStore.GRANT_ELIGIBLE_KEYS deliberately omits both) — a
    // grant would let later sends/calls skip their card, which violates
    // David's per-message rule. Each send/call requests a FRESH
    // single-use 90s token via the factories below; the factories embed
    // the exact text / contact on the card, which is the last line of
    // the exact-words defence.
    const val ACTION_MESSAGE_SEND = "message.send"
    const val ACTION_CALL_PLACE = "call.place"

    // ---- ALLOW: read-only queries, minor self-heal, own-task resume ----
    val SCREEN_SNAPSHOT = AuthAction(
        key = "screen.snapshot",
        title = "Read the current screen",
        detail = "Reads visible buttons and text to understand the screen. Changes nothing.",
        riskClass = RiskClass.ALLOW,
        isPureRead = true,
    )
    val MEMORY_QUERY = AuthAction(
        key = "memory.query",
        title = "Search memory",
        detail = "Searches your saved memory. Changes nothing.",
        riskClass = RiskClass.ALLOW,
        isPureRead = true,
    )
    val TASK_RESUME_OWN = AuthAction(
        key = "task.resume.own",
        title = "Resume paused task",
        detail = "Continues a task you asked LOOM to pause earlier.",
        riskClass = RiskClass.ALLOW,
    )

    // ---- CONFIRM: plain-language card (what, why, reversible?) ----
    fun tapGuidanceTarget(instruction: String) = tapGuidanceTarget(instruction, scope = "", appLabel = "")

    /**
     * Device-evidence fix (2026-11-05, items 4+6): the search sequence
     * requests its taps with the target app's package as [scope] (and
     * [appLabel] for the offer text). A scoped tap is grant-eligible —
     * "Always allow search steps in YouTube" stores
     * ("tap.guidance", "com.google.android.youtube") — so a stored
     * grant suppresses the search-box / field / result cards for that
     * app on later DIRECT_USER searches. Unscoped callers (guidance
     * sessions) keep scope "" and grantOffer null: their taps never
     * gain or use a grant. Blank scope never matches a grant
     * (AuthGrantStore.hasGrant), so the scoped form is the only one
     * that can ever be grant-covered.
     */
    fun tapGuidanceTarget(instruction: String, scope: String, appLabel: String) = AuthAction(
        key = ACTION_TAP_GUIDANCE,
        title = "Tap this for you?",
        detail = "Guidance wants to tap: $instruction. " +
            "The tap happens in the app currently on screen.",
        riskClass = RiskClass.CONFIRM,
        scope = scope,
        grantOffer = if (scope.isBlank() || appLabel.isBlank()) null
        else "Always allow search steps in $appLabel",
    )

    val DEVICE_TAP = AuthAction(
        key = ACTION_TAP,
        title = "Control this device element?",
        detail = "LOOM wants to tap a screen element on your behalf.",
        riskClass = RiskClass.CONFIRM,
    )

    // ---- Stage 2C (§3.2): demonstration confirmation model ----

    /**
     * Per-sequence approval card. Consumed ONCE by ActionQueue at sequence
     * start (single-use, 90s). Lists the app, step count, and that every tap
     * stays in that app, plus the stop paths.
     */
    /**
     * Device-failure fix (2026-11-13, F4): [scopePackage] scopes
     * the cover request to the plan's target app. A scoped cover
     * in an approved app is covered by pseudo control (no card
     * under a grant); an unscoped cover (blank scope) always
     * cards, exactly as before. Callers that know the plan's
     * package MUST pass it.
     */
    fun demoSequence(
        goal: String,
        appLabel: String,
        stepCount: Int,
        paranoid: Boolean = false,
        scopePackage: String = "",
    ) = AuthAction(
        key = ACTION_DEMO_SEQUENCE,
        title = "Show you how to $goal?",
        detail = "LOOM will demonstrate \"$goal\" in $appLabel: $stepCount tap(s), " +
            "every tap stays in $appLabel. " +
            (if (paranoid) "Each tap will ask for confirmation separately. " else "") +
            "Say \"stop\" or tap Stop anytime to interrupt.",
        riskClass = RiskClass.CONFIRM,
        scope = scopePackage,
    )

    /**
     * Per-step escalation for out-of-scope steps (non-sensitive app only).
     * CONFIRM always — the queue pauses on NeedsConfirmation and resumes
     * only on Approve.
     */
    fun tapGuidanceOutOfScope(
        instruction: String,
        demoApp: String,
        otherApp: String,
    ) = AuthAction(
        key = ACTION_TAP_GUIDANCE_OUTOFSCOPE,
        title = "Step outside $demoApp?",
        detail = "LOOM wants to tap in $otherApp: $instruction. " +
            "This step is outside the demonstrated app.",
        riskClass = RiskClass.CONFIRM,
    )

    /**
     * Sequence-covered per-step tap (in the demonstrated app, non-sensitive,
     * non-financial). ALLOW — but issued ONLY because a live demo.sequence
     * cover exists; without a cover the CONFIRM variant is built instead.
     * Paranoid strictness escalates via the gate's effectiveRisk() anyway.
     */
    fun tapGuidanceSequenced(instruction: String, appPackage: String) = AuthAction(
        key = ACTION_TAP_GUIDANCE,
        title = "Tap this for you?",
        detail = "Guidance wants to tap: $instruction. " +
            "Covered by your approved demonstration in $appPackage.",
        riskClass = RiskClass.ALLOW,
    )

    /**
     * AOIE order (a) (2026-11-07) — sequenced ALLOW variants for
     * EVERY ordinary plan step kind, mirroring
     * [tapGuidanceSequenced]. Same keys, RiskClass.ALLOW, built ONLY
     * at the cover-aware token-provider layer when a live plan cover
     * spans the step. The shared CONFIRM entries below are NEVER
     * changed: flipping a registry entry to ALLOW would silently
     * de-card MODEL-origin requests, guidance taps and standalone
     * searches too (audit §B.5 hazard). These factories are the
     * only ALLOW path for ordinary operation, and the gate's
     * hard-deny check still runs first (effectiveRisk).
     */
    fun deviceTypeSequenced(packageName: String) = AuthAction(
        key = ACTION_DEVICE_TYPE,
        title = "Type text for you?",
        detail = "LOOM is typing into the focused field as part of your approved task in $packageName.",
        riskClass = RiskClass.ALLOW,
        scope = packageName,
    )

    fun deviceSubmitSequenced(packageName: String) = AuthAction(
        key = ACTION_DEVICE_SUBMIT,
        title = "Submit this search?",
        detail = "LOOM is submitting the search as part of your approved task in $packageName.",
        riskClass = RiskClass.ALLOW,
        scope = packageName,
    )

    fun deviceScrollSequenced(packageName: String) = AuthAction(
        key = ACTION_DEVICE_SCROLL,
        title = "Scroll for you?",
        detail = "LOOM is scrolling as part of your approved task in $packageName.",
        riskClass = RiskClass.ALLOW,
        scope = packageName,
    )

    fun deviceBackSequenced(packageName: String) = AuthAction(
        key = ACTION_DEVICE_BACK,
        title = "Go back for you?",
        detail = "LOOM is going back as part of your approved task in $packageName.",
        riskClass = RiskClass.ALLOW,
        scope = packageName,
    )

    fun appLaunchSequenced(packageName: String, label: String) = AuthAction(
        key = ACTION_APP_LAUNCH,
        title = "Open $label?",
        detail = "LOOM is opening $label as part of your approved task.",
        riskClass = RiskClass.ALLOW,
        scope = packageName,
    )

    /**
     * AOIE order (a) — scoped scroll/back CONFIRM actions. Scroll
     * and back were the only ordinary-operation keys that could
     * never be granted (unscoped, not in GRANT_ELIGIBLE_KEYS), so
     * every scroll/back inside a search or plan always carded.
     * Scoped by package with a grant offer, they become grantable
     * per app for DIRECT_USER only — exactly like type/submit.
     * Blank scope/label keeps the old unscoped behaviour (no offer,
     * never grant-covered).
     */
    fun deviceScroll(packageName: String, appLabel: String) = AuthAction(
        key = ACTION_DEVICE_SCROLL,
        title = "Scroll for you?",
        detail = "LOOM wants to scroll the current screen on your behalf.",
        riskClass = RiskClass.CONFIRM,
        scope = packageName,
        grantOffer = if (packageName.isBlank() || appLabel.isBlank()) null
        else "Always allow scrolling in $appLabel",
    )

    fun deviceBack(packageName: String, appLabel: String) = AuthAction(
        key = ACTION_DEVICE_BACK,
        title = "Go back for you?",
        detail = "LOOM wants to press the system back action on your behalf.",
        riskClass = RiskClass.CONFIRM,
        scope = packageName,
        grantOffer = if (packageName.isBlank() || appLabel.isBlank()) null
        else "Always allow going back in $appLabel",
    )

    // ---- Stage 2C (decision 4): incremental catalog gesture actions ----
    // CONFIRM: user-initiated device control, same class as tap.device.

    val DEVICE_TYPE = AuthAction(
        key = ACTION_DEVICE_TYPE,
        title = "Type text for you?",
        detail = "LOOM wants to enter text into the focused field on your behalf.",
        riskClass = RiskClass.CONFIRM,
    )

    // Master implementation §4 — submit the focused search field (IME Enter).
    val DEVICE_SUBMIT = AuthAction(
        key = ACTION_DEVICE_SUBMIT,
        title = "Submit this search?",
        detail = "LOOM wants to press Enter to run the search in the focused field.",
        riskClass = RiskClass.CONFIRM,
    )

    /**
     * Device-evidence fix (2026-11-05, item 6): scoped type/submit
     * actions for the search sequence. [packageName] is the grant
     * scope, [appLabel] the offer text — "Always allow typing in
     * YouTube" / "Always allow submitting searches in YouTube". The
     * static vals above stay for unscoped callers (scope "" →
     * grantOffer null → never grant-covered).
     */
    fun deviceType(packageName: String, appLabel: String) = AuthAction(
        key = ACTION_DEVICE_TYPE,
        title = "Type text for you?",
        detail = "LOOM wants to enter text into the focused field on your behalf.",
        riskClass = RiskClass.CONFIRM,
        scope = packageName,
        grantOffer = if (packageName.isBlank() || appLabel.isBlank()) null
        else "Always allow typing in $appLabel",
    )

    fun deviceSubmit(packageName: String, appLabel: String) = AuthAction(
        key = ACTION_DEVICE_SUBMIT,
        title = "Submit this search?",
        detail = "LOOM wants to press Enter to run the search in the focused field.",
        riskClass = RiskClass.CONFIRM,
        scope = packageName,
        grantOffer = if (packageName.isBlank() || appLabel.isBlank()) null
        else "Always allow submitting searches in $appLabel",
    )

    val DEVICE_SCROLL = AuthAction(
        key = ACTION_DEVICE_SCROLL,
        title = "Scroll for you?",
        detail = "LOOM wants to scroll the current screen on your behalf.",
        riskClass = RiskClass.CONFIRM,
    )

    val DEVICE_BACK = AuthAction(
        key = ACTION_DEVICE_BACK,
        title = "Go back for you?",
        detail = "LOOM wants to press the system back action on your behalf.",
        riskClass = RiskClass.CONFIRM,
    )

    val APP_LAUNCH = AuthAction(
        key = ACTION_APP_LAUNCH,
        title = "Open this app?",
        detail = "LOOM wants to launch an app on your behalf.",
        riskClass = RiskClass.CONFIRM,
    )

    /**
     * Master §8 — scoped app launch. [packageName] is the grant scope
     * (the grant is keyed by action + package, never by label), [label]
     * is the human-readable app name for the card and the "always allow"
     * opt-in ("Always allow opening YouTube"). The launcher builds this
     * only AFTER its sensitive pre-check, so a sensitive package can
     * never reach a card — and therefore never gain a grant.
     */
    fun appLaunch(packageName: String, label: String) = AuthAction(
        key = ACTION_APP_LAUNCH,
        title = "Open $label?",
        detail = "LOOM wants to open $label on your behalf.",
        riskClass = RiskClass.CONFIRM,
        scope = packageName,
        grantOffer = "Always allow opening $label",
    )

    val SKILL_INSTALL = AuthAction(
        key = "skill.install",
        title = "Install this skill?",
        detail = "Adds a new skill to LOOM. It was tested in isolation first.",
        riskClass = RiskClass.CONFIRM,
    )

    val MESSAGE_SEND = AuthAction(
        key = ACTION_MESSAGE_SEND,
        title = "Send this message for you?",
        detail = "LOOM wants to send a message on your behalf. " +
            "You can review the exact text on the confirmation card.",
        riskClass = RiskClass.CONFIRM,
    )

    /**
     * Dictated messaging + calls (2026-11-05, fix-plan item 12): the
     * factory that makes `message.send` usable as the exact-words
     * check. The static val above is generic — nothing puts the text
     * on the card — so the executor MUST request through this factory:
     * the card shows the recipient and the exact dictated text, and
     * David approves THAT text. No scope, no grantOffer: the key is
     * never grant-eligible, so a stored grant can never suppress this
     * card. The text is carried verbatim (surrounding whitespace
     * trimmed only); the executor types exactly what the card shows.
     */
    fun messageSend(recipient: String, text: String) = AuthAction(
        key = ACTION_MESSAGE_SEND,
        title = "Send this message to $recipient?",
        detail = "LOOM wants to send this exact message to $recipient:\n\"$text\"\n" +
            "Check the words — only this message, to only this person, will be sent.",
        riskClass = RiskClass.CONFIRM,
    )

    /**
     * Dictated messaging + calls (2026-11-05, fix-plan item 12): the
     * `call.place` factory. [contactLabel] is the name (or dictated
     * number) David said; [number] is the dialled number when one is
     * known (ACTION_DIAL fallback), "" for the Phone-app route where
     * no number is needed. No scope, no grantOffer: per-call
     * confirmation is structural — a fresh single-use token per call.
     */
    fun callPlace(contactLabel: String, number: String = "") = AuthAction(
        key = ACTION_CALL_PLACE,
        title = "Call $contactLabel?",
        detail = if (number.isBlank()) {
            "LOOM wants to call $contactLabel for you. " +
                "Only this call will be placed."
        } else {
            "LOOM wants to call $contactLabel ($number) for you. " +
                "The dialer will open with this number — you press call. " +
                "Only this call will be placed."
        },
        riskClass = RiskClass.CONFIRM,
    )

    val EXTERNAL_PUBLISH = AuthAction(
        key = "external.publish",
        title = "Publish outside LOOM?",
        detail = "LOOM wants to publish or share content outside the app.",
        riskClass = RiskClass.CONFIRM,
    )

    val SETTINGS_CHANGE = AuthAction(
        key = "settings.change",
        title = "Change this setting?",
        detail = "LOOM wants to change one of your settings.",
        riskClass = RiskClass.CONFIRM,
    )

    val FILE_WRITE_EXTERNAL = AuthAction(
        key = "file.write.external",
        title = "Write a file outside LOOM?",
        detail = "LOOM wants to write a file outside its own storage.",
        riskClass = RiskClass.CONFIRM,
    )

    // ---- DENY: never permitted ----
    val SPEND_MONEY = AuthAction(
        key = "spend.money",
        title = "Spend money",
        detail = "LOOM never spends money without your explicit go-ahead.",
        riskClass = RiskClass.DENY,
    )

    /**
     * Stage 2C (§3.2, §4.3): financial transactions and security-sensitive
     * actions. DENY always, in ANY package. No confirmation surface — the
     * queue refuses before any token request and maps the refusal to this
     * key for audit completeness.
     */
    val FINANCIAL_TRANSACTION = AuthAction(
        key = ACTION_TAP_FINANCIAL,
        title = "Financial or security-sensitive action",
        detail = "LOOM never performs financial transactions or " +
            "security-sensitive actions — not per-sequence, not per-step, " +
            "in any app.",
        riskClass = RiskClass.DENY,
    )

    /**
     * Stage 2C (§3.2, §4.1.3): automation inside a sensitive package.
     * DENY always, no confirmation surface. Backstop: any execution attempt
     * inside a banking/finance/authenticator app is refused by name and
     * audit-logged, even if the queue's pre-dispatch gate were bypassed.
     */
    val SENSITIVE_APP_AUTOMATION = AuthAction(
        key = ACTION_SENSITIVE_APP,
        title = "Automation inside a sensitive app",
        detail = "Banking, finance, and authenticator apps are permanently " +
            "guidance-only. No confirmation can authorize execution inside them.",
        riskClass = RiskClass.DENY,
    )

    val FACTORY_RESET = AuthAction(
        key = "device.factory_reset",
        title = "Factory reset",
        detail = "Destructive device operations are never permitted.",
        riskClass = RiskClass.DENY,
    )

    val SECURITY_CHANGE = AuthAction(
        key = "device.security_change",
        title = "Change device security",
        detail = "Security-sensitive device changes are never permitted.",
        riskClass = RiskClass.DENY,
    )
}
