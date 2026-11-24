package com.loom.assistant.intelligence.authgate

/**
 * PSEUDO CONTROL (David-approved 2026-11-11 ~20:28 WAT) — the policy
 * table. Pure data, no Android imports, so the harness proves it
 * without a device.
 *
 * What it is: David grants pseudo control ONCE (a deliberate tap on
 * the in-app grant screen — never a default, never by voice, never by
 * the model). While the grant is active, LOOM performs tasks inside
 * the approved apps WITHOUT bringing up approval cards (approval
 * cards waste time — that is the reason for this feature). Pseudo =
 * LOOM acts in David's place, like he is doing it, but it is not him
 * and it is not full control: only what he granted, inside the apps
 * he approved.
 *
 * THE APPROVED APP LIST (David approved this list as proposed):
 *  - Operate freely (navigate, search, open, read, play — no cards
 *    once granted): YouTube, YouTube Music, Chrome, Facebook,
 *    WhatsApp (open chats, read), Phone (call the person he names),
 *    Messages (read), Gallery (show pictures), Clock (set alarms and
 *    timers), Settings (go to a setting and show it only).
 *  - Only when he names the exact thing, no guessing: WhatsApp
 *    messages (exact person and words), texts (exact person and
 *    words), calls (exact person he names). These still ask EVERY
 *    time — they are NOT covered by this table.
 *  - NEVER, even under pseudo control: banking, financial and
 *    authenticator apps (guidance-only forever — no procedure can
 *    override this); anything that spends money or buys; deleting
 *    things. These are NOT covered by this table either.
 *
 * How the table enforces that (the policy is data, not scattered
 * phrase branches):
 *  - [covers] is true ONLY when the action key is one of the
 *    reversible operation keys ([COVERED_KEYS] — launch, tap (both
 *    tap keys), type, submit, scroll, back) or the plan-cover key
 *    ([COVER_KEYS] — demo.sequence, the per-sequence cover) AND the
 *    action's scope package is one of the approved apps (static
 *    table, plus the runtime-resolved role packages the gate
 *    passes in — resolved by the application layer, see
 *    PseudoControlPackages in platform:services). Everything else —
 *    sends, calls, money, settings changes, file writes,
 *    publishes — falls through to the existing approval/AuthGate
 *    behaviour. The cover decision lives IN this policy as data:
 *    a scoped plan in an approved app does not card under a grant
 *    (device-failure fix 2026-11-13, F4); an unscoped plan always
 *    cards, exactly as before.
 *  - The sensitive-app and financial hard-deny runs FIRST in
 *    [AuthGate.effectiveRisk], before any pseudo check — a pseudo
 *    grant can never authorize execution inside a sensitive app.
 *  - Origin: unlike stored "always allow" grants (DIRECT_USER
 *    only), pseudo control deliberately applies to MODEL-origin
 *    requests too — plan execution IS model-origin
 *    (PlanWiring passes RequestOrigin.MODEL), so an origin gate
 *    would make the feature dead on arrival. The safety boundary
 *    is this TABLE (approved apps + reversible keys), not the
 *    origin — and the task itself is always started by David's own
 *    words. The gate audit-logs every pseudo skip as PSEUDO_ALLOW
 *    (distinct from GRANT_ALLOW) so it stays visible.
 *  - Paranoid strictness (level 2) disables pseudo control, like
 *    it disables stored grants: "confirm everything" means
 *    everything.
 *
 * Package variants: several apps ship under different packages per
 * vendor ROM. The table lists the variants for the SAME named app
 * (e.g. MIUI Gallery vs Google Photos are both "Gallery — show
 * pictures"). Packages marked UNCONFIRMED are the author's best
 * reading of the Redmi 14C (HyperOS) defaults and MUST be confirmed
 * by device test PC-01; a wrong package fails safe (the card
 * appears, the task still works, the test names the package).
 * The MIUI dialer host (com.android.contacts) is the one genuinely
 * uncertain entry: AppProfile notes the Redmi 14C may ship the MIUI
 * dialer instead of the Google Dialer.
 */
object PseudoControlPolicy {

    /** Version of the approved list. A grant is bound to the version
     *  it was granted for: when the list changes, the old grant
     *  lapses and the prompt is asked once more (see
     *  [PseudoControlStore]). */
    const val PSEUDO_VERSION: Int = 1

    /** One approved app: its plain-words label, what LOOM may do in
     *  it without a card, and the package(s) that identify it. */
    data class PseudoApp(
        val label: String,
        val scope: String,
        val packages: List<String>,
    )

