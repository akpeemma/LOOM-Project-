package com.loom.assistant.execution.messaging

import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.StopLimits
import com.loom.assistant.execution.screenawareness.TargetAliases

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) — the call
 * sequence. Two routes, one executor:
 *
 *  PRIMARY — Phone app via the interaction pipeline (no number needed):
 *   resolve "Phone" → launch → foreground + readable tree → find the
 *   contact search affordance → tap → focus → type the name → select
 *   the contact row (same in-app matcher as the message executor,
 *   ambiguity → ask) → verify the contact screen → find the call
 *   button → tap it gated by a FRESH `call.place` token → verify the
 *   screen changed (in-call UI). The call itself is placed by the
 *   Phone app's button, not by LOOM — that is the honest boundary.
 *
 *  FALLBACK — ACTION_DIAL intent for dictated numbers ("call
 *  0803…"): no app launch, no number lookup — the number is parsed
 *  from the utterance, a `call.place` token is requested (the card
 *  shows the number), and the dialer opens with it pre-filled. David
 *  presses call himself; the OS UI is the final confirmation. Zero
 *  new permissions. ACTION_CALL + CALL_PHONE are deliberately NOT
 *  built: they place the call with no further user press and need
 *  the manifest permission this build dropped.
 *
 * Per-call confirmation is structural: a fresh single-use 90s
 * `call.place` token per call, and the key is never grant-eligible.
 * The executor takes exactly one contact/number per invocation.
 *
 * Honesty rules, threading and TOCTOU discipline are copied from the
 * message/search sequences (see MessageExecutor.kt header).
 */

// ---------- outcomes ----------

sealed class CallOutcome {
    /** The call was placed (Phone route) or the dialer opened (DIAL route). */
    data class Success(val detail: String) : CallOutcome()

    /** Honest failure — [reason] is what David is told, verbatim. */
    data class Failed(val reason: String) : CallOutcome()

    /** No installed app matches "Phone". */
    data object AppNotFound : CallOutcome()

    /** Several apps match "Phone" — David must pick one. */
    data class AmbiguousApp(val candidates: List<String>) : CallOutcome()

    /** Several contacts match the name — David must pick one. */
    data class AmbiguousContact(val candidates: List<String>) : CallOutcome()
}

/**
 * Gesture sink for calls. Production: LoomAccessibilityService
 * extensions (launchApp / requestTap / typeText / scrollScreen /
 * goBack / callTap / dialNumber). `callTap` consumes a `call.place`
 * token; `dial` consumes a `call.place` token and fires the
 * ACTION_DIAL intent. There is deliberately NO submit() and NO
 * direct-call sink.
 */
interface CallGestures {
    fun launch(packageName: String, token: String?): MessageGestureAcceptance
    fun tap(element: ScreenElement, token: String?): MessageGestureAcceptance
    fun type(text: String, token: String?): MessageGestureAcceptance
    fun scroll(forward: Boolean, token: String?): MessageGestureAcceptance
    fun back(token: String?): MessageGestureAcceptance
    /** The Phone-app call-button tap — consumes a `call.place` token. */
    fun callTap(element: ScreenElement, token: String?): MessageGestureAcceptance
    /** ACTION_DIAL with a `tel:` URI — consumes a `call.place` token. */
    fun dial(telUri: String, token: String?): MessageGestureAcceptance
}

/** The call sequence with constructor-injected seams. */
class CallSequence(
    private val apps: MessageAppResolver,
    private val tree: MessageTreeSource,
    private val gestures: CallGestures,
    private val tokens: MessageTokenProvider,
    private val clock: MessageClock = MessageClock.SYSTEM,
    private val sleeper: MessageSleeper = MessageSleeper.THREAD,
    private val timeouts: MessageTimeouts = MessageTimeouts(),
    private val log: MessageLogger = MessageLogger {},
    /**
     * MASTER F7b — stop signal for this sequence. Default reads the
     * shared [PlanStopRegistry]; tests inject a flag. Checked at
     * every step boundary — before the dial token, before launch,
     * before focus/type, before contact select, before the
     * place-call token — and the sequence aborts honestly with
     * Failed("Stopped.").
     */
    private val isStopped: () -> Boolean = { PlanStopRegistry.isStopped() },
) {

    companion object {
        const val TOCTOU_TOLERANCE_PX = 24
    }

    private sealed interface StepResult {
        data object Ok : StepResult
        data class Recoverable(val reason: String) : StepResult
        data class Fatal(val reason: String) : StepResult
    }

    private fun runStep(attempt: () -> StepResult): StepResult {
        return when (val first = attempt()) {
            is StepResult.Ok, is StepResult.Fatal -> first
            is StepResult.Recoverable -> when (val second = attempt()) {
                is StepResult.Recoverable ->
                    StepResult.Fatal("${second.reason} (after one retry)")
                else -> second
            }
        }
    }

    private fun StepResult.toOutcome(): CallOutcome = when (this) {
        is StepResult.Recoverable -> CallOutcome.Failed(reason)
        is StepResult.Fatal -> CallOutcome.Failed(reason)
        is StepResult.Ok -> error("Ok step has no failure outcome")
    }

    /**
     * MASTER F7b — a stop at a step boundary aborts the sequence
     * honestly. Returns the Failed outcome when stopped, null
     * otherwise; callers `return` it immediately.
     */
    private fun stoppedOutcome(): CallOutcome? =
        if (isStopped()) {
            CallOutcome.Failed("Stopped.").also { log.log("call: stopped at a step boundary") }
        } else {
            null
        }

    // ---------- entry ----------

    fun execute(
        target: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
    ): CallOutcome {
        val t = target.trim()
        if (t.isBlank()) return CallOutcome.Failed("Who should I call?")
        if (isBulkRecipient(t)) {
            return CallOutcome.Failed(
                "I don't place bulk calls — tell me one person at a time.",
            )
        }
        // Dictated number → ACTION_DIAL fallback (no app launch).
        if (isPhoneNumberTarget(t)) {
            return dialNumber(t, origin)
        }
        log.log("call: start target=\"$t\"")
        val resolved = try {
            apps.resolve("Phone")
        } catch (e: Exception) {
            log.log("call: resolve threw: ${e.message}")
            return CallOutcome.Failed("Could not look up \"Phone\": ${e.message}")
        }
        val (pkg, label) = when (resolved) {
            is MessageAppResolution.NotFound -> {
                log.log("call: app not found: \"Phone\"")
                return CallOutcome.AppNotFound
            }
            is MessageAppResolution.Ambiguous -> {
                log.log("call: app ambiguous: \"Phone\" -> ${resolved.candidates}")
                return CallOutcome.AmbiguousApp(resolved.candidates)
            }
            is MessageAppResolution.Found -> resolved.packageName to resolved.label
        }
        log.log("call: resolved \"Phone\" -> $label ($pkg)")
        if (ScreenDataPolicy.isSensitivePackage(pkg)) {
            return CallOutcome.Failed(
                "That's a banking, financial, or authenticator app, so I can only " +
                    "guide you to it — I can't place calls from it.",
            )
        }
        // MASTER F7b — stop before the launch token.
        stoppedOutcome()?.let { return it }
        val launchToken = tokens.tokenFor(
            MessageAuthAction(MessageActionKeys.APP_LAUNCH, "Open $label", pkg, label),
            origin,
        ) ?: return CallOutcome.Failed("Not confirmed — I didn't open $label.").also {
            log.log("call: launch token denied for $label")
        }
        when (val launched = safeLaunch(pkg, launchToken)) {
            is MessageGestureAcceptance.Refused -> {
                log.log("call: launch refused for $label: ${launched.reason}")
                return CallOutcome.Failed("I couldn't open $label: ${launched.reason}")
            }
            MessageGestureAcceptance.Accepted -> log.log("call: launched $label")
        }
        poll(timeouts.foregroundMs) {
            val snap = safeSnapshot()
            if (snap != null && snap.packageName == pkg && snap.elements.isNotEmpty()) snap else null
        } ?: return CallOutcome.Failed(
            "$label did not come to the foreground — the screen is not readable.",
        ).also { log.log("call: foreground wait timed out for $label") }
        log.log("call: $label foreground, tree readable")
        // MASTER F7b — stop before the contact-search focus.
        stoppedOutcome()?.let { return it }
        val focusResult = runStep { focusContactSearch(pkg, label, origin) }
        if (focusResult !is StepResult.Ok) {
            log.log("call: contact-search focus failed: ${stepReason(focusResult)}")
            return focusResult.toOutcome()
        }
        // MASTER F7b — stop before the name-type token.
        stoppedOutcome()?.let { return it }
        val typeToken = tokens.tokenFor(
            MessageAuthAction(MessageActionKeys.DEVICE_TYPE, "Type the contact name", pkg, label),
            origin,
        ) ?: return CallOutcome.Failed("Not confirmed — I didn't type the contact name.").also {
            log.log("call: name-type token denied")
        }
        when (val typed = safeType(t, typeToken)) {
            is MessageGestureAcceptance.Refused -> {
                log.log("call: name type refused: ${typed.reason}")
                return CallOutcome.Failed("I couldn't type the contact name: ${typed.reason}")
            }
            MessageGestureAcceptance.Accepted -> log.log("call: typed contact name")
        }
        val nameVerify = verifyTypedText(t)
        if (nameVerify != null) {
            log.log("call: contact-name verification failed")
            return nameVerify
        }
        // MASTER F7b — stop before the contact select.
        stoppedOutcome()?.let { return it }
        val selectResult = runStep { selectContactRow(pkg, label, t, origin) }
        if (selectResult !is StepResult.Ok) {
            if (selectResult is StepResult.Fatal &&
                selectResult.reason.startsWith("AMBIGUOUS_CONTACT:")
            ) {
                val names = selectResult.reason
                    .removePrefix("AMBIGUOUS_CONTACT:")
                    .split("|").filter { it.isNotBlank() }
                return CallOutcome.AmbiguousContact(names)
            }
            log.log("call: contact select failed: ${stepReason(selectResult)}")
            return selectResult.toOutcome()
        }
        log.log("call: contact \"$t\" opened")
        // Find the call button on the contact screen and tap it gated
        // by a FRESH call.place token.
        // MASTER F7b — stop before the place-call token.
        stoppedOutcome()?.let { return it }
        val callResult = runStep { placeCall(pkg, label, t, origin) }
        if (callResult !is StepResult.Ok) {
            log.log("call: place-call failed: ${stepReason(callResult)}")
            return callResult.toOutcome()
        }
        return CallOutcome.Success("Calling $t.")
    }

    private fun stepReason(result: StepResult): String = when (result) {
        is StepResult.Fatal -> result.reason
        is StepResult.Recoverable -> result.reason
        is StepResult.Ok -> "ok"
    }

    // ---------- ACTION_DIAL fallback ----------

    private fun dialNumber(target: String, origin: RequestOrigin): CallOutcome {
        val number = normalizePhoneNumber(target)
        log.log("call: dial fallback number=\"$number\"")
        // MASTER F7b — stop before the dial token.
        stoppedOutcome()?.let { return it }
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.CALL_PLACE,
                "Call $target", "", "", target, number,
            ),
            origin,
        ) ?: return CallOutcome.Failed("Not confirmed — I didn't dial $target.").also {
            log.log("call: dial token denied")
        }
        return when (val dialled = safeDial("tel:$number", token)) {
            MessageGestureAcceptance.Accepted -> CallOutcome.Success(
                "Opened the dialer for $target — press call to place the call.",
            )
            is MessageGestureAcceptance.Refused -> CallOutcome.Failed(
                "I couldn't open the dialer for $target: ${dialled.reason}",
            )
        }
    }

    // ---------- step: focus the contact search ----------

    /**
     * Same tiers as the message executor's chat search, minus the
     * "Start chat" tier (the Phone app has no such button): a search
     * field, a single bare field, or a search icon/button.
     */
    private fun focusContactSearch(pkg: String, label: String, origin: RequestOrigin): StepResult {
        val affordance = when (val found = findContactSearchAffordance()) {
            is SearchResult.Found -> found.affordance
            is SearchResult.Ambiguous ->
                return StepResult.Fatal(
                    "${found.count} matches for the contact search in $label — be more specific.",
                )
            is SearchResult.None -> {
                val scrollToken = tokens.tokenFor(
                    MessageAuthAction(MessageActionKeys.DEVICE_SCROLL, "Scroll the screen"),
                    origin,
                )
                if (scrollToken != null) safeScroll(true, scrollToken)
                when (val retry = findContactSearchAffordance()) {
                    is SearchResult.Found -> retry.affordance
                    is SearchResult.Ambiguous ->
                        return StepResult.Fatal(
                            "${retry.count} matches for the contact search in $label — be more specific.",
                        )
                    is SearchResult.None ->
                        return StepResult.Fatal("I couldn't find a way to search contacts in $label.")
                }
            }
        }
        val tapToken = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Tap the contact search in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't tap the contact search.")
        when (val tapped = tapFresh(affordance.element, tapToken)) {
            is StepResult.Ok -> Unit
            else -> return tapped
        }
        if (affordance.isField) return waitForFocus()
        val field = poll(timeouts.fieldAppearMs) {
            safeSnapshot()?.elements?.firstOrNull {
                isMessageFieldRole(it) && it.enabled && !it.bounds.isEmpty
            }
        } ?: return StepResult.Recoverable("tapped contact search but no search field appeared")
        val fieldToken = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Tap the search field in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't tap the search field.")
        return when (val fieldTap = tapFresh(field, fieldToken)) {
            is StepResult.Ok -> waitForFocus()
            else -> fieldTap
        }
    }

    private sealed interface SearchResult {
        data class Found(val affordance: Affordance) : SearchResult
        data class Ambiguous(val count: Int) : SearchResult
        data object None : SearchResult
    }

    private data class Affordance(val element: ScreenElement, val isField: Boolean)

    private fun findContactSearchAffordance(): SearchResult {
        val snap = safeSnapshot() ?: return SearchResult.None
        val live = snap.elements.filter { it.enabled && !it.bounds.isEmpty }
        // MASTER Wave 3 (F9) — "search" never resolves to the
        // voice-search mic: TargetAliases.mentionsSearch excludes
        // any element whose description mentions "voice".
        val mentionsSearch: (ScreenElement) -> Boolean = {
            TargetAliases.mentionsSearch(it)
        }
        // MASTER Wave 3 (F9) — tier H (first): the Phone dialer's
        // own "Search contacts" box. The video shows it at the top
        // of the Phone screen in every frame 540–620s, yet the
        // generic tiers missed it 3x — the box is a non-field
        // control whose text IS "Search contacts", so match it
        // exactly before the generic tiers run.
        val tierH = live.filter { el ->
            listOfNotNull(el.text, el.contentDescription, el.hintText).any { v ->
                v.trim().equals("Search contacts", ignoreCase = true)
            }
        }
        if (tierH.size == 1) {
            val el = tierH[0]
            return SearchResult.Found(Affordance(el, isField = isMessageFieldRole(el)))
        }
        if (tierH.size > 1) return SearchResult.Ambiguous(tierH.size)
        val tierA = live.filter { isMessageFieldRole(it) && mentionsSearch(it) }
        if (tierA.size == 1) return SearchResult.Found(Affordance(tierA[0], isField = true))
        if (tierA.size > 1) return SearchResult.Ambiguous(tierA.size)
        val tierB = live.filter {
            isMessageFieldRole(it) &&
                it.text.isNullOrBlank() && it.contentDescription.isNullOrBlank() &&
                it.hintText.isNullOrBlank()
        }
        if (tierB.size == 1) return SearchResult.Found(Affordance(tierB[0], isField = true))
        if (tierB.size > 1) return SearchResult.Ambiguous(tierB.size)
        val tierC = live.filter { !isMessageFieldRole(it) && mentionsSearch(it) }
        if (tierC.size == 1) return SearchResult.Found(Affordance(tierC[0], isField = false))
        if (tierC.size > 1) return SearchResult.Ambiguous(tierC.size)
        return SearchResult.None
    }

    private fun waitForFocus(): StepResult {
        val focused = poll(timeouts.focusMs) {
            if (safeHasFocus()) true else null
        }
        return if (focused == true) StepResult.Ok
        else StepResult.Recoverable("the field never took focus")
    }

    // ---------- step: select the contact row ----------

    private fun selectContactRow(
        pkg: String,
        label: String,
        recipient: String,
        origin: RequestOrigin,
    ): StepResult {
        // MASTER Wave 3 (F14b/F14c) — bounded scroll hunt for the
        // named contact: after each scroll the newly revealed rows
        // are scanned for the contact BEFORE the next scroll; a
        // scroll that changes nothing is the end of the list
        // ("reached the end, not found"); at most
        // MAX_SCROLLS_PER_SEARCH scrolls. Never scrolls past the
        // contact, never scrolls indefinitely (the video scrolled
        // past "My Mummy" while the card covered it).
        var rows = poll(timeouts.rowsMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            val found = findContactLabels(snap, recipient)
            if (found.isEmpty()) null else found
        }
        var scrolls = 0
        var lastSignature = safeSnapshot()?.let { SearchScreenPredicates.viewportSignature(it) }
        while (rows == null && scrolls < StopLimits.MAX_SCROLLS_PER_SEARCH) {
            stoppedOutcome()?.let { return StepResult.Fatal("Stopped.") }
            val scrollToken = tokens.tokenFor(
                MessageAuthAction(MessageActionKeys.DEVICE_SCROLL, "Scroll the screen"),
                origin,
            )
            if (scrollToken == null) break
            if (safeScroll(true, scrollToken) is MessageGestureAcceptance.Refused) break
            scrolls++
            val afterScroll = safeSnapshot()
            val signature = SearchScreenPredicates.viewportSignature(afterScroll)
            if (signature == lastSignature) {
                return StepResult.Fatal(
                    "I couldn't find \"$recipient\" in $label — " +
                        "${StopLimits.REACHED_END_NOT_FOUND}.",
                )
            }
            lastSignature = signature
            rows = poll(timeouts.rowsMs) {
                val snap = safeSnapshot() ?: return@poll null
                if (snap.packageName != pkg) return@poll null
                val found = findContactLabels(snap, recipient)
                if (found.isEmpty()) null else found
            }
        }
        val labels = rows ?: return StepResult.Fatal(
            if (scrolls >= StopLimits.MAX_SCROLLS_PER_SEARCH) {
                "I couldn't find \"$recipient\" in $label — " +
                    StopLimits.MAX_SCROLLS_STOPPING + "."
            } else {
                "I couldn't find \"$recipient\" in $label — check the name and try again."
            },
        )
        if (labels.size > 1) {
            return StepResult.Fatal("AMBIGUOUS_CONTACT:" + labels.joinToString("|"))
        }
        val targetLabel = labels[0]
        val snap = safeSnapshot()
            ?: return StepResult.Recoverable("screen went unreadable before opening the contact")
        val row = snap.elements.firstOrNull {
            it.enabled && !it.bounds.isEmpty && !isMessageFieldRole(it) &&
                it.label() == targetLabel
        } ?: return StepResult.Recoverable("the contact row for \"$recipient\" disappeared")
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Open $targetLabel in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't open the contact.")
        return tapFresh(row, token)
    }

    // ---------- step: place the call ----------

    /**
     * Finds the call button on the contact screen — a non-field element
     * whose text or content-description mentions "call" — and taps it
     * with a FRESH `call.place` token. Exactly one must match.
     * Device-unknown: whether the Phone app exposes the button (vs a
     * call icon inside the contact row itself — the row route is NOT
     * attempted this pass; if device testing shows the contact screen
     * has no exposed call button, the row-icon route is the next
     * design question, not a silent fallback).
     */
    private fun placeCall(
        pkg: String,
        label: String,
        target: String,
        origin: RequestOrigin,
    ): StepResult {
        val preTap = safeSnapshot()
        val snap = preTap
            ?: return StepResult.Recoverable("screen went unreadable before the call")
        val buttons = snap.elements.filter {
            it.enabled && !it.bounds.isEmpty && !isMessageFieldRole(it) &&
                ((it.text?.contains("call", ignoreCase = true) == true) ||
                    (it.contentDescription?.contains("call", ignoreCase = true) == true))
        }
        if (buttons.isEmpty()) {
            return StepResult.Fatal("I couldn't find a call button for $target in $label.")
        }
        if (buttons.size > 1) {
            return StepResult.Fatal(
                "${buttons.size} call buttons for $target in $label — be more specific.",
            )
        }
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.CALL_PLACE,
                "Call $target", pkg, label, target, "",
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't call $target.")
        val tapped = when (val r = safeCallTap(buttons[0], token)) {
            MessageGestureAcceptance.Accepted -> StepResult.Ok
            is MessageGestureAcceptance.Refused ->
                StepResult.Fatal("Call refused: ${r.reason}")
        }
        if (tapped !is StepResult.Ok) return tapped
        // Verify the outcome on the UPDATED screen: same app
        // foreground, tree readable, element set changed (in-call UI).
        val preTapIds = preTap.elements.map { it.id }.toSet()
        val changed = poll(timeouts.landingMs) {
            val s = safeSnapshot() ?: return@poll null
            if (s.packageName != pkg) return@poll null
            val ids = s.elements.map { it.id }.toSet()
            if (ids != preTapIds) true else null
        }
        return if (changed == true) StepResult.Ok
        else StepResult.Fatal("I tapped call, but the screen didn't change — the call may not have started.")
    }

    // ---------- step: verify the typed text ----------

    private fun verifyTypedText(expected: String): CallOutcome? {
        val seen = poll(timeouts.typeVerifyMs) {
            val snap = safeSnapshot() ?: return@poll null
            val field = snap.elements.firstOrNull {
                isMessageFieldRole(it) && it.enabled && !it.bounds.isEmpty
            } ?: return@poll null
            val text = field.text
            when {
                text == null -> true
                text.contains(expected, ignoreCase = true) -> true
                else -> false
            }
        }
        return if (seen == false) {
            CallOutcome.Failed("I typed the text, but it didn't appear in the field.")
        } else {
            null
        }
    }

    // ---------- TOCTOU re-resolution ----------

    private data class ElementIdentity(
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val role: ElementRole,
    )

    private fun identityOf(el: ScreenElement) = ElementIdentity(
        className = el.className,
        text = el.text,
        contentDescription = el.contentDescription,
        role = el.role,
    )

    private fun tapFresh(element: ScreenElement, token: String?): StepResult {
        val snap = safeSnapshot() ?: return StepResult.Recoverable("screen went unreadable before the tap")
        val identity = identityOf(element)
        val fresh = snap.elements.filter {
            identityOf(it) == identity && it.enabled && !it.bounds.isEmpty
        }
        if (fresh.isEmpty()) return StepResult.Recoverable("target-lost: \"${element.label()}\"")
        if (fresh.size > 1) {
            return StepResult.Fatal(
                "the target \"${element.label()}\" now matches ${fresh.size} elements — be more specific",
            )
        }
        val target = fresh[0]
        if (!sameTarget(target, element)) {
            return StepResult.Recoverable("target moved: \"${element.label()}\"")
        }
        return when (val tapped = safeTap(target, token)) {
            MessageGestureAcceptance.Accepted -> StepResult.Ok
            is MessageGestureAcceptance.Refused -> StepResult.Fatal("tap refused: ${tapped.reason}")
        }
    }

    internal fun sameTarget(a: ScreenElement, b: ScreenElement): Boolean {
        val dcx = kotlin.math.abs(a.bounds.exactCenterX() - b.bounds.exactCenterX())
        val dcy = kotlin.math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY())
        if (dcx > TOCTOU_TOLERANCE_PX || dcy > TOCTOU_TOLERANCE_PX) return false
        val aw = a.bounds.width().toDouble()
        val ah = a.bounds.height().toDouble()
        val bw = b.bounds.width().toDouble().coerceAtLeast(1.0)
        val bh = b.bounds.height().toDouble().coerceAtLeast(1.0)
        if (aw < 1 || ah < 1) return false
        return aw / bw in 0.5..2.0 && ah / bh in 0.5..2.0
    }

    // ---------- polling ----------

    private fun <T> poll(timeoutMs: Long, check: () -> T?): T? {
        val deadline = clock.nowMs() + timeoutMs
        while (true) {
            val found = try {
                check()
            } catch (_: Exception) {
                null
            }
            if (found != null) return found
            if (clock.nowMs() >= deadline) return null
            sleeper.sleep(timeouts.pollIntervalMs)
        }
    }

    // ---------- safe seam calls ----------

    private fun safeSnapshot(): ScreenModel? = try {
        tree.snapshot()
    } catch (_: Exception) {
        null
    }

    private fun safeHasFocus(): Boolean = try {
        tree.hasInputFocus()
    } catch (_: Exception) {
        false
    }

    private fun safeLaunch(pkg: String, token: String?): MessageGestureAcceptance = try {
        gestures.launch(pkg, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "launch threw")
    }

    private fun safeTap(el: ScreenElement, token: String?): MessageGestureAcceptance = try {
        gestures.tap(el, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "tap threw")
    }

    private fun safeType(text: String, token: String?): MessageGestureAcceptance = try {
        gestures.type(text, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "type threw")
    }

    private fun safeScroll(forward: Boolean, token: String?): MessageGestureAcceptance = try {
        gestures.scroll(forward, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "scroll threw")
    }

    private fun safeCallTap(el: ScreenElement, token: String?): MessageGestureAcceptance = try {
        gestures.callTap(el, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "call tap threw")
    }

    private fun safeDial(telUri: String, token: String?): MessageGestureAcceptance = try {
        gestures.dial(telUri, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "dial threw")
    }
}

/**
 * The entry point the speech worker calls: `CallExecutor.execute(target)`.
 *
 * Seams are installed once at startup by MessageExecutorWiring
 * (platform:services) — until then [execute] fails honestly instead
 * of dispatching through unwired seams. [execute] BLOCKS on
 * confirmation cards: call it off the main thread.
 */
object CallExecutor {
    @Volatile
    private var sequence: CallSequence? = null

    fun configure(
        apps: MessageAppResolver,
        tree: MessageTreeSource,
        gestures: CallGestures,
        tokens: MessageTokenProvider,
        clock: MessageClock = MessageClock.SYSTEM,
        sleeper: MessageSleeper = MessageSleeper.THREAD,
        timeouts: MessageTimeouts = MessageTimeouts(),
        log: MessageLogger = MessageLogger {},
    ) {
        sequence = CallSequence(apps, tree, gestures, tokens, clock, sleeper, timeouts, log)
    }

    /** Test seam: install a prebuilt sequence directly. */
    fun configureSequence(seq: CallSequence) {
        sequence = seq
    }

    fun execute(
        target: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
    ): CallOutcome =
        sequence?.execute(target, origin)
            ?: CallOutcome.Failed(
                "Calling is not set up yet — the device-control service isn't running.",
            )
}
