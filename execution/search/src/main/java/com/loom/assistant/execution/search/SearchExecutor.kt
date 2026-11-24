package com.loom.assistant.execution.search

import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenDataPolicy
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.execution.screenawareness.SearchScreenPredicates
import com.loom.assistant.execution.screenawareness.StopLimits
import com.loom.assistant.execution.screenawareness.SubmitVerification
import com.loom.assistant.execution.screenawareness.TargetAliases
import com.loom.assistant.execution.screenawareness.VideoPresence
import com.loom.assistant.core.common.PlanStopRegistry
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.core.common.SubmitMechanism

/**
 * Master implementation §4 + §5 — the in-app search sequence.
 *
 * `search for <query> in <app>`, executed as one observed sequence that
 * reuses the existing pieces (research report §4 gap map):
 *
 *   resolve app ([AppResolver]) → launch (existing `app.launch` sink) →
 *   wait for foreground + readable tree (poll, `verifyLanding` pattern) →
 *   find search affordance (tiered, multi-match refuses like
 *   GuidanceSession B3) → tap → wait-for-focus (NEW: poll `findFocus`) →
 *   type (wires existing `typeText()` — first caller) → submit (the
 *   submit chain: IME Enter → keyboard action key → suggestion tap →
 *   on-screen search button, each verified — never dispatch-only) →
 *   inspect results → select the first matching result →
 *   verify the outcome on the updated screen.
 *
 * Honesty rules (throughout):
 *  - NEVER report success from dispatch alone: every step verifies against
 *    a fresh snapshot (focus asserted, typed text observed when readable,
 *    results present, landing changed after the result tap).
 *  - Targets are re-identified by identity on every step — never stale
 *    coordinates or cached bounds (TOCTOU re-resolution, same 24px
 *    tolerance as ActionQueue).
 *  - On a recoverable failure the step is re-resolved and retried ONCE;
 *    a second failure fails honestly with the reason. Non-recoverable
 *    failures (app not found, sensitive app, token denied, dispatch
 *    refused by the gate) fail immediately with no retry.
 *  - The original objective (app + query) is carried across every step
 *    and every retry; a retry never invents a new target.
 *  - Sensitive apps are guidance-only: the resolved package is checked
 *    BEFORE any token is requested, and every gesture sink re-checks the
 *    foreground package itself. No token is ever spent on a refused action.
 *
 * Threading: [SearchSequence.execute] BLOCKS — token acquisition waits on
 * David's confirmation card (up to the gate's 90s pending expiry). Call it
 * off the main thread (the speech worker calls from a background thread).
 *
 * The entry point the speech worker calls is [SearchExecutor.execute];
 * [SearchSequence] is the same logic with constructor-injected seams so
 * JVM tests can drive it with fakes.
 */

// ---------- outcomes (exact API the speech worker calls) ----------

/**
 * Universal Console (2026-11-07) — what a search is FOR.
 *
 * Before this, the sequence ALWAYS opened the first result: a plain
 * search ("Search YouTube for Burna Boy") opened a video it was
 * never asked to open, and a play plan then tapped the result a
 * second time. [SHOW_RESULTS] stops at the verified results list;
 * [OPEN_FIRST_RESULT] opens the first result after the results are
 * verified (a play). The goal travels on the plan step
 * ([PlanParams.SEARCH_GOAL]) and through [SearchExecutor.execute];
 * the default keeps the old behaviour for callers that never name
 * a goal.
 */
enum class SearchGoal {
    SHOW_RESULTS,
    OPEN_FIRST_RESULT;

    companion object {
        fun fromParam(value: String?): SearchGoal = when (value?.lowercase()?.trim()) {
            "show_results" -> SHOW_RESULTS
            "open_first", null, "" -> OPEN_FIRST_RESULT
            else -> OPEN_FIRST_RESULT
        }
    }
}

sealed class SearchOutcome {
    /** The query ran and the first matching result was opened. */
    data class Success(val detail: String) : SearchOutcome()

    /** Honest failure — [reason] is what David is told, verbatim. */
    data class Failed(val reason: String) : SearchOutcome()

    /** No installed app matches the name. */
    data object AppNotFound : SearchOutcome()

    /** Several apps match — David must pick one. */
    data class Ambiguous(val candidates: List<String>) : SearchOutcome()
}

// ---------- injected seams ----------

/** App resolution. Production: Worker 1's shared AppCatalogue.resolveByName. */
sealed class AppResolution {
    data class Found(val packageName: String, val label: String) : AppResolution()
    data object NotFound : AppResolution()
    data class Ambiguous(val candidates: List<String>) : AppResolution()
}

fun interface AppResolver {
    fun resolve(name: String): AppResolution
}

/**
 * Live-tree reads. Production: ScreenAwarenessHolder.current for
 * snapshot/findAll, LoomAccessibilityService.hasInputFocus for focus.
 */
interface SearchTreeSource {
    fun snapshot(): ScreenModel?
    /** True when a text field currently holds input focus. */
    fun hasInputFocus(): Boolean
}

/** One gesture acceptance — mirrors GestureResult without the Android module. */
sealed class GestureAcceptance {
    data object Accepted : GestureAcceptance()
    data class Refused(val reason: String) : GestureAcceptance()
}

/**
 * Gesture sink. Production: LoomAccessibilityService extensions
 * (launchApp / requestTap / typeText / submitSearch / scrollScreen /
 * goBack) — each consumes its own single-use token inside the atomic
 * consume+dispatch boundary, so a token passed here is spent by the sink.
 */
interface SearchGestures {
    fun launch(packageName: String, token: String?): GestureAcceptance
    fun tap(element: ScreenElement, token: String?): GestureAcceptance
    fun type(text: String, token: String?): GestureAcceptance
    fun submit(token: String?): GestureAcceptance

    /**
     * App-aware execution (2026-11-07, David's directive §1) — submit
     * mechanism 2: tap the keyboard's own Search / Go / Done key
     * (LoomAccessibilityGestures.submitSearchViaKeyboardKey). Same
     * `device.submit` action class, same token shape. Default refuses
     * honestly so existing fakes and hosts without the key path keep
     * compiling — the chain treats Refused as "mechanism unavailable,
     * try the next one", never as a failure.
     */
    fun submitViaKey(token: String?): GestureAcceptance =
        GestureAcceptance.Refused("Keyboard action key not available.")

    fun scroll(forward: Boolean, token: String?): GestureAcceptance
    fun back(token: String?): GestureAcceptance
}

/**
 * What the executor asks the gate for. [key] is the registry action key;
 * [label] is the human-readable instruction shown on the confirmation
 * card (tap actions) or the action title. The production wiring
 * (SearchExecutorWiring) maps each key to the real AuthAction in
 * AuthActionRegistry — this module never imports the gate, so it stays
 * JVM-testable without the gate's dependency tree.
 *
 * Device-evidence fix (2026-11-05, items 4+6): [scope] carries the
 * searched app's package name and [appLabel] its display label, so the
 * wiring can build the SCOPED factory action (grant offer + stored
 * grants apply) instead of the static blank-scope action. Both default
 * to "" — callers that omit them keep the old unscoped behaviour.
 */