    /** The ten approved apps, in the order David approved them. */
    val FREE_APPS: List<PseudoApp> = listOf(
        PseudoApp(
            "YouTube",
            "Find and play videos",
            listOf("com.google.android.youtube"),
        ),
        PseudoApp(
            "YouTube Music",
            "Play songs",
            listOf("com.google.android.apps.youtube.music"),
        ),
        PseudoApp(
            "Chrome",
            "Search the web, open pages",
            listOf("com.android.chrome"),
        ),
        PseudoApp(
            "Facebook",
            "Feed, Reels, search, stories",
            listOf("com.facebook.katana"),
        ),
        PseudoApp(
            "WhatsApp",
            "Open chats, read messages",
            listOf("com.whatsapp"),
        ),
        PseudoApp(
            "Phone",
            "Call the person you name — every call still asks first",
            // Google Dialer (AppProfile seed). Redmi 14C may ship the
            // MIUI dialer instead — its host is the Contacts app:
            // UNCONFIRMED on device, confirm in PC-01.
            listOf("com.google.android.dialer", "com.android.contacts"),
        ),
        PseudoApp(
            "Messages",
            "Read texts — sending still asks every time",
            // Google Messages is the HyperOS default SMS app;
            // com.android.mms is the MIUI SMS host. UNCONFIRMED
            // on device, confirm in PC-01.
            listOf("com.google.android.apps.messaging", "com.android.mms"),
        ),
        PseudoApp(
            "Gallery",
            "Show pictures",
            // MIUI Gallery on HyperOS; Google Photos as the alternate.
            // UNCONFIRMED on device, confirm in PC-01.
            listOf("com.miui.gallery", "com.google.android.apps.photos"),
        ),
        PseudoApp(
            "Clock",
            "Set alarms and timers",
            // Google Clock / AOSP clock. UNCONFIRMED on device.
            listOf("com.google.android.deskclock", "com.android.deskclock"),
        ),
        PseudoApp(
            "Settings",
            "Go to a setting and show it only — changing still asks",
            listOf("com.android.settings"),
        ),
    )

    /** Package → app, for the gate check. */
    private val packageToApp: Map<String, PseudoApp> =
        FREE_APPS.flatMap { app -> app.packages.map { it to app } }.toMap()

    /** The only action keys pseudo control may ever cover: the
     *  reversible operation keys. Deliberately one wider than
     *  [AuthGrantStore.GRANT_ELIGIBLE_KEYS]: [ACTION_TAP]
     *  (tap.device, the dispatcher's tap key) joins the six grant
     *  keys, because a tap inside an approved app is the same
     *  reversible class as a guidance tap — without it, every
     *  dispatched tap cards even under a grant (device-failure
     *  fix 2026-11-13, F4). The table stands alone and is reviewed
     *  on purpose if either set ever changes, never silently
     *  widened. */
    val COVERED_KEYS: Set<String> = setOf(
        AuthActionRegistry.ACTION_APP_LAUNCH,
        AuthActionRegistry.ACTION_TAP,
        AuthActionRegistry.ACTION_TAP_GUIDANCE,
        AuthActionRegistry.ACTION_DEVICE_TYPE,
        AuthActionRegistry.ACTION_DEVICE_SUBMIT,
        AuthActionRegistry.ACTION_DEVICE_SCROLL,
        AuthActionRegistry.ACTION_DEVICE_BACK,
    )

    /** The plan-cover key: the per-sequence demo.sequence card.
     *  Covered ONLY when scoped to an approved app's package —
     *  the scope is what makes the cover decision safe (an
     *  unscoped cover request always cards). Kept separate from
     *  [COVERED_KEYS] so the two decisions stay readable: an
     *  operation key covers one action, the cover key covers one
     *  plan. Both still require the approved-app scope. */
    val COVER_KEYS: Set<String> = setOf(
        AuthActionRegistry.ACTION_DEMO_SEQUENCE,
    )

    /** Actions that are NEVER covered, named explicitly so a test
     *  (and a reader) can see the boundary: sends, calls, money,
     *  financial taps, sensitive-app automation. These always go
     *  through the existing approval / hard-deny path. */
    val NEVER_KEYS: Set<String> = setOf(
        AuthActionRegistry.ACTION_MESSAGE_SEND,
        AuthActionRegistry.ACTION_CALL_PLACE,
        AuthActionRegistry.ACTION_TAP_FINANCIAL,
        AuthActionRegistry.ACTION_SENSITIVE_APP,
        AuthActionRegistry.SPEND_MONEY.key,
    )

    /** True when pseudo control covers this action: a reversible
     *  operation key or the plan-cover key, AND a scope package on
     *  the approved list (static table, plus [extraPackages] —
     *  the runtime-resolved role packages). Blank scope never
     *  matches (unscoped taps and unscoped covers keep their
     *  card). NEVER keys never match, explicitly, whatever the
     *  scope. */
    fun covers(
        actionKey: String,
        scopePackage: String,
        extraPackages: Set<String> = emptySet(),
    ): Boolean {
        if (scopePackage.isBlank()) return false
        if (actionKey in NEVER_KEYS) return false
        if (actionKey !in COVERED_KEYS && actionKey !in COVER_KEYS) return false
        return packageToApp.containsKey(scopePackage) || scopePackage in extraPackages
    }

    /** The approved app for a package, or null. */
    fun appForPackage(packageName: String): PseudoApp? = packageToApp[packageName]

    /** Plain-words label for a package ("YouTube"), or null. */
    fun appLabel(packageName: String): String? = appForPackage(packageName)?.label
}
