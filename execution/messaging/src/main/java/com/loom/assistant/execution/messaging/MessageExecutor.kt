package com.loom.assistant.execution.messaging

import com.loom.assistant.core.common.MessageChannel
import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * Dictated messaging + calls (2026-11-05, fix-plan item 12) — the
 * message sequence. Mirrors the search sequence
 * (`execution/search/.../SearchExecutor.kt`) step for step, with the
 * app + step targets as parameters, so ONE executor serves WhatsApp
 * and SMS:
 *
 *   resolve app ([MessageAppResolver]) → launch (existing `app.launch`
 *   sink) → wait for foreground + readable tree → find the chat-list
 *   search affordance → tap → wait-for-focus → type the contact name
 *   (live-filtered rows, no submit) → select the chat row matching
 *   the name → verify the chat opened → find the compose field
 *   (hint "Message") → tap → wait-for-focus → type the EXACT dictated
 *   words → verify the typed text → tap the send button gated by a
 *   FRESH `message.send` token → verify the message was sent.
 *
 * What is deliberately NOT here:
 *  - No `ActionQueue` routing: the queue still fails non-TAP steps
 *    honestly, so messages get their own executor like search has.
 *  - No `submitSearch` / IME Enter for sending: WhatsApp's Enter key
 *    inserts a newline by default ("Enter is send" is an opt-in
 *    setting), so reusing it would type a newline, not send. The send
 *    tap is a new `message.send`-gated sink (a `tap.guidance` token
 *    cannot substitute — tokens are key-bound).
 *  - No contacts provider, no READ_CONTACTS: contact resolution
 *    happens INSIDE the target app's own search UI (type the name,
 *    match the row). A device-contacts fallback is NOT built this
 *    pass — only if device testing proves in-app search unreliable.
 *  - No SmsManager / SEND_SMS: SMS goes through the Messages app via
 *    this same pipeline (visible to David, honest tree verification).
 *  - No bulk: the executor takes exactly one (recipient, text) per
 *    invocation; a bulk-looking recipient is refused here as a second
 *    line of defence (parse-time refusal is the first).
 *
 * Honesty rules (copied from the search sequence):
 *  - NEVER report success from dispatch alone: every step verifies
 *    against a fresh snapshot.
 *  - Targets are re-identified by identity on every step — never
 *    stale coordinates or cached bounds (TOCTOU re-resolution, same
 *    24px tolerance as ActionQueue).
 *  - On a recoverable failure the step is re-resolved and retried
 *    ONCE; a second failure fails honestly with the reason.
 *  - Sensitive apps are guidance-only: the resolved package is
 *    checked BEFORE any token is requested, and every gesture sink
 *    re-checks the foreground package itself.
 *
 * Device-unknowns (only David's Redmi 14C can settle them — the
 * scoping file lists them per step): whether WhatsApp's search icon /
 * chat rows / compose hint / send button / sent bubbles are exposed
 * in the accessibility tree, and the same for Messages. A step that
 * cannot be observed in the tree is reported as unverified, never
 * as passed.
 *
 * Threading: [MessageSequence.execute] BLOCKS — token acquisition
 * waits on David's confirmation card (up to the gate's 90s pending
 * expiry). Call it off the main thread.
 */

// ---------- outcomes ----------

sealed class MessageOutcome {
    /** The message was sent and verified (see [detail] for how). */
    data class Success(val detail: String) : MessageOutcome()

    /** Honest failure — [reason] is what David is told, verbatim. */
    data class Failed(val reason: String) : MessageOutcome()

    /** No installed app matches the channel's app name. */
    data object AppNotFound : MessageOutcome()

    /** Several apps match the channel's app name — David must pick one. */
    data class AmbiguousApp(val candidates: List<String>) : MessageOutcome()

    /** Several contacts match the name — David must pick one. */
    data class AmbiguousContact(val candidates: List<String>) : MessageOutcome()
}

// ---------- injected seams (mirror the search module's, module-local
// so this module does not depend on execution:search) ----------

/** App resolution. Production: Worker 1's shared AppCatalogue.resolveByName. */
sealed class MessageAppResolution {
    data class Found(val packageName: String, val label: String) : MessageAppResolution()
    data object NotFound : MessageAppResolution()
    data class Ambiguous(val candidates: List<String>) : MessageAppResolution()
}

fun interface MessageAppResolver {
    fun resolve(name: String): MessageAppResolution
}

