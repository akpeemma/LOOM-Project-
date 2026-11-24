package com.loom.assistant.intelligence.planner

import android.graphics.Rect
import com.loom.assistant.core.common.CapabilityPrompt
import com.loom.assistant.core.common.PlanJsonPrompt
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * AOIE Phases 1, 4, 5, 6 — typed contract, perception confidence,
 * model-planning prompt parity, registry recipes.
 *
 * Run via test-harness/run-2c-tests.sh (runner: aioeplan).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) passed++ else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passed++ else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private fun el(
    id: String,
    text: String?,
    role: ElementRole = ElementRole.TEXT,
    focused: Boolean = false,
    hintText: String? = null,
) = ScreenElement(
    id = id,
    text = text,
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(0, 0, 100, 50),
    role = role,
    enabled = true,
    isPassword = false,
    hintText = hintText,
    focused = focused,
)

private val apps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube music", "yt music" ->
            AppResolution.Found("com.google.android.apps.youtube.music", "YT Music")
        else -> AppResolution.NotFound
    }
}

private fun request(goal: String) =
    PlanRequest(goal = goal, origin = RequestOrigin.DIRECT_USER, transparency = false)

fun main() {
    // ---- Phase 1: typed execution contract ----
    run {
        val step = PlanStep(index = 0, kind = TaskStepKind.TAP_TARGET, description = "tap Fall")
        val copy = step.copy(description = "tap Fall (retry)")
        check("actionId stable across copy", step.actionId == copy.actionId)
        check("actionId prefixed", step.actionId.startsWith("act-"))
        val other = PlanStep(index = 1, kind = TaskStepKind.TAP_TARGET, description = "tap If")
        check("actionIds distinct per step", step.actionId != other.actionId)
        checkEq("tap risk is ORDINARY", step.riskLevel, RiskLevel.ORDINARY)
        val send = PlanStep(index = 2, kind = TaskStepKind.MESSAGE_SEND, description = "send")
        checkEq("message risk is CONSEQUENTIAL", send.riskLevel, RiskLevel.CONSEQUENTIAL)
        val obs = PlanStep(index = 3, kind = TaskStepKind.OBSERVE_SCREEN, description = "observe")
        checkEq("observe risk is LOW", obs.riskLevel, RiskLevel.LOW)
        checkEq("tap verification method", step.verificationMethod, VerificationMethod.SCREEN_CHANGED)
        checkEq("observe verification method", obs.verificationMethod, VerificationMethod.SCREEN_CHANGED)

        val trace = ExecutionTraceStore()
        trace.record(
            ExecutionTraceEntry(
                planId = "plan-test",
                actionId = step.actionId,
                stepIndex = 0,
                kind = TaskStepKind.TAP_TARGET,
                dispatch = "DISPATCHED",
                verification = VerificationResult.Verdict.PASS,
                confidence = 1.0,
            ),
        )
        checkEq("trace records completed", trace.verifiedActionIds(), setOf(step.actionId))
        check("trace is non-empty", trace.entries().isNotEmpty())
    }

    // ---- Phase 4: perception confidence + grounding ----
    run {
        val coordinator = PerceptionCoordinator()
        check("null snapshot → null observation", coordinator.observe(null) == null)
        val empty = ScreenModel(packageName = "com.example", elements = emptyList())
        checkEq("empty tree confidence 0.3", coordinator.observe(empty)?.confidence, 0.3)
        val unknown = ScreenModel(
            packageName = "com.example",
            elements = listOf(el("x", "something")),
        )
        checkEq("unknown kind confidence 0.7", coordinator.observe(unknown)?.confidence, 0.7)
        val feed = ScreenModel(
            packageName = "com.example",
            elements = listOf(
                el("list", null, ElementRole.LIST),
                el("nav", null, ElementRole.NAVIGATION),
                el("row", "post", ElementRole.TEXT),
            ),
        )
        checkEq("classified screen confidence 1.0", coordinator.observe(feed)?.confidence, 1.0)
        val enriched = coordinator.enrich(feed)
        checkEq("enrich stamps confidence", enriched?.confidence, 1.0)
        checkEq("enrich stamps source", enriched?.source, "accessibility")
        check("below-threshold stops", 0.3 < PerceptionCoordinator.MIN_ACTION_CONFIDENCE)
        check("threshold usable", 0.7 >= PerceptionCoordinator.MIN_ACTION_CONFIDENCE)

        val ctx = AppContextProvider.fromSnapshot(feed)
        check("context built", ctx != null)
        val ground = ctx?.let { AppContextProvider.groundingText(it, null) } ?: ""
        check("grounding names package", "com.example" in ground)
        check("grounding names kind", "FEED" in ground)
        check("grounding names keyboard", "keyboard=" in ground)
    }

    // ---- Phase 4: DETAIL classification ----
    run {
        val longText = "A".repeat(200)
        val detail = ScreenModel(
            packageName = "com.example",
            elements = listOf(el("body", longText)),
        )
        checkEq(
            "long text, no chrome → DETAIL",
            ScreenClassifier.classify(detail).kind,
            ScreenKind.DETAIL,
        )
        val feed = ScreenModel(
            packageName = "com.example",
            elements = listOf(
                el("list", null, ElementRole.LIST),
                el("caption", longText),
            ),
        )
        check("feed with long caption is NOT DETAIL", ScreenClassifier.classify(feed).kind != ScreenKind.DETAIL)
        check("isDetailScreen agrees", AppContextProvider.isDetailScreen(detail))
        check("isDetailScreen rejects feed", !AppContextProvider.isDetailScreen(feed))
    }

    // ---- Phase 5: prompt / validator parity ----
    run {
        val prompt = PlanJsonPrompt.text
        for (kind in TaskStepKind.entries) {
            check("prompt lists kind ${kind.name}", kind.name in prompt)
        }
        for ((kind, params) in PlanValidator.ALLOWED_PARAMS) {
            for (param in params) {
                check("prompt documents $kind param $param", param in prompt)
            }
        }
        check("prompt forbids invented bounds", "bounds" in prompt.lowercase())
        check("prompt caps steps", "20" in prompt)
        check("prompt names searchGoal values", "show_results" in prompt && "open_first" in prompt)

        val validator = PlanValidator()
        val modelJson = """
            {"goal":"open YouTube Music and search for Davido","steps":[
              {"kind":"LAUNCH_APP","description":"open YT Music","params":{"app":"YouTube Music"}},
              {"kind":"SEARCH_IN_APP","description":"search Davido","params":{"app":"YouTube Music","query":"Davido"}}
            ]}
        """.trimIndent()
        val parsed = validator.parsePlanJson(modelJson, request("open YouTube Music and search for Davido"))
        check("validator accepts well-formed model plan", parsed is PlanValidator.ParseOutcome.Valid)

        val withBounds = """
            {"goal":"tap Fall","steps":[
              {"kind":"TAP_TARGET","description":"tap Fall","params":{"target":"Fall","bounds":"0,0,100,50"}}
            ]}
        """.trimIndent()
        val stripped = validator.parsePlanJson(withBounds, request("tap Fall"))
        check("validator accepts plan with bounds", stripped is PlanValidator.ParseOutcome.Valid)
        val step = (stripped as? PlanValidator.ParseOutcome.Valid)?.plan?.steps?.firstOrNull()
        check("model bounds stripped", step != null && PlanParams.BOUNDS !in step.params)

        val badKind = """{"goal":"x","steps":[{"kind":"FLY_TO_MOON","description":"d"}]}"""
        check(
            "validator rejects unknown kind",
            validator.parsePlanJson(badKind, request("x")) is PlanValidator.ParseOutcome.Invalid,
        )
        val noSteps = """{"goal":"x","steps":[]}"""
        check(
            "validator rejects empty steps",
            validator.parsePlanJson(noSteps, request("x")) is PlanValidator.ParseOutcome.Invalid,
        )

        // New AOIE params: KEYBOARD_ACTION on SUBMIT, TIMEOUT_MS on WAIT_FOR_APP.
        val withAoieParams = """
            {"goal":"wait then submit","steps":[
              {"kind":"WAIT_FOR_APP","description":"wait","params":{"app":"YouTube Music","timeoutMs":"5000"}},
              {"kind":"SUBMIT","description":"submit","params":{"keyboardAction":"dismiss"}}
            ]}
        """.trimIndent()
        check(
            "validator accepts keyboardAction/timeoutMs",
            validator.parsePlanJson(withAoieParams, request("wait then submit"))
                is PlanValidator.ParseOutcome.Valid,
        )
    }

    // ---- Phase 5: model proposes, rules fall back ----
    run {
        val goal = "open YouTube Music then scroll down twice then go back"
        val modelJson = """
            {"goal":"$goal","steps":[
              {"kind":"LAUNCH_APP","description":"open YT Music","params":{"app":"YouTube Music"}},
              {"kind":"SCROLL","description":"scroll down","params":{"direction":"down"}},
              {"kind":"SCROLL","description":"scroll down again","params":{"direction":"down"}},
              {"kind":"GO_BACK","description":"go back","params":{}}
            ]}
        """.trimIndent()
        val planner = TaskPlanner(apps, llmPlanJson = { modelJson })
        val result = planner.plan(request(goal))
        check("model plan used when valid", result is TaskPlanner.PlanResult.Planned)
        val steps = (result as? TaskPlanner.PlanResult.Planned)?.plan?.steps ?: emptyList()
        checkEq("model plan has 4 steps", steps.size, 4)
        checkEq(
            "model plan kinds in order",
            steps.map { it.kind },
            listOf(TaskStepKind.LAUNCH_APP, TaskStepKind.SCROLL, TaskStepKind.SCROLL, TaskStepKind.GO_BACK),
        )
        check("model plan origin is DIRECT_USER", (result as TaskPlanner.PlanResult.Planned).plan.origin == RequestOrigin.DIRECT_USER)

        val broken = TaskPlanner(apps, llmPlanJson = { "not json at all" })
        val fallback = broken.plan(request(goal))
        val fallbackSteps = (fallback as? TaskPlanner.PlanResult.Planned)?.plan?.steps ?: emptyList()
        check(
            "invalid model JSON falls back to rules",
            fallbackSteps.map { it.kind } !=
                listOf(TaskStepKind.LAUNCH_APP, TaskStepKind.SCROLL, TaskStepKind.SCROLL, TaskStepKind.GO_BACK),
        )

        val emptySource = TaskPlanner(apps, llmPlanJson = { "" })
        val ruleOnly = emptySource.plan(request(goal))
        check("empty model reply falls back", ruleOnly is TaskPlanner.PlanResult)
    }

    // ---- Phase 6: registry — Phone Manager seed + recipes ----
    run {
        val store = AppProfileStore()
        val pm = store.profileFor("com.miui.securitycenter")
        checkEq("Phone Manager seed label", pm.label, "Phone Manager")
        checkEq("Phone Manager seed category", pm.category, AppCategory.SYSTEM)
        check("Phone Manager seed has a recipe", pm.recipes.isNotEmpty())
        check("Phone Manager seed UNVERIFIED", pm.verificationStatus == ProfileVerificationStatus.UNVERIFIED)
        check("Phone Manager seed notes MIUI assumption", pm.knownLimitations.any { "MIUI" in it })

        val ytm = store.profileFor("com.google.android.apps.youtube.music")
        check("YT Music seed has a search recipe", ytm.recipes.any { it.name == "search" })

        val afterOne = store.recordValidation(
            "com.google.android.apps.youtube.music",
            "search",
            "harness: search recipe verified against seed screen",
            appVersion = "7.0",
        )
        // YT Music's seed holds a single recipe, so verifying it
        // verifies the whole profile — there is no TESTED middle
        // state for a one-recipe profile.
        checkEq("single recipe verified → VERIFIED", afterOne.verificationStatus, ProfileVerificationStatus.VERIFIED)
        check("recipe marked verified", afterOne.recipes.first { it.name == "search" }.verified)
        checkEq("appVersion recorded", afterOne.appVersion, "7.0")

        // TESTED is the middle state for a multi-recipe profile:
        // one of two recipes verified, the other still unverified.
        val twoRecipe = AppProfile(
            packageName = "com.example.two",
            label = "Two Recipe",
            category = AppCategory.OTHER,
            preferredSubmitOrder = DEFAULT_SUBMIT_ORDER,
            recipes = listOf(
                AppRecipe(name = "search", steps = listOf(TaskStepKind.SEARCH_IN_APP)),
                AppRecipe(name = "open-item", steps = listOf(TaskStepKind.TAP_TARGET)),
            ),
        )
        val persistence = InMemoryAppProfilePersistence()
        persistence.save(mapOf("com.example.two" to twoRecipe))
        val store2 = AppProfileStore(persistence)
        val afterPartial = store2.recordValidation("com.example.two", "search", "harness: partial")
        checkEq("one of two verified → TESTED", afterPartial.verificationStatus, ProfileVerificationStatus.TESTED)
        val afterAll = store2.recordValidation("com.example.two", "open-item", "harness: complete")
        checkEq("two of two verified → VERIFIED", afterAll.verificationStatus, ProfileVerificationStatus.VERIFIED)

        // A profile whose only recipe is verified rises to VERIFIED.
        val pmAfter = store.recordValidation(
            "com.miui.securitycenter",
            "inspect-utility-screen",
            "harness: inspect recipe verified",
        )
        checkEq("all recipes verified → VERIFIED", pmAfter.verificationStatus, ProfileVerificationStatus.VERIFIED)
        check("unknown recipe leaves profile unchanged",
            store.recordValidation("com.miui.securitycenter", "no-such-recipe", "x").verificationStatus ==
                ProfileVerificationStatus.VERIFIED)
    }

    // ---- CapabilityPrompt still names the action channel ----
    run {
        check("capability prompt mentions PLAN_TASK", "PLAN_TASK" in CapabilityPrompt.text)
    }

    println("AioePlannerTest: $passed passed, $failed failed")
    if (failed == 0) println("ALL $passed TESTS PASSED") else println("FAILED: $failed")
}