data class SearchAuthAction(
    val key: String,
    val label: String,
    val scope: String = "",
    val appLabel: String = "",
)

/** Registry action keys, mirrored from AuthActionRegistry — keep in sync. */
object SearchActionKeys {
    const val APP_LAUNCH = "app.launch"
    const val TAP_GUIDANCE = "tap.guidance"
    const val DEVICE_TYPE = "device.type"
    const val DEVICE_SUBMIT = "device.submit"
    const val DEVICE_SCROLL = "device.scroll"
    const val DEVICE_BACK = "device.back"
}

/**
 * Token acquisition. Production: a blocking AuthGate.request (see
 * SearchExecutorWiring) — null when David declines, the card times out,
 * or the gate is unavailable. Never cached: every attempt requests a
 * fresh single-use token.
 */
fun interface TokenProvider {
    fun tokenFor(action: SearchAuthAction): String?

    /**
     * Origin-aware form. Default implementation delegates to the 1-arg
     * form so existing fakes keep working; the production provider
     * (BlockingGateTokenProvider) overrides this and passes [origin]
     * through to AuthGate.request so DIRECT_USER grants apply.
     */
    fun tokenFor(action: SearchAuthAction, origin: RequestOrigin): String? = tokenFor(action)
}

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

fun interface Sleeper {
    fun sleep(ms: Long)

    companion object {
        val THREAD: Sleeper = Sleeper { Thread.sleep(it) }
    }
}

data class SearchTimeouts(
    val foregroundMs: Long = 10_000,
    val fieldAppearMs: Long = 5_000,
    val focusMs: Long = 5_000,
    /**
     * MASTER Wave 3 (F8) — lengthened from 2 s: a slow field (or a
     * slow tree refresh after the type) needs longer to show the
     * typed text, and the type step now re-verifies against the
     * SAME field it typed into instead of the first field on screen.
     */
    val typeVerifyMs: Long = 4_000,
    val resultsMs: Long = 10_000,
    val landingMs: Long = 5_000,
    val pollIntervalMs: Long = 250,
    /**
     * App-aware execution (2026-11-07): settle wait after each submit
     * dispatch before the chain re-snapshots and verifies. The search
     * needs a moment to run — verifying instantly would read the
     * pre-submit screen and call an executed search NotExecuted.
     */
    val submitSettleMs: Long = 750,
)

/**
 * Per-step search logging. Production: android.util.Log via
 * SearchExecutorWiring; JVM tests use the no-op default. Never logs
 * screen content — only step names and outcomes.
 */
fun interface SearchLogger {
    fun log(message: String)
}

/**
 * The search sequence with constructor-injected seams. The speech worker
 * calls [SearchExecutor.execute] instead — same behaviour, wired seams.
 */