/** Live-tree reads. Production: ScreenAwarenessHolder.current + focus probe. */
interface MessageTreeSource {
    fun snapshot(): ScreenModel?
    /** True when a text field currently holds input focus. */
    fun hasInputFocus(): Boolean
}

/** One gesture acceptance — mirrors GestureResult without the Android module. */
sealed class MessageGestureAcceptance {
    data object Accepted : MessageGestureAcceptance()
    data class Refused(val reason: String) : MessageGestureAcceptance()
}

/**
 * Gesture sink. Production: LoomAccessibilityService extensions
 * (launchApp / requestTap / typeText / scrollScreen / goBack /
 * sendTap) — each consumes its own single-use token inside the atomic
 * consume+dispatch boundary, so a token passed here is spent by the
 * sink. NOTE: there is deliberately NO submit() here — IME Enter
 * must never send a message (see the header).
 */
interface MessageGestures {
    fun launch(packageName: String, token: String?): MessageGestureAcceptance
    fun tap(element: ScreenElement, token: String?): MessageGestureAcceptance
    fun type(text: String, token: String?): MessageGestureAcceptance
    fun scroll(forward: Boolean, token: String?): MessageGestureAcceptance
    fun back(token: String?): MessageGestureAcceptance
    /** The send-button tap — consumes a `message.send` token, nothing else. */
    fun sendTap(element: ScreenElement, token: String?): MessageGestureAcceptance
}

/**
 * What the executor asks the gate for. [key] is the registry action
 * key; [label] is the human-readable instruction shown on the
 * confirmation card. [recipient]/[text] carry the exact words for
 * `message.send` (and the contact/number for `call.place`) so the
 * production wiring can build the factory action that puts them on
 * the card. [scope]/[appLabel] carry the app package/label for the
 * scoped launch/type/tap actions (grant offer + stored grants).
 */
data class MessageAuthAction(
    val key: String,
    val label: String,
    val scope: String = "",
    val appLabel: String = "",
    val recipient: String = "",
    val text: String = "",
)

/** Registry action keys, mirrored from AuthActionRegistry — keep in sync. */
object MessageActionKeys {
    const val APP_LAUNCH = "app.launch"
    const val TAP_GUIDANCE = "tap.guidance"
    const val DEVICE_TYPE = "device.type"
    const val DEVICE_SCROLL = "device.scroll"
    const val DEVICE_BACK = "device.back"
    const val MESSAGE_SEND = "message.send"
    const val CALL_PLACE = "call.place"
}

/**
 * Token acquisition. Production: a blocking AuthGate.request (see
 * MessageExecutorWiring) — null when David declines, the card times
 * out, or the gate is unavailable. Never cached: every attempt
 * requests a fresh single-use token. For `message.send` /
 * `call.place` this is structural: the key is never grant-eligible,
 * so every send/call always cards.
 */
fun interface MessageTokenProvider {
    fun tokenFor(action: MessageAuthAction): String?

    fun tokenFor(action: MessageAuthAction, origin: RequestOrigin): String? =
        tokenFor(action)
}

fun interface MessageClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: MessageClock = MessageClock { System.currentTimeMillis() }
    }
}

fun interface MessageSleeper {
    fun sleep(ms: Long)

    companion object {
        val THREAD: MessageSleeper = MessageSleeper { Thread.sleep(it) }
    }
}

data class MessageTimeouts(
    val foregroundMs: Long = 10_000,
    val fieldAppearMs: Long = 5_000,
    val focusMs: Long = 5_000,
    val typeVerifyMs: Long = 2_000,
    val rowsMs: Long = 5_000,
    val chatOpenMs: Long = 5_000,
    val sentMs: Long = 5_000,
    val landingMs: Long = 5_000,
    val pollIntervalMs: Long = 250,
)

/** Per-step message logging. Never logs screen content — only step names. */
fun interface MessageLogger {
    fun log(message: String)
}

/**
 * Bulk-looking recipients, refused at parse time first (VoiceCommands)
 * and here as a second line of defence. The executor takes exactly
 * one recipient per invocation — no list, no loop, no "everyone".
 */
fun isBulkRecipient(recipient: String): Boolean {
    val t = recipient.lowercase().trim()
    return t == "everyone" || t == "everybody" || t == "all" ||
        t == "all contacts" || t == "all my contacts" ||
        t.contains("everyone") || t.contains("everybody") ||
        t.contains("all contacts") || t.contains("all my contacts") ||
        t.contains("everyone in") || t.contains("all of my contacts")
}