class SearchSequence(
    private val apps: AppResolver,
    private val tree: SearchTreeSource,
    private val gestures: SearchGestures,
    private val tokens: TokenProvider,
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.THREAD,
    private val timeouts: SearchTimeouts = SearchTimeouts(),
    private val log: SearchLogger = SearchLogger {},
    /**
     * App-aware execution (2026-11-07): the submit mechanism order
     * for this sequence. Defaults to [SubmitChain.DEFAULT_ORDER];
     * [SearchExecutor.execute] may override it per call (the plan
     * executor's TRY_ALTERNATIVE retry passes the remaining order).
     * Mutable so one configured sequence can serve per-call orders.
     */
    var submitMechanisms: List<SubmitMechanism> = SubmitChain.DEFAULT_ORDER,
    /**
     * AOIE Phase 6 — the app's LEARNED submit order (from
     * AppProfileStore.preferredOrder), consulted per call when no
     * per-call order was passed. Before this only the plan path
     * read profiles; the standalone search path always used the
     * default order. Null = default order (tests, unwired hosts).
     */
    private val submitOrderProvider: ((packageName: String) -> List<SubmitMechanism>)? = null,
    /**
     * AOIE Phase 6 — the app's search-entry hints (from
     * AppProfile.searchEntryHints, stored since the seeds and
     * never read until now). Used as the first affordance tier:
     * a field whose hint/text is exactly a known entry hint for
     * this app. Null = no hints (tier skipped).
     */
    private val searchEntryHints: ((packageName: String) -> List<String>)? = null,
    /**
     * MASTER F7b — stop signal for this sequence. Default reads the
     * shared [PlanStopRegistry] (voice "stop", notification stop,
     * Stop-window stop, shutdown all set it); tests inject a flag.
     * Checked at every step boundary — before launch, before focus,
     * before type, before submit, before the result tap — and the
     * sequence aborts honestly with Failed("Stopped."). A stop
     * landing during a parked card wait is caught at the next
     * boundary (the token wait itself is not interruptible here).
     */
    private val isStopped: () -> Boolean = { PlanStopRegistry.isStopped() },
) {

    companion object {
        /** Re-identification tolerance — mirrors ActionQueue.TOCTOU_TOLERANCE_PX. */
        const val TOCTOU_TOLERANCE_PX = 24
        /** Query terms shorter than this are ignored for result matching. */
        const val MIN_TERM_LENGTH = 2
    }

    // ---------- step results (§5: recover once, else fail honestly) ----------

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

    private fun StepResult.toOutcome(): SearchOutcome = when (this) {
        is StepResult.Recoverable -> SearchOutcome.Failed(reason)
        is StepResult.Fatal -> SearchOutcome.Failed(reason)
        is StepResult.Ok -> error("Ok step has no failure outcome")
    }

    /**
     * MASTER F7b — a stop at a step boundary aborts the sequence
     * honestly. Returns the Failed outcome when stopped, null
     * otherwise; callers `return` it immediately.
     */
    private fun stoppedOutcome(): SearchOutcome? =
        if (isStopped()) {
            SearchOutcome.Failed("Stopped.").also { log.log("search: stopped at a step boundary") }
        } else {
            null
        }

    // ---------- entry ----------

    fun execute(
        appName: String,
        query: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
        /**
         * Universal Console (2026-11-07): what the search is for —
         * stop at the results list, or open the first result.
         * Defaults to [SearchGoal.OPEN_FIRST_RESULT] (the old
         * behaviour) so existing callers keep working.
         */
        goal: SearchGoal = SearchGoal.OPEN_FIRST_RESULT,
    ): SearchOutcome {
        val app = appName.trim()
        val q = query.trim()
        if (app.isBlank()) return SearchOutcome.Failed("Which app should I search in?")
        if (q.isBlank()) return SearchOutcome.Failed("What should I search for?")
        log.log("search: start app=\"$app\" query=\"$q\" goal=$goal")
        // Resolve — no token touched yet.
        val resolved = try {
            apps.resolve(app)
        } catch (e: Exception) {
            log.log("search: resolve threw: ${e.message}")
            return SearchOutcome.Failed("Could not look up \"$app\": ${e.message}")
        }
        val (pkg, label) = when (resolved) {
            is AppResolution.NotFound -> {
                log.log("search: app not found: \"$app\"")
                return SearchOutcome.AppNotFound
            }
            is AppResolution.Ambiguous -> {
                log.log("search: app ambiguous: \"$app\" -> ${resolved.candidates}")
                return SearchOutcome.Ambiguous(resolved.candidates)
            }
            is AppResolution.Found -> resolved.packageName to resolved.label
        }
        log.log("search: resolved \"$app\" -> $label ($pkg)")
        // AOIE Phase 6: the standalone path now reads the learned
        // order too — a per-call order (plan TRY_ALTERNATIVE retry)
        // still wins, then the profile's learned order, then the
        // configured default. The configured field is updated so
        // the submit step below uses the resolved order.
        if (submitMechanisms == SubmitChain.DEFAULT_ORDER) {
            submitOrderProvider?.invoke(pkg)?.takeIf { it.isNotEmpty() }?.let {
                submitMechanisms = it
            }
        }
        // Sensitive pre-check BEFORE any token request (§4 hard rule).
        if (ScreenDataPolicy.isSensitivePackage(pkg)) {
            log.log("search: sensitive package refused: $pkg")
            return SearchOutcome.Failed(
                "That's a banking, financial, or authenticator app, so I can only " +
                    "guide you to its search — I can't search inside it for you.",
            )
        }
        // Launch — scoped (item 4): the wiring builds the scoped factory
        // action from the package + label, so the launch card offers
        // "Always allow opening <label>" and a stored grant suppresses it.
        // MASTER F7b — stop before the launch token.
        stoppedOutcome()?.let { return it }
        val launchToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.APP_LAUNCH, "Open $label", pkg, label), origin)
            ?: return SearchOutcome.Failed("Not confirmed — I didn't open $label.").also {
                log.log("search: launch token denied for $label")
            }
        when (val launched = safeLaunch(pkg, launchToken)) {
            is GestureAcceptance.Refused -> {
                log.log("search: launch refused for $label: ${launched.reason}")
                return SearchOutcome.Failed("I couldn't open $label: ${launched.reason}")
            }
            GestureAcceptance.Accepted -> log.log("search: launched $label")
        }
        // Wait for foreground + readable tree (verifyLanding pattern).
        // The poll RETURNS the snapshot it accepted — it is reused
        // as the pre-search baseline below, so no second snapshot is
        // spent here (the stale-coordinate fixtures count snapshots;
        // an extra one shifts which model the affordance find sees).
        val beforeSearch = poll(timeouts.foregroundMs) {
            val snap = safeSnapshot()
            if (snap != null && snap.packageName == pkg && snap.elements.isNotEmpty()) snap else null
        } ?: return SearchOutcome.Failed(
            "$label did not come to the foreground — the screen is not readable.",
        ).also { log.log("search: foreground wait timed out for $label") }
        log.log("search: $label foreground, tree readable")
        // Universal Console (2026-11-07): the pre-search screen —
        // results are judged against it (new rows the search
        // produced), so typed text in the box can never count as
        // results. It is the foreground snapshot above, not a fresh
        // read — the results-ready pre-check reads the SAME screen
        // the app opened onto.
        // MASTER Wave 3 (F14a) — results-ready predicate as a hard
        // stop, checked BEFORE any re-type, re-tap or re-submit. If
        // results matching the query are already on screen, the
        // search step is COMPLETE — never re-type the query, re-tap
        // the suggestion, or re-submit. A plain search returns at
        // once ("results already showing"); a play search skips
        // straight to selecting a result. The canonical predicate
        // already excludes a suggestions screen and an open input,
        // so no baseline comparison is needed here.
        val alreadyShowing = SearchScreenPredicates.resultsPresent(beforeSearch, q)
        if (alreadyShowing) {
            log.log("search: results already showing for \"$q\" in $label — skipping type/submit")
            if (goal == SearchGoal.SHOW_RESULTS) {
                return SearchOutcome.Success(
                    "Results for \"$q\" are already showing in $label.",
                )
            }
        }
        if (!alreadyShowing) {
            // Focus the search field (affordance → tap → focus, one recovery).
            // MASTER F7b — stop before the focus step.
            stoppedOutcome()?.let { return it }
            val focusResult = runStep { focusSearchField(pkg, label, origin) }
            if (focusResult !is StepResult.Ok) {
                log.log("search: focus step failed for $label: ${stepReason(focusResult)}")
                return focusResult.toOutcome()
            }
            log.log("search: search field focused in $label")
            // MASTER Wave 3 (F8) — capture the field's identity at
            // focus time. The type step verifies against THIS field,
            // not whatever holds FOCUS_INPUT at dispatch (a blocking
            // token wait sits between focus and type, and focus can
            // move — the video's "text didn't appear in the focused
            // field" failure).
            val focusedFieldId = safeSnapshot()?.elements
                ?.firstOrNull { it.focused && isFieldRole(it) }?.id
            // Type the query into the focused field (scoped: item 6),
            // with one re-focus + re-type retry on verification
            // failure. MASTER F7b — stop before the type token.
            stoppedOutcome()?.let { return it }
            val typeFailure = typeQueryWithRetry(pkg, label, q, origin, focusedFieldId)
            if (typeFailure != null) {
                log.log("search: typed-text verification failed")
                return typeFailure
            }
            log.log("search: typed text verified")
            // Submit (the submit chain: IME Enter → keyboard key →
            // suggestion tap → on-screen button, verified after each).
            // MASTER F7b — stop before the submit step.
            stoppedOutcome()?.let { return it }
            val submitResult = runStep { submitSearch(pkg, label, q, origin) }
            if (submitResult !is StepResult.Ok) {
                log.log("search: submit step failed: ${stepReason(submitResult)}")
                return submitResult.toOutcome()
            }
            log.log("search: submitted")
        }
        // Universal Console (2026-11-07): a plain search STOPS
        // here, at the verified results list — it never taps a
        // result. The results check is the same evidence bar as
        // PlanVerify.searchResultsShown (new content rows the search
        // produced, or a list with a matching row); the box alone
        // never counts. Only OPEN_FIRST_RESULT goes on to select.
        if (goal == SearchGoal.SHOW_RESULTS) {
            val shown = poll(timeouts.resultsMs) {
                val snap = safeSnapshot() ?: return@poll null
                if (snap.packageName != pkg) return@poll null
                if (resultsShownAfterSubmit(snap, beforeSearch, q)) snap else null
            }
            return if (shown != null) {
                SearchOutcome.Success("Found results for \"$q\" in $label.")
            } else {
                SearchOutcome.Failed("No results matching \"$q\" appeared in $label.")
            }.also { log.log("search: show-results done -> $it") }
        }
        // Inspect results, select the first match, verify the landing.
        // MASTER F7b — stop before the result tap.
        stoppedOutcome()?.let { return it }
        val outcomeHolder = Holder<SearchOutcome>()
        val selectResult = runStep { selectFirstResult(pkg, label, q, outcomeHolder, origin) }
        if (selectResult !is StepResult.Ok) {
            log.log("search: select step failed: ${stepReason(selectResult)}")
            return selectResult.toOutcome()
        }
        val outcome = outcomeHolder.value
            ?: SearchOutcome.Failed("internal error: selection finished without an outcome")
        log.log("search: done -> $outcome")
        return outcome
    }

    /** One-line reason for a failed step, for the per-step log. */
    private fun stepReason(result: StepResult): String = when (result) {
        is StepResult.Fatal -> result.reason
        is StepResult.Recoverable -> result.reason
        is StepResult.Ok -> "ok"
    }

    /** Minimal out-param so runStep closures can return a value with Ok. */
    private class Holder<T>(var value: T? = null)

    // ---------- step 1: focus the search field ----------

    /**
     * Finds the search affordance on the live tree, taps it, and waits for
     * input focus. Re-resolves from the live tree on every attempt (the
     * runStep retry re-runs this whole closure fresh).
     */
    private fun focusSearchField(pkg: String, label: String, origin: RequestOrigin): StepResult {
        val affordance = when (val found = findAffordance(pkg)) {
            is AffordanceResult.Found -> found.affordance
            is AffordanceResult.Ambiguous ->
                return StepResult.Fatal(
                    "${found.count} matches for the search box in $label — be more specific.",
                )
            is AffordanceResult.None -> {
                // One scroll, then re-inspect — the box may be below the fold.
                val scrollToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.DEVICE_SCROLL, "Scroll the screen", pkg, label), origin)
                if (scrollToken != null) safeScroll(true, scrollToken)
                when (val retry = findAffordance(pkg)) {
                    is AffordanceResult.Found -> retry.affordance
                    is AffordanceResult.Ambiguous ->
                        return StepResult.Fatal(
                            "${retry.count} matches for the search box in $label — be more specific.",
                        )
                    is AffordanceResult.None ->
                        return StepResult.Fatal("I couldn't find a search box in $label.")
                }
            }
        }
        val tapToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.TAP_GUIDANCE, "Tap the search box in $label", pkg, label), origin)
            ?: return StepResult.Fatal("Not confirmed — I didn't tap the search box.")
        when (val tapped = tapFresh(affordance.element, tapToken)) {
            is StepResult.Ok -> Unit
            else -> return tapped
        }
        if (affordance.isField) {
            return waitForFocus()
        }
        // A search button: wait for the field it opens.
        // Device-evidence fix (2026-11-05, H1): real apps open a
        // SEARCH_FIELD (SearchView / hinted EditText), not a bare INPUT.
        val field = poll(timeouts.fieldAppearMs) {
            safeSnapshot()?.elements?.firstOrNull { isFieldRole(it) && it.enabled && !it.bounds.isEmpty }
        }
        if (field == null) {
            // The tap may have left the app — Back once, then recover.
            val snap = safeSnapshot()
            if (snap != null && snap.packageName != pkg) {
                val backToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.DEVICE_BACK, "Go back", pkg, label), origin)
                if (backToken != null) safeBack(backToken)
                val restored = poll(timeouts.fieldAppearMs) {
                    val s = safeSnapshot()
                    if (s != null && s.packageName == pkg) s else null
                }
                if (restored == null) {
                    return StepResult.Fatal("Tapping search left $label and I couldn't get back.")
                }
                return StepResult.Recoverable("tapped search but no search field appeared")
            }
            return StepResult.Recoverable("tapped search but no search field appeared")
        }
        val fieldToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.TAP_GUIDANCE, "Tap the search field in $label", pkg, label), origin)
            ?: return StepResult.Fatal("Not confirmed — I didn't tap the search field.")
        return when (val fieldTap = tapFresh(field, fieldToken)) {
            is StepResult.Ok -> waitForFocus()
            else -> fieldTap
        }
    }

    /** Polls until a text field holds input focus (NEW in §4). */
    private fun waitForFocus(): StepResult {
        val focused = poll(timeouts.focusMs) {
            if (safeHasFocus()) true else null
        }
        return if (focused == true) StepResult.Ok
        else StepResult.Recoverable("the search field never took focus")
    }

    // ---------- step 2: type with one re-focus + re-type retry ----------

    /**
     * MASTER Wave 3 (F8) — types [query] into the field resolved at
     * focus time ([fieldId]) and verifies against THAT field. On a
     * verification failure the step recovers ONCE: re-focus the same
     * field (re-resolved from the live tree) and re-type, then
     * re-verify. A second failure fails honestly — the old code had
     * no retry at all, and the video's "text didn't appear in the
     * focused field" ended the whole search. Returns the failure
     * outcome, or null on success.
     */
    private fun typeQueryWithRetry(
        pkg: String,
        label: String,
        query: String,
        origin: RequestOrigin,
        fieldId: String?,
    ): SearchOutcome? {
        val first = typeOnce(pkg, label, query, origin)
        if (first != null) return first
        if (verifyTypedText(query, fieldId) == null) return null
        log.log("search: typed-text verification failed — re-focusing and re-typing once")
        val refocused = refocusField(pkg, label, fieldId, origin)
        if (refocused != null) return refocused
        val second = typeOnce(pkg, label, query, origin)
        if (second != null) return second
        return verifyTypedText(query, fieldId)
    }

    /** One type dispatch (fresh token) + nothing else. */
    private fun typeOnce(
        pkg: String,
        label: String,
        query: String,
        origin: RequestOrigin,
    ): SearchOutcome? {
        val typeToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.DEVICE_TYPE, "Type the search", pkg, label), origin)
            ?: return SearchOutcome.Failed("Not confirmed — I didn't type the search.").also {
                log.log("search: type token denied")
            }
        return when (val typed = safeType(query, typeToken)) {
            is GestureAcceptance.Refused -> {
                log.log("search: type refused: ${typed.reason}")
                SearchOutcome.Failed("I couldn't type the search: ${typed.reason}")
            }
            GestureAcceptance.Accepted -> {
                log.log("search: typed query into field")
                null
            }
        }
    }

    /** Re-focuses the field captured at focus time ([fieldId]), re-resolved live. */
    private fun refocusField(
        pkg: String,
        label: String,
        fieldId: String?,
        origin: RequestOrigin,
    ): SearchOutcome? {
        if (fieldId == null) return null // nothing to re-focus on; the retry just re-types
        val snap = safeSnapshot()
            ?: return SearchOutcome.Failed("The screen went unreadable while re-focusing the search field.")
        val field = snap.elements.firstOrNull { it.id == fieldId && isFieldRole(it) }
            ?: return SearchOutcome.Failed("The search field left the screen while typing.")
        val tapToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.TAP_GUIDANCE, "Tap the search field in $label", pkg, label), origin)
            ?: return SearchOutcome.Failed("Not confirmed — I didn't tap the search field again.")
        return when (val tapped = tapFresh(field, tapToken)) {
            is StepResult.Ok -> when (waitForFocus()) {
                is StepResult.Ok -> null
                else -> SearchOutcome.Failed("The search field never took focus again.")
            }
            is StepResult.Recoverable -> SearchOutcome.Failed("The search field moved while re-focusing.")
            is StepResult.Fatal -> SearchOutcome.Failed("I couldn't tap the search field again.")
        }
    }

    /**
     * After typing, the field resolved at focus time ([fieldId])
     * should show the query. The focused field is the one the
     * keyboard is typing into — reading the first field on screen
     * instead would verify the wrong text when two fields are
     * present. Only when the captured field is gone may it fall
     * back to the focused field, then the first SEARCH_FIELD /
     * INPUT, and the failure reason then says so. When the field
     * exposes no text (some fields don't), there is nothing to
     * check — proceed, don't fake a failure.
     */
    private data class TypedCheck(val ok: Boolean, val fellBack: Boolean)

    private fun verifyTypedText(query: String, fieldId: String? = null): SearchOutcome? {
        val checked = poll(timeouts.typeVerifyMs) {
            val snap = safeSnapshot() ?: return@poll null
            val captured = fieldId?.let { id ->
                snap.elements.firstOrNull { it.id == id && isFieldRole(it) }
            }
            val focused = snap.elements.firstOrNull {
                it.focused && isFieldRole(it) && it.enabled && !it.bounds.isEmpty
            }
            val fellBack = captured == null
            val field = captured
                ?: focused
                ?: snap.elements.firstOrNull {
                    isFieldRole(it) && it.enabled && !it.bounds.isEmpty
                }
                ?: return@poll null
            val text = field.text
            when {
                text == null -> TypedCheck(ok = true, fellBack = fellBack)
                text.contains(query, ignoreCase = true) -> TypedCheck(ok = true, fellBack = fellBack)
                else -> TypedCheck(ok = false, fellBack = fellBack)
            }
        }
        return if (checked?.ok == false) {
            SearchOutcome.Failed(
                if (checked.fellBack) {
                    "I typed the search, but the text didn't appear in the focused field — " +
                        "the field I focused left the screen, so I checked the first search field instead."
                } else {
                    "I typed the search, but the text didn't appear in the focused field."
                },
            )
        } else {
            null
        }
    }

    // ---------- step 3: submit (the submit chain) ----------

    /**
     * Submits through the [SubmitChain]: mechanisms in
     * [submitMechanisms] order, each with a fresh token, a settle
     * wait, a fresh snapshot and a [SubmitVerifier] judgement.
     * Returns Ok ONLY when the chain verified Executed — dispatch
     * alone is never success. NotExecuted fails honestly with the
     * per-mechanism log; Unknown is recoverable (one retry).
     */
    private fun submitSearch(pkg: String, label: String, query: String, origin: RequestOrigin): StepResult {
        // MASTER Wave 3 (F14a) — results-ready check BEFORE any
        // re-submit: if results are already on screen the search
        // step is COMPLETE. Never re-submit a completed search. The
        // canonical predicate already excludes a suggestions screen
        // and an open input, so a typed screen can never pass here.
        val already = safeSnapshot()
        // Device-failure fix (2026-11-13, F2): the pre-submit
        // results-ready check is package-gated — results "already
        // showing" on any other screen (including LOOM's own chat)
        // must never skip the submit.
        if (already != null && SearchScreenPredicates.resultsPresent(already, query, pkg)) {
            log.log("search: results already showing before submit — skipping the submit")
            return StepResult.Ok
        }
        val before = safeSnapshot()
            ?: return StepResult.Fatal("The screen went unreadable before the search ran.")
        val chain = SubmitChain(
            gestures = gestures,
            tokenFor = { mechanism ->
                when (mechanism) {
                    SubmitMechanism.IME_ENTER, SubmitMechanism.KEYBOARD_ACTION_KEY ->
                        tokens.tokenFor(
                            SearchAuthAction(SearchActionKeys.DEVICE_SUBMIT, "Submit the search", pkg, label),
                            origin,
                        )
                    SubmitMechanism.SUGGESTION_TAP ->
                        tokens.tokenFor(
                            SearchAuthAction(SearchActionKeys.TAP_GUIDANCE, "Tap the suggestion to run the search", pkg, label),
                            origin,
                        )
                    SubmitMechanism.SEARCH_BUTTON ->
                        tokens.tokenFor(
                            SearchAuthAction(SearchActionKeys.TAP_GUIDANCE, "Tap the search button", pkg, label),
                            origin,
                        )
                }
            },
            snapshot = { safeSnapshot() },
            tapTarget = { mechanism ->
                val snap = safeSnapshot() ?: return@SubmitChain null
                when (mechanism) {
                    SubmitMechanism.SUGGESTION_TAP -> findSuggestion(snap, query)
                    SubmitMechanism.SEARCH_BUTTON -> findSearchButton(snap)
                    else -> null
                }
            },
            sleeper = sleeper,
            waitAfterDispatchMs = timeouts.submitSettleMs,
        )
        val result = chain.submit(query, submitMechanisms, before)
        val tried = result.attempts.joinToString("; ") { attempt ->
            val dispatchReason = when (val d = attempt.dispatch) {
                GestureAcceptance.Accepted -> "accepted"
                is GestureAcceptance.Refused -> "refused: ${d.reason}"
            }
            "${attempt.mechanism}: $dispatchReason"
        }
        // AOIE Phase 2: the failure names its specific diagnostic
        // (field not found / IME unavailable / dispatched with no
        // transition / …), not just "the search never ran".
        val diagnostic = SubmitDiagnosticClassifier.classify(
            verification = result.verification,
            before = before,
            after = safeSnapshot(),
            dispatchedAny = result.attempts.any { it.dispatch is GestureAcceptance.Accepted },
            expectedPackage = pkg,
        )
        return when (val verification = result.verification) {
            is SubmitVerification.Executed -> StepResult.Ok
            is SubmitVerification.NotExecuted -> StepResult.Fatal(
                "The search never ran — ${verification.reason}. Tried: $tried. " +
                    "Diagnosis: ${diagnostic.label}.",
            )
            is SubmitVerification.Unknown -> StepResult.Recoverable(
                "I couldn't tell whether the search ran. Tried: $tried. " +
                    "Diagnosis: ${diagnostic.label}.",
            )
        }
    }

    /**
     * A suggestion row: the first enabled, non-field element whose
     * text echoes the query. Suggestions only exist while the
     * keyboard is open and the user is typing — a matching row on a
     * keyboard-less screen is a result, not a suggestion, and must
     * never be tapped as one. Tapping a suggestion submits the
     * search — it is a submit mechanism, NOT a result tap (result
     * taps happen only after submit verified Executed).
     */
    private fun findSuggestion(snap: ScreenModel, query: String): ScreenElement? {
        if (query.isBlank() || !snap.keyboardVisible) return null
        return snap.elements.firstOrNull { el ->
            el.enabled && !el.bounds.isEmpty && !isFieldRole(el) &&
                (el.text?.contains(query, ignoreCase = true) == true)
        }
    }

    /**
     * The on-screen search button: exactly one enabled, non-field
     * element labelled "search". Zero or several means no usable
     * button (never guess) — the chain records the mechanism as
     * unavailable. The search field itself is never a button (H6).
     */
    private fun findSearchButton(snap: ScreenModel): ScreenElement? {
        val buttons = snap.elements.filter {
            it.enabled && !it.bounds.isEmpty && !isFieldRole(it) &&
                TargetAliases.mentionsSearch(it)
        }
        return if (buttons.size == 1) buttons[0] else null
    }

    // ---------- step 4: inspect results, select, verify landing ----------

    /**
     * Result-selection rule (documented): the FIRST visible, enabled,
     * non-input element in tree order whose text or content-description
     * contains at least one query term (terms are the whitespace-split
     * query words of length ≥ [MIN_TERM_LENGTH]; a query with no such term
     * matches on the whole query string instead). The search field itself
     * is never a result.
     *
     * MASTER Wave 3 (F8/F14b/F14c/F15c):
     *  - Only runs after VERIFIED results: rows are accepted only
     *    when the screen is NOT a suggestions screen — a suggestion
     *    row echoes the query, so polling findResults on the
     *    suggestions screen used to tap the first suggestion as if
     *    it were a result (the YouTube loop).
     *  - Bounded scroll hunt: after each scroll the newly revealed
     *    rows are scanned for the target BEFORE the next scroll; a
     *    scroll that changes nothing is the end of the list
     *    ("reached the end, not found"); at most
     *    [StopLimits.MAX_SCROLLS_PER_SEARCH] scrolls, then stop
     *    honestly. Never scrolls past the target, never scrolls
     *    indefinitely.
     *  - After the tap, when a video is present on the landing
     *    screen, playback must be verified (playing, not merely
     *    opened) — a search task is not complete until a video is
     *    open and playing (or the user asked only for results).
     */
    private fun selectFirstResult(
        pkg: String,
        label: String,
        query: String,
        out: Holder<SearchOutcome>,
        origin: RequestOrigin,
    ): StepResult {
        var results = pollForVerifiedResults(pkg, query)
        var scrolls = 0
        var lastSignature = safeSnapshot()?.let { SearchScreenPredicates.viewportSignature(it) }
        while (results == null && scrolls < StopLimits.MAX_SCROLLS_PER_SEARCH) {
            stoppedOutcome()?.let { return StepResult.Fatal("Stopped.") }
            val scrollToken = tokens.tokenFor(SearchAuthAction(SearchActionKeys.DEVICE_SCROLL, "Scroll the screen", pkg, label), origin)
            if (scrollToken == null) break
            if (safeScroll(true, scrollToken) is GestureAcceptance.Refused) break
            scrolls++
            val afterScroll = safeSnapshot()
            val signature = SearchScreenPredicates.viewportSignature(afterScroll)
            if (signature == lastSignature) {
                // F14b: a scroll that changes nothing is the end of
                // the list — stop and report honestly.
                log.log("search: scroll changed nothing — end of list after $scrolls scroll(s)")
                return StepResult.Fatal(
                    "No results matching \"$query\" appeared in $label — " +
                        "${StopLimits.REACHED_END_NOT_FOUND}.",
                )
            }
            lastSignature = signature
            // F14c: scan the newly revealed rows for the target
            // BEFORE the next scroll.
            results = pollForVerifiedResults(pkg, query)
        }
        if (results == null) {
            return if (scrolls >= StopLimits.MAX_SCROLLS_PER_SEARCH) {
                StepResult.Fatal(
                    "No results matching \"$query\" appeared in $label — " +
                        StopLimits.MAX_SCROLLS_STOPPING + ".",
                )
            } else {
                StepResult.Fatal("No results matching \"$query\" appeared in $label.")
            }
        }
        val first = results.firstOrNull()
            ?: return StepResult.Fatal("No results matching \"$query\" appeared in $label.")
        val preTap = safeSnapshot()
        val token = tokens.tokenFor(
            SearchAuthAction(
                SearchActionKeys.TAP_GUIDANCE,
                "Open \"${first.label()}\" in $label",
                pkg,
                label,
            ),
            origin,
        ) ?: return StepResult.Fatal("Not confirmed — I didn't open the result.")
        return when (val tapped = tapFresh(first, token)) {
            is StepResult.Ok -> {
                // Verify the outcome on the UPDATED screen: same app
                // foreground, tree readable, and the element set changed
                // vs the pre-tap screen. Dispatch alone is never success.
                val preTapIds = preTap?.elements?.map { it.id }?.toSet() ?: emptySet()
                val changed = poll(timeouts.landingMs) {
                    val snap = safeSnapshot() ?: return@poll null
                    if (snap.packageName != pkg) return@poll null
                    val ids = snap.elements.map { it.id }.toSet()
                    if (ids != preTapIds) true else null
                }
                if (changed != true) {
                    StepResult.Fatal("I tapped the result, but the screen didn't change.")
                } else {
                    // F15c: a video on the landing screen must be
                    // verified PLAYING, not merely opened.
                    verifyPlaybackAfterOpen(pkg, label, query, first, out)
                }
            }
            else -> tapped
        }
    }

    /**
     * Polls for result rows that are VERIFIED results — the screen
     * is not a suggestions screen and no field still holds focus,
     * so a suggestion row can never be picked as a result. This is
     * the SELECTION gate, deliberately weaker than
     * [SearchScreenPredicates.resultsPresent]: the submit was
     * already verified Executed by the chain before selection runs,
     * so the search demonstrably ran and one verified result row is
     * selectable. (The pre-Wave-3 code polled findResults alone;
     * Wave 3 keeps that, plus the suggestions/input exclusion —
     * the one-row results screen in the stale-coordinate tests
     * selects its row here, while the canonical results predicate
     * stays strict for the plan path and the submit verifier.)
     */
    private fun pollForVerifiedResults(pkg: String, query: String): List<ScreenElement>? {
        return poll(timeouts.resultsMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            if (SearchScreenPredicates.isSuggestionsScreen(snap)) return@poll null
            if (snap.elements.any { it.focused && isFieldRole(it) }) return@poll null
            val found = findResults(snap, query)
            if (found.isEmpty()) null else found
        }
    }

    /**
     * F15c — after opening a result, when a video is present on the
     * landing screen, verify it is PLAYING (a Pause control / playing
     * label) within the landing window. A tap alone is not success
     * for a video result. When no video is present (a channel page,
     * an article, …) the landing change itself is the success.
     */
    private fun verifyPlaybackAfterOpen(
        pkg: String,
        label: String,
        query: String,
        first: ScreenElement,
        out: Holder<SearchOutcome>,
    ): StepResult {
        val landing = safeSnapshot()
        if (landing == null || landing.packageName != pkg) {
            return StepResult.Fatal("I tapped the result, but the screen went unreadable.")
        }
        if (!VideoPresence.videoPresent(landing)) {
            out.value = SearchOutcome.Success(
                "Searched \"$query\" in $label and opened \"${first.label()}\".",
            )
            return StepResult.Ok
        }
        val playing = poll(timeouts.landingMs) {
            val snap = safeSnapshot() ?: return@poll null
            if (snap.packageName != pkg) return@poll null
            if (VideoPresence.isPlaying(snap)) true else null
        }
        return if (playing == true) {
            out.value = SearchOutcome.Success(
                "Searched \"$query\" in $label and opened \"${first.label()}\" — it's playing.",
            )
            StepResult.Ok
        } else {
            StepResult.Fatal(
                "I opened \"${first.label()}\" in $label, but the video isn't playing.",
            )
        }
    }

    /**
     * Universal Console (2026-11-07) — the SHOW_RESULTS evidence
     * bar, the same rule as PlanVerify.searchResultsShown, kept
     * local because execution:search must not depend on
     * execution:tasks (the manual build's module graph stays
     * acyclic): at least two NEW content rows appeared versus
     * [before] (the pre-search screen) — a generic query returns
     * rows that need not contain the query words, so new rows are
     * the evidence the search ran and returned content; one new row
     * is a suggestion, not results; the box alone (no new rows) is
     * never results — or the canonical predicate (a list with a
     * matching row, once the input is closed).
     *
     * MASTER Wave 3 (F8/F14a) — a suggestions screen or a spinner
     * is NEVER results here either: the old `list + one matching
     * row` arm passed on the suggestions screen, so a failed submit
     * read as success and `selectFirstResult` tapped a suggestion
     * as if it were a result.
     */
    private fun resultsShownAfterSubmit(after: ScreenModel, before: ScreenModel?, query: String): Boolean {
        if (after.elements.isEmpty()) return false
        if (SearchScreenPredicates.isSuggestionsScreen(after)) return false
        if (SearchScreenPredicates.isLoadingScreen(after, query)) return false
        // New rows count only when they MATCH the query and the
        // input is closed: an unrelated screen change (an error
        // dialog, a tab switch) is not results, and suggestion rows
        // while typing are not results either — the old arm counted
        // ANY two new elements, which passed on a screen that merely
        // changed.
        val inputClosed = !after.keyboardVisible &&
            after.elements.none { it.focused && isFieldRole(it) }
        if (before != null) {
            val beforeIds = before.elements.map { it.id to it.label() }.toSet()
            val newRows = after.elements.count { el ->
                SearchScreenPredicates.matchesQuery(el, query) &&
                    (el.id to el.label()) !in beforeIds
            }
            if (inputClosed && newRows >= 2) return true
            if (newRows == 0) return false
        }
        return SearchScreenPredicates.resultsPresent(after, query)
    }

    private fun findResults(snap: ScreenModel, query: String): List<ScreenElement> {
        val terms = query.lowercase().split(Regex("\\s+"))
            .filter { it.length >= MIN_TERM_LENGTH }
        return snap.elements.filter { el ->
            el.enabled && !el.bounds.isEmpty && !isFieldRole(el) &&
                !isSearchField(el) &&
                // MASTER Wave 3 (F9) — the voice-search mic is never
                // a result, whatever it says.
                !TargetAliases.isVoiceControl(el) &&
                (el.text?.isNotBlank() == true || el.contentDescription?.isNotBlank() == true) &&
                if (terms.isEmpty()) {
                    (el.text?.contains(query, ignoreCase = true) == true) ||
                        (el.contentDescription?.contains(query, ignoreCase = true) == true)
                } else {
                    terms.any { term ->
                        (el.text?.lowercase()?.contains(term) == true) ||
                            (el.contentDescription?.lowercase()?.contains(term) == true)
                    }
                }
        }
    }

    // ---------- affordance resolution ----------

    private sealed interface AffordanceResult {
        data class Found(val affordance: Affordance) : AffordanceResult
        data class Ambiguous(val count: Int) : AffordanceResult
        data object None : AffordanceResult
    }

    private data class Affordance(val element: ScreenElement, val isField: Boolean)

    /**
     * Search-affordance tiers, in priority order (research §4 gap 3 —
     * what a given app's tree actually exposes is still device-unknown,
     * so the tiers are ordered most-specific first):
     *  A. A search field whose text/desc/hint mentions "search" — an
     *     INPUT with a search label, or a SEARCH_FIELD (SearchView, or
     *     an EditText whose hint asks to search: YouTube's "Search
     *     YouTube", Chrome's "Search or type URL").
     *  B. A single bare, empty field (no other field on screen) — the
     *     search field on a search screen.
     *  C. A button/control whose text/desc mentions "search" (search icon).
     * More than one match inside the chosen tier refuses honestly
     * (GuidanceSession B3) instead of guessing.
     */
    private fun findAffordance(packageName: String? = null): AffordanceResult {
        val snap = safeSnapshot() ?: return AffordanceResult.None
        val live = snap.elements.filter { it.enabled && !it.bounds.isEmpty }
        // MASTER Wave 3 (F9) — "search" never resolves to the
        // voice-search mic: TargetAliases.mentionsSearch excludes
        // any element whose description mentions "voice".
        val mentionsSearch: (ScreenElement) -> Boolean = {
            TargetAliases.mentionsSearch(it)
        }
        // AOIE Phase 6 — tier H (first): the app's own entry hints.
        // A field whose hint/text/desc IS a known entry hint for
        // this app (e.g. YouTube's "Search YouTube") is the search
        // field, even when several search-mentioning fields exist.
        val hints = packageName?.let { searchEntryHints?.invoke(it) }.orEmpty()
        if (hints.isNotEmpty()) {
            val tierH = live.filter { el ->
                isFieldRole(el) && listOfNotNull(el.hintText, el.text, el.contentDescription).any { v ->
                    hints.any { h -> v.trim().equals(h.trim(), ignoreCase = true) }
                }
            }
            if (tierH.size == 1) return AffordanceResult.Found(Affordance(tierH[0], isField = true))
            if (tierH.size > 1) return AffordanceResult.Ambiguous(tierH.size)
        }
        val tierA = live.filter { isFieldRole(it) && mentionsSearch(it) }
        if (tierA.size == 1) return AffordanceResult.Found(Affordance(tierA[0], isField = true))
        if (tierA.size > 1) return AffordanceResult.Ambiguous(tierA.size)
        val tierB = live.filter {
            isFieldRole(it) &&
                it.text.isNullOrBlank() && it.contentDescription.isNullOrBlank() &&
                it.hintText.isNullOrBlank()
        }
        if (tierB.size == 1) return AffordanceResult.Found(Affordance(tierB[0], isField = true))
        if (tierB.size > 1) return AffordanceResult.Ambiguous(tierB.size)
        val tierC = live.filter { !isFieldRole(it) && mentionsSearch(it) }
        if (tierC.size == 1) return AffordanceResult.Found(Affordance(tierC[0], isField = false))
        if (tierC.size > 1) return AffordanceResult.Ambiguous(tierC.size)
        return AffordanceResult.None
    }

    /**
     * Device-evidence fix (2026-11-05, H1 + latent H6): a search field
     * is a SEARCH_FIELD by role (SearchView, or a hinted EditText) OR an
     * INPUT whose text/desc/hint mentions "search". The role alone is
     * enough — after typing, the field's text IS the query, so a
     * role-based check is what keeps the typed query from being picked
     * as a "first result" in [findResults].
     */
    private fun isSearchField(el: ScreenElement): Boolean =
        el.role == ElementRole.SEARCH_FIELD ||
            (el.role == ElementRole.INPUT && mentionsSearchText(el))

    /** True for INPUT and SEARCH_FIELD roles — every text-entry field. */
    private fun isFieldRole(el: ScreenElement): Boolean =
        el.role == ElementRole.INPUT || el.role == ElementRole.SEARCH_FIELD

    /** Text/desc/hint mentions "search" — where the hint actually lives. */
    private fun mentionsSearchText(el: ScreenElement): Boolean =
        (el.text?.contains("search", ignoreCase = true) == true) ||
            (el.contentDescription?.contains("search", ignoreCase = true) == true) ||
            (el.hintText?.contains("search", ignoreCase = true) == true)

    // ---------- TOCTOU re-resolution (§5) ----------

    /**
     * Identity of an element for re-identification: class + label + role.
     * Bounds are NOT part of the identity — they are checked separately
     * against [sameTarget] so a layout shift re-resolves instead of
     * tapping stale coordinates.
     */
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

    /**
     * Re-resolves [element] against the LIVE tree immediately before
     * dispatch and taps the fresh element — never the cached bounds.
     * Gone → Recoverable (target-lost); moved beyond tolerance →
     * Recoverable (the runStep retry re-finds it); gate refusal → Fatal.
     */
    private fun tapFresh(element: ScreenElement, token: String?): StepResult {
        val snap = safeSnapshot() ?: return StepResult.Recoverable("screen went unreadable before the tap")
        val identity = identityOf(element)
        val fresh = snap.elements.filter {
            identityOf(it) == identity && it.enabled && !it.bounds.isEmpty
        }
        if (fresh.isEmpty()) return StepResult.Recoverable("target-lost: \"${element.label()}\"")
        if (fresh.size > 1) {
            // Same identity twice — refuse to guess which one.
            return StepResult.Fatal("the target \"${element.label()}\" now matches ${fresh.size} elements — be more specific")
        }
        val target = fresh[0]
        if (!sameTarget(target, element)) {
            return StepResult.Recoverable("target moved: \"${element.label()}\"")
        }
        return when (val tapped = safeTap(target, token)) {
            GestureAcceptance.Accepted -> StepResult.Ok
            is GestureAcceptance.Refused -> StepResult.Fatal("tap refused: ${tapped.reason}")
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

    /** Polls [check] until it returns non-null or [timeoutMs] elapses. */
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

    // ---------- safe seam calls (a throwing seam is a failed step, never a crash) ----------

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

    private fun safeLaunch(pkg: String, token: String?): GestureAcceptance = try {
        gestures.launch(pkg, token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "launch threw")
    }

    private fun safeTap(el: ScreenElement, token: String?): GestureAcceptance = try {
        gestures.tap(el, token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "tap threw")
    }

    private fun safeType(text: String, token: String?): GestureAcceptance = try {
        gestures.type(text, token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "type threw")
    }

    private fun safeScroll(forward: Boolean, token: String?): GestureAcceptance = try {
        gestures.scroll(forward, token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "scroll threw")
    }

    private fun safeBack(token: String?): GestureAcceptance = try {
        gestures.back(token)
    } catch (e: Exception) {
        GestureAcceptance.Refused(e.message ?: "back threw")
    }
}

/**
 * The entry point the speech worker calls: `SearchExecutor.execute(appName, query)`.
 *
 * Seams are installed once at startup by SearchExecutorWiring (platform:
 * services) — until then [execute] fails honestly instead of dispatching
 * through unwired seams. [execute] BLOCKS on confirmation cards: call it
 * off the main thread.
 */
object SearchExecutor {
    @Volatile
    private var sequence: SearchSequence? = null

    fun configure(
        apps: AppResolver,
        tree: SearchTreeSource,
        gestures: SearchGestures,
        tokens: TokenProvider,
        clock: Clock = Clock.SYSTEM,
        sleeper: Sleeper = Sleeper.THREAD,
        timeouts: SearchTimeouts = SearchTimeouts(),
        log: SearchLogger = SearchLogger {},
        submitMechanisms: List<SubmitMechanism> = SubmitChain.DEFAULT_ORDER,
        submitOrderProvider: ((packageName: String) -> List<SubmitMechanism>)? = null,
        searchEntryHints: ((packageName: String) -> List<String>)? = null,
    ) {
        sequence = SearchSequence(
            apps, tree, gestures, tokens, clock, sleeper, timeouts, log,
            submitMechanisms, submitOrderProvider, searchEntryHints,
        )
    }

    /** Test seam: install a prebuilt sequence directly. */
    fun configureSequence(seq: SearchSequence) {
        sequence = seq
    }

    fun execute(
        appName: String,
        query: String,
        origin: RequestOrigin = RequestOrigin.MODEL,
        /**
         * App-aware execution (2026-11-07): per-call submit order
         * (the plan executor's TRY_ALTERNATIVE retry passes the
         * remaining mechanisms). Null keeps the configured order.
         */
        submitMechanisms: List<SubmitMechanism>? = null,
        /**
         * Universal Console (2026-11-07): what the search is for.
         * MASTER Wave 3 (F8) — a null goal now means SHOW_RESULTS:
         * the standalone floating-host search (the path David used)
         * used to default to OPEN_FIRST_RESULT and open a video he
         * never asked to open. The plan path always passes an
         * explicit goal, so it is unaffected; callers that want a
         * result opened pass OPEN_FIRST_RESULT explicitly.
         */
        goal: SearchGoal? = null,
    ): SearchOutcome {
        val seq = sequence
            ?: return SearchOutcome.Failed("Search is not set up yet — the device-control service isn't running.")
        // AOIE Phase 6: a null per-call order RESETS the field to the
        // default, so the sequence resolves the app's learned order
        // fresh (the old code left a previous call's order in the
        // field, so a plan retry's order leaked into later searches).
        seq.submitMechanisms = submitMechanisms ?: SubmitChain.DEFAULT_ORDER
        return if (goal != null) seq.execute(appName, query, origin, goal)
        else seq.execute(appName, query, origin, SearchGoal.SHOW_RESULTS)
    }
}