/** True when [target] looks like a dictated phone number, not a name. */
fun isPhoneNumberTarget(target: String): Boolean {
    val digits = target.filter { it.isDigit() }
    if (digits.length < 7) return false
    return target.all { it.isDigit() || it == '+' || it == '*' || it == '#' ||
        it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
}

/** Digits-only form of a dictated number, for the `tel:` URI. */
fun normalizePhoneNumber(target: String): String =
    target.filter { it.isDigit() || it == '+' }

/**
 * In-app contact-row matching, shared by the message and call
 * executors. Candidates are visible, enabled, non-field elements
 * whose text (or content-description) mentions the name. Exact-name
 * matches win over contains matches, so a chat preview that happens
 * to mention the name cannot create a false ambiguity — a preview
 * almost never EQUALS the contact's name.
 *
 * Group chats vs individuals with the same name are ambiguous → ask.
 * Returns the distinct matching labels ("" when none match).
 */
internal fun findContactLabels(
    snap: ScreenModel,
    recipient: String,
): List<String> {
    val name = recipient.trim()
    if (name.isBlank()) return emptyList()
    val live = snap.elements.filter {
        it.enabled && !it.bounds.isEmpty && !isMessageFieldRole(it)
    }
    val mentions: (ScreenElement) -> Boolean = {
        (it.text?.contains(name, ignoreCase = true) == true) ||
            (it.contentDescription?.contains(name, ignoreCase = true) == true)
    }
    val exact = live.filter {
        it.text?.trim().equals(name, ignoreCase = true) == true
    }
    val pool = if (exact.isNotEmpty()) exact else live.filter(mentions)
    return pool.map { it.label() }.distinct()
}

/** True for INPUT and SEARCH_FIELD roles — every text-entry field. */
internal fun isMessageFieldRole(el: ScreenElement): Boolean =
    el.role == ElementRole.INPUT || el.role == ElementRole.SEARCH_FIELD

/** The message sequence with constructor-injected seams. */
class MessageSequence(
    private val apps: MessageAppResolver,
    private val tree: MessageTreeSource,
    private val gestures: MessageGestures,
    private val tokens: MessageTokenProvider,
    private val clock: MessageClock = MessageClock.SYSTEM,
    private val sleeper: MessageSleeper = MessageSleeper.THREAD,
    private val timeouts: MessageTimeouts = MessageTimeouts(),
    private val log: MessageLogger = MessageLogger {},
    /**
     * MASTER F7b — stop signal for this sequence. Default reads the
     * shared [PlanStopRegistry]; tests inject a flag. Checked at
     * every step boundary — before launch, before focus/type,
     * before contact select, before compose focus/type, before the
     * send — and the sequence aborts honestly with Failed("Stopped.").
     */
    private val isStopped: () -> Boolean = { PlanStopRegistry.isStopped() },
) {

    companion object {
        /** Re-identification tolerance — mirrors ActionQueue.TOCTOU_TOLERANCE_PX. */
        const val TOCTOU_TOLERANCE_PX = 24
    }

    private sealed interface StepResult {
        data object Ok : StepResult
        /** Safe to re-resolve from the live tree and try once more. */
        data class Recoverable(val reason: String) : StepResult
        /** Do not retry — the reason is final. */
        data class Fatal(val reason: String) : StepResult
    }

    /** Runs [attempt]; on a recoverable failure, re-resolves and retries ONCE. */
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

    private fun StepResult.toOutcome(): MessageOutcome = when (this) {
        is StepResult.Recoverable -> MessageOutcome.Failed(reason)
        is StepResult.Fatal -> MessageOutcome.Failed(reason)
        is StepResult.Ok -> error("Ok step has no failure outcome")
    }

    /**
     * MASTER F7b — a stop at a step boundary aborts the sequence
     * honestly. Returns the Failed outcome when stopped, null
     * otherwise; callers `return` it immediately.
     */
    private fun stoppedOutcome(): MessageOutcome? =
        if (isStopped()) {
            MessageOutcome.Failed("Stopped.").also { log.log("message: stopped at a step boundary") }
        } else {
            null
        }

    // ---------- entry ----------

    fun execute(
        channel: MessageChannel,
        recipient: String,
        text: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
    ): MessageOutcome {
        val to = recipient.trim()
        // Exact words: the text is carried verbatim — surrounding
        // whitespace trimmed only, never reworded or normalized.
        val exactText = text.trim()
        if (to.isBlank()) return MessageOutcome.Failed("Who should I message?")
        if (exactText.isBlank()) return MessageOutcome.Failed("What should I say?")
        if (isBulkRecipient(to)) {
            return MessageOutcome.Failed(
                "I don't send bulk messages — tell me one person at a time.",
            )
        }
        val appName = when (channel) {
            MessageChannel.WHATSAPP -> "WhatsApp"
            MessageChannel.SMS -> "Messages"
        }
        log.log("message: start channel=$channel to=\"$to\"")
        val resolved = try {
            apps.resolve(appName)
        } catch (e: Exception) {
            log.log("message: resolve threw: ${e.message}")
            return MessageOutcome.Failed("Could not look up \"$appName\": ${e.message}")
        }
        val (pkg, label) = when (resolved) {
            is MessageAppResolution.NotFound -> {
                log.log("message: app not found: \"$appName\"")
                return MessageOutcome.AppNotFound
            }
            is MessageAppResolution.Ambiguous -> {
                log.log("message: app ambiguous: \"$appName\" -> ${resolved.candidates}")
                return MessageOutcome.AmbiguousApp(resolved.candidates)
            }
            is MessageAppResolution.Found -> resolved.packageName to resolved.label
        }
        log.log("message: resolved \"$appName\" -> $label ($pkg)")
        if (ScreenDataPolicy.isSensitivePackage(pkg)) {
            return MessageOutcome.Failed(
                "That's a banking, financial, or authenticator app, so I can only " +
                    "guide you to it — I can't send messages from it.",
            )
        }
        // MASTER F7b — stop before the launch token.
        stoppedOutcome()?.let { return it }
        val launchToken = tokens.tokenFor(
            MessageAuthAction(MessageActionKeys.APP_LAUNCH, "Open $label", pkg, label),
            origin,
        ) ?: return MessageOutcome.Failed("Not confirmed — I didn't open $label.").also {
            log.log("message: launch token denied for $label")
        }
        when (val launched = safeLaunch(pkg, launchToken)) {
            is MessageGestureAcceptance.Refused -> {
                log.log("message: launch refused for $label: ${launched.reason}")
                return MessageOutcome.Failed("I couldn't open $label: ${launched.reason}")
            }
            MessageGestureAcceptance.Accepted -> log.log("message: launched $label")
        }
        poll(timeouts.foregroundMs) {
            val snap = safeSnapshot()
            if (snap != null && snap.packageName == pkg && snap.elements.isNotEmpty()) snap else null
        } ?: return MessageOutcome.Failed(
            "$label did not come to the foreground — the screen is not readable.",
        ).also { log.log("message: foreground wait timed out for $label") }
        log.log("message: $label foreground, tree readable")
        // Focus the chat-list search (affordance → tap → focus).
        // MASTER F7b — stop before the focus step.
        stoppedOutcome()?.let { return it }
        val focusResult = runStep { focusChatSearch(pkg, label, origin) }
        if (focusResult !is StepResult.Ok) {
            log.log("message: chat-search focus failed: ${stepReason(focusResult)}")
            return focusResult.toOutcome()
        }
        log.log("message: chat search focused in $label")
        // Type the contact name (live-filtered rows — no submit).
        // MASTER F7b — stop before the name-type token.
        stoppedOutcome()?.let { return it }
        val typeToken = tokens.tokenFor(
            MessageAuthAction(MessageActionKeys.DEVICE_TYPE, "Type the contact name", pkg, label),
            origin,
        ) ?: return MessageOutcome.Failed("Not confirmed — I didn't type the contact name.").also {
            log.log("message: name-type token denied")
        }
        when (val typed = safeType(to, typeToken)) {
            is MessageGestureAcceptance.Refused -> {
                log.log("message: name type refused: ${typed.reason}")
                return MessageOutcome.Failed("I couldn't type the contact name: ${typed.reason}")
            }
            MessageGestureAcceptance.Accepted -> log.log("message: typed contact name")
        }
        val nameVerify = verifyTypedText(to)
        if (nameVerify != null) {
            log.log("message: contact-name verification failed")
            return nameVerify
        }
        // Select the chat row matching the name.
        // MASTER F7b — stop before the contact select.
        stoppedOutcome()?.let { return it }
        val selectResult = runStep { selectContactRow(pkg, label, to, origin) }
        if (selectResult !is StepResult.Ok) {
            if (selectResult is StepResult.Fatal &&
                selectResult.reason.startsWith("AMBIGUOUS_CONTACT:")
            ) {
                val names = selectResult.reason
                    .removePrefix("AMBIGUOUS_CONTACT:")
                    .split("|").filter { it.isNotBlank() }
                return MessageOutcome.AmbiguousContact(names)
            }
            log.log("message: contact select failed: ${stepReason(selectResult)}")
            return selectResult.toOutcome()
        }
        log.log("message: chat with \"$to\" opened")
        // Verify the chat opened: compose field present, same package.
        val chatOpen = poll(timeouts.chatOpenMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            if (findComposeField(snap) != null) snap else null
        }
        if (chatOpen == null) {
            return MessageOutcome.Failed(
                "I opened a chat for \"$to\", but I couldn't find the message field — " +
                    "the chat may not have opened.",
            ).also { log.log("message: compose field never appeared") }
        }
        // Focus the compose field.
        // MASTER F7b — stop before the compose focus.
        stoppedOutcome()?.let { return it }
        val composeResult = runStep { focusComposeField(pkg, label, origin) }
        if (composeResult !is StepResult.Ok) {
            log.log("message: compose focus failed: ${stepReason(composeResult)}")
            return composeResult.toOutcome()
        }
        log.log("message: compose field focused")
        // Type the EXACT dictated words, verbatim.
        // MASTER F7b — stop before the message-type token.
        stoppedOutcome()?.let { return it }
        val textToken = tokens.tokenFor(
            MessageAuthAction(MessageActionKeys.DEVICE_TYPE, "Type the message", pkg, label),
            origin,
        ) ?: return MessageOutcome.Failed("Not confirmed — I didn't type the message.").also {
            log.log("message: message-type token denied")
        }
        when (val typed = safeType(exactText, textToken)) {
            is MessageGestureAcceptance.Refused -> {
                log.log("message: message type refused: ${typed.reason}")
                return MessageOutcome.Failed("I couldn't type the message: ${typed.reason}")
            }
            MessageGestureAcceptance.Accepted -> log.log("message: typed exact text")
        }
        // Exact-words check 2: the field must contain the exact text
        // before the send tap. A field that exposes no text is
        // unverified, never faked as verified.
        val textVerify = verifyTypedText(exactText)
        if (textVerify != null) {
            log.log("message: exact-text verification failed")
            return textVerify
        }
        log.log("message: exact text verified in the field")
        // Send — tap the send button gated by a FRESH message.send
        // token. The card shows recipient + exact text (factory).
        // MASTER F7b — stop before the send token.
        stoppedOutcome()?.let { return it }
        val sendResult = runStep { sendMessage(pkg, label, to, exactText, origin) }
        if (sendResult !is StepResult.Ok) {
            log.log("message: send failed: ${stepReason(sendResult)}")
            return sendResult.toOutcome()
        }
        // Verify sent: bubble with the exact text, or the field cleared.
        return verifySent(pkg, label, to, exactText)
    }

    private fun stepReason(result: StepResult): String = when (result) {
        is StepResult.Fatal -> result.reason
        is StepResult.Recoverable -> result.reason
        is StepResult.Ok -> "ok"
    }

    // ---------- step: focus the chat-list search ----------

    /**
     * Finds the chat-list search affordance, taps it, and waits for
     * input focus. Tiers (most-specific first):
     *  A. A search field whose text/desc/hint mentions "search".
     *  B. A single bare, empty field.
     *  C. A button/control whose text/desc mentions "search"
     *     (WhatsApp's icon-only search button).
     *  D. A labelled compose/start button — "Start chat" / "New chat" /
     *     "Compose" (Messages' conversation-list entry point).
     * A button (C/D) opens a field: the field is re-found fresh and
     * tapped. More than one match inside the chosen tier refuses
     * honestly instead of guessing.
     */
    private fun focusChatSearch(pkg: String, label: String, origin: RequestOrigin): StepResult {
        val affordance = when (val found = findChatSearchAffordance()) {
            is ChatSearchResult.Found -> found.affordance
            is ChatSearchResult.Ambiguous ->
                return StepResult.Fatal(
                    "${found.count} matches for the chat search in $label — be more specific.",
                )
            is ChatSearchResult.None -> {
                val scrollToken = tokens.tokenFor(
                    MessageAuthAction(MessageActionKeys.DEVICE_SCROLL, "Scroll the screen"),
                    origin,
                )
                if (scrollToken != null) safeScroll(true, scrollToken)
                when (val retry = findChatSearchAffordance()) {
                    is ChatSearchResult.Found -> retry.affordance
                    is ChatSearchResult.Ambiguous ->
                        return StepResult.Fatal(
                            "${retry.count} matches for the chat search in $label — be more specific.",
                        )
                    is ChatSearchResult.None ->
                        return StepResult.Fatal("I couldn't find a way to search chats in $label.")
                }
            }
        }
        val tapToken = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Tap the chat search in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't tap the chat search.")
        when (val tapped = tapFresh(affordance.element, tapToken)) {
            is StepResult.Ok -> Unit
            else -> return tapped
        }
        if (affordance.isField) return waitForFocus()
        val field = poll(timeouts.fieldAppearMs) {
            safeSnapshot()?.elements?.firstOrNull {
                isMessageFieldRole(it) && it.enabled && !it.bounds.isEmpty
            }
        } ?: return StepResult.Recoverable("tapped chat search but no search field appeared")
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

    private sealed interface ChatSearchResult {
        data class Found(val affordance: Affordance) : ChatSearchResult
        data class Ambiguous(val count: Int) : ChatSearchResult
        data object None : ChatSearchResult
    }

    private data class Affordance(val element: ScreenElement, val isField: Boolean)

    private fun findChatSearchAffordance(): ChatSearchResult {
        val snap = safeSnapshot() ?: return ChatSearchResult.None
        val live = snap.elements.filter { it.enabled && !it.bounds.isEmpty }
        val mentionsSearch: (ScreenElement) -> Boolean = {
            (it.text?.contains("search", ignoreCase = true) == true) ||
                (it.contentDescription?.contains("search", ignoreCase = true) == true) ||
                (it.hintText?.contains("search", ignoreCase = true) == true)
        }
        val mentionsStartChat: (ScreenElement) -> Boolean = {
            val hay = listOfNotNull(it.text, it.contentDescription)
                .joinToString(" ").lowercase()
            hay.contains("start chat") || hay.contains("new chat") ||
                hay.contains("compose") || hay.contains("new message")
        }
        val tierA = live.filter { isMessageFieldRole(it) && mentionsSearch(it) }
        if (tierA.size == 1) return ChatSearchResult.Found(Affordance(tierA[0], isField = true))
        if (tierA.size > 1) return ChatSearchResult.Ambiguous(tierA.size)
        val tierB = live.filter {
            isMessageFieldRole(it) &&
                it.text.isNullOrBlank() && it.contentDescription.isNullOrBlank() &&
                it.hintText.isNullOrBlank()
        }
        if (tierB.size == 1) return ChatSearchResult.Found(Affordance(tierB[0], isField = true))
        if (tierB.size > 1) return ChatSearchResult.Ambiguous(tierB.size)
        val tierC = live.filter { !isMessageFieldRole(it) && mentionsSearch(it) }
        if (tierC.size == 1) return ChatSearchResult.Found(Affordance(tierC[0], isField = false))
        if (tierC.size > 1) return ChatSearchResult.Ambiguous(tierC.size)
        val tierD = live.filter { !isMessageFieldRole(it) && mentionsStartChat(it) }
        if (tierD.size == 1) return ChatSearchResult.Found(Affordance(tierD[0], isField = false))
        if (tierD.size > 1) return ChatSearchResult.Ambiguous(tierD.size)
        return ChatSearchResult.None
    }

    /** Polls until a text field holds input focus. */
    private fun waitForFocus(): StepResult {
        val focused = poll(timeouts.focusMs) {
            if (safeHasFocus()) true else null
        }
        return if (focused == true) StepResult.Ok
        else StepResult.Recoverable("the field never took focus")
    }

    // ---------- step: select the chat row ----------

    /**
     * The FIRST live-filtered row matching the contact name. Exact-name
     * matches win (see [findContactLabels]); >1 distinct matching name
     * is ambiguous — encoded as a Fatal whose reason starts with
     * "AMBIGUOUS_CONTACT:" so [execute] can map it to
     * [MessageOutcome.AmbiguousContact] instead of a plain failure.
     * The row is re-resolved fresh before the tap (TOCTOU).
     */
    private fun selectContactRow(
        pkg: String,
        label: String,
        recipient: String,
        origin: RequestOrigin,
    ): StepResult {
        val rows = poll(timeouts.rowsMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            val found = findContactLabels(snap, recipient)
            if (found.isEmpty()) null else found
        }
        if (rows == null) {
            val scrollToken = tokens.tokenFor(
                MessageAuthAction(MessageActionKeys.DEVICE_SCROLL, "Scroll the screen"),
                origin,
            )
            if (scrollToken != null) safeScroll(true, scrollToken)
            val retry = safeSnapshot()?.let { findContactLabels(it, recipient) }
                ?.takeIf { it.isNotEmpty() }
            if (retry == null) {
                return StepResult.Fatal(
                    "I couldn't find \"$recipient\" in $label — check the name and try again.",
                )
            }
            return selectContactRowFromLabels(retry, pkg, label, recipient, origin)
        }
        return selectContactRowFromLabels(rows, pkg, label, recipient, origin)
    }

    private fun selectContactRowFromLabels(
        labels: List<String>,
        pkg: String,
        label: String,
        recipient: String,
        origin: RequestOrigin,
    ): StepResult {
        if (labels.size > 1) {
            return StepResult.Fatal(
                "AMBIGUOUS_CONTACT:" + labels.joinToString("|"),
            )
        }
        val targetLabel = labels[0]
        val snap = safeSnapshot()
            ?: return StepResult.Recoverable("screen went unreadable before opening the chat")
        val row = snap.elements.firstOrNull {
            it.enabled && !it.bounds.isEmpty && !isMessageFieldRole(it) &&
                it.label() == targetLabel
        } ?: return StepResult.Recoverable("the chat row for \"$recipient\" disappeared")
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Open the chat with $targetLabel in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't open the chat.")
        return tapFresh(row, token)
    }

    // ---------- step: focus the compose field ----------

    /**
     * The compose field is the field whose hint asks for a message
     * (WhatsApp's hint is "Message"; Messages' is a "Text message"-style
     * hint — both contain "message"), else a single bare field.
     * Device-unknown: whether the hint is exposed, and what other
     * fields share the chat screen.
     */
    private fun focusComposeField(pkg: String, label: String, origin: RequestOrigin): StepResult {
        val field = findComposeField(safeSnapshot())
            ?: return StepResult.Fatal("I couldn't find the message field in $label.")
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.TAP_GUIDANCE,
                "Tap the message field in $label", pkg, label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't tap the message field.")
        return when (val tapped = tapFresh(field, token)) {
            is StepResult.Ok -> waitForFocus()
            else -> tapped
        }
    }

    private fun findComposeField(snap: ScreenModel?): ScreenElement? {
        if (snap == null) return null
        val live = snap.elements.filter {
            isMessageFieldRole(it) && it.enabled && !it.bounds.isEmpty
        }
        val hinted = live.filter {
            it.hintText?.contains("message", ignoreCase = true) == true ||
                it.hintText?.contains("text", ignoreCase = true) == true
        }
        if (hinted.size == 1) return hinted[0]
        if (hinted.size > 1) return null
        val bare = live.filter {
            it.text.isNullOrBlank() && it.contentDescription.isNullOrBlank() &&
                it.hintText.isNullOrBlank()
        }
        if (bare.size == 1) return bare[0]
        if (live.size == 1) return live[0]
        return null
    }

    // ---------- step: verify the typed text ----------

    /**
     * Exact-words check 2: after typing, the focused field should show
     * the exact text. When the field exposes its text and it does NOT
     * contain it, the type did not land — fail honestly. When the field
     * exposes no text, there is nothing to check — proceed, don't fake
     * a failure (recorded as unverified by the caller path).
     */
    private fun verifyTypedText(expected: String): MessageOutcome? {
        val seen = poll(timeouts.typeVerifyMs) {
            val snap = safeSnapshot() ?: return@poll null
            val field = snap.elements.firstOrNull {
                isMessageFieldRole(it) && it.enabled && !it.bounds.isEmpty
            } ?: return@poll null
            val text = field.text
            when {
                text == null -> true // unreadable field: nothing to verify
                text.contains(expected, ignoreCase = true) -> true
                else -> false // field exposes text and it is not the text
            }
        }
        return if (seen == false) {
            MessageOutcome.Failed("I typed the text, but it didn't appear in the field.")
        } else {
            null
        }
    }

    // ---------- step: send ----------

    /**
     * The send tap. The send button is re-found FRESH at this step —
     * after typing it replaces the mic button, so the mic button's
     * identity must never be reused. The tap consumes a FRESH
     * `message.send` token (requested here, never cached) via the
     * `sendTap` sink; the card shows recipient + exact text.
     */
    private fun sendMessage(
        pkg: String,
        label: String,
        recipient: String,
        exactText: String,
        origin: RequestOrigin,
    ): StepResult {
        val snap = safeSnapshot()
            ?: return StepResult.Recoverable("screen went unreadable before sending")
        val sendButton = findSendButton(snap)
            ?: return StepResult.Fatal("I couldn't find the send button in $label.")
        val token = tokens.tokenFor(
            MessageAuthAction(
                MessageActionKeys.MESSAGE_SEND,
                "Send this message to $recipient",
                pkg, label, recipient, exactText,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't send the message.")
        return when (val sent = safeSendTap(sendButton, token)) {
            MessageGestureAcceptance.Accepted -> StepResult.Ok
            is MessageGestureAcceptance.Refused ->
                StepResult.Fatal("Send refused: ${sent.reason}")
        }
    }

    /**
     * The send affordance: a non-field element whose text or
     * content-description mentions "send" (WhatsApp's icon-only button
     * carries contentDescription "Send"; Messages' is an arrow icon /
     * "Send" text). Exactly one must match — more than one refuses
     * instead of guessing. Device-unknown: which form each app
     * exposes.
     */
    private fun findSendButton(snap: ScreenModel): ScreenElement? {
        val buttons = snap.elements.filter {
            it.enabled && !it.bounds.isEmpty && !isMessageFieldRole(it) &&
                ((it.text?.contains("send", ignoreCase = true) == true) ||
                    (it.contentDescription?.contains("send", ignoreCase = true) == true))
        }
        return if (buttons.size == 1) buttons[0] else null
    }

    // ---------- step: verify sent ----------

    /**
     * Polls for a sent bubble containing the exact text; failing that,
     * for the compose field having cleared. A bubble sighting is full
     * verification. Field-cleared with no readable bubble is honest
     * PARTIAL verification — the success detail says so plainly, never
     * "sent" as if the bubble were seen. (Ticks are almost certainly
     * not exposed in the tree.) If the text is still sitting in the
     * field, the send did not go — fail honestly.
     */
    private fun verifySent(
        pkg: String,
        label: String,
        recipient: String,
        exactText: String,
    ): MessageOutcome {
        val seen = poll(timeouts.sentMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            val bubble = snap.elements.any {
                !isMessageFieldRole(it) &&
                    (it.text?.contains(exactText, ignoreCase = true) == true)
            }
            if (bubble) return@poll "bubble"
            val field = findComposeField(snap)
            val cleared = field == null || field.text.isNullOrBlank()
            if (cleared) "cleared" else null
        }
        return when (seen) {
            "bubble" -> MessageOutcome.Success(
                "Sent \"$exactText\" to $recipient in $label — the message is visible in the chat.",
            )
            "cleared" -> MessageOutcome.Success(
                "Sent \"$exactText\" to $recipient in $label. " +
                    "The message field cleared, but the sent bubble wasn't readable " +
                    "in the tree, so this is the field-cleared check — please confirm " +
                    "on the recipient's phone if it matters.",
            )
            else -> MessageOutcome.Failed(
                "I tapped send, but I couldn't confirm the message went — " +
                    "the text is still in the message field.",
            )
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

    /** TOCTOU sameness — mirrors ActionQueue.sameTarget (24px centre, 2x size). */
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

    private fun safeBack(token: String?): MessageGestureAcceptance = try {
        gestures.back(token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "back threw")
    }

    private fun safeSendTap(el: ScreenElement, token: String?): MessageGestureAcceptance = try {
        gestures.sendTap(el, token)
    } catch (e: Exception) {
        MessageGestureAcceptance.Refused(e.message ?: "send tap threw")
    }
}

/**
 * The entry point the speech worker calls:
 * `MessageExecutor.execute(channel, recipient, text)`.
 *
 * Seams are installed once at startup by MessageExecutorWiring
 * (platform:services) — until then [execute] fails honestly instead
 * of dispatching through unwired seams. [execute] BLOCKS on
 * confirmation cards: call it off the main thread.
 */
object MessageExecutor {
    @Volatile
    private var sequence: MessageSequence? = null

    fun configure(
        apps: MessageAppResolver,
        tree: MessageTreeSource,
        gestures: MessageGestures,
        tokens: MessageTokenProvider,
        clock: MessageClock = MessageClock.SYSTEM,
        sleeper: MessageSleeper = MessageSleeper.THREAD,
        timeouts: MessageTimeouts = MessageTimeouts(),
        log: MessageLogger = MessageLogger {},
    ) {
        sequence = MessageSequence(apps, tree, gestures, tokens, clock, sleeper, timeouts, log)
    }

    /** Test seam: install a prebuilt sequence directly. */
    fun configureSequence(seq: MessageSequence) {
        sequence = seq
    }

    fun execute(
        channel: MessageChannel,
        recipient: String,
        text: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
    ): MessageOutcome =
        sequence?.execute(channel, recipient, text, origin)
            ?: MessageOutcome.Failed(
                "Messaging is not set up yet — the device-control service isn't running.",
            )
}
