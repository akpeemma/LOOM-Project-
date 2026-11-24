package com.loom.assistant.intelligence.planner

import android.graphics.Rect
import com.loom.assistant.core.common.RequestOrigin
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel

/**
 * NAV-SCREEN fix (2026-11-11) — planner-side navigation tests.
 *
 * Covers, against the REAL TaskPlanner / PlanValidator /
 * PlanFailureAnalyzer / ScreenKnowledgeStore:
 *  1. catalogue-first routing — "open Reels" / "go to Reels" plan
 *     NAVIGATE_TAB (never a launch for an app called "reels");
 *     "open YouTube" / "go to YouTube" stay LAUNCH_APP;
 *  2. the model path — an LLM-proposed LAUNCH_APP "Reels" is
 *     rewritten to NAVIGATE_TAB;
 *  3. the validator — NAVIGATE_TAB with TARGET is valid, without
 *     TARGET is invalid;
 *  4. TARGET_GONE replan (N9) — a gone NAVIGATE_TAB replans with a
 *     positional fix suggestion; a gone plain TAP_TARGET aborts;
 *  5. ScreenKnowledge nav tabs — recorded per app, surviving via
 *     the in-memory persistence, and preferredNavTabFor finds
 *     Reels by position.
 */
private var passed = 0
private var failed = 0

private fun check(name: String, actual: Boolean) {
    if (actual) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        passed++
    } else {
        failed++
        println("FAIL: $name — expected <$expected>, got <$actual>")
    }
}

private val fakeApps = AppResolver { name ->
    when (name.lowercase()) {
        "youtube" -> AppResolution.Found("com.google.android.youtube", "YouTube")
        "facebook" -> AppResolution.Found("com.facebook.katana", "Facebook")
        "whatsapp" -> AppResolution.Found("com.whatsapp", "WhatsApp")
        else -> AppResolution.NotFound
    }
}

private fun planner(llm: ((String) -> String)? = null, knowledge: ScreenKnowledgeStore = ScreenKnowledgeStore()) =
    TaskPlanner(fakeApps, llm, knowledge = knowledge)

private fun req(goal: String, contextApp: String? = null) =
    PlanRequest(goal, RequestOrigin.DIRECT_USER, transparency = true, contextApp = contextApp)

private fun planned(result: TaskPlanner.PlanResult): TaskPlan? =
    (result as? TaskPlanner.PlanResult.Planned)?.plan

private fun kinds(plan: TaskPlan): List<TaskStepKind> = plan.steps.map { it.kind }

private fun el(
    id: String,
    className: String,
    text: String? = null,
    desc: String? = null,
    bounds: Rect = Rect(0, 0, 100, 50),
    role: ElementRole = ElementRole.OTHER,
    enabled: Boolean = true,
    selected: Boolean = false,
): ScreenElement = ScreenElement(
    id = id,
    text = text,
    contentDescription = desc,
    className = className,
    bounds = bounds,
    role = role,
    enabled = enabled,
    isPassword = false,
    selected = selected,
)

/** David's Facebook feed: six icon-only bottom-nav tabs, Home selected. */
private fun facebookFeed(): ScreenModel {
    val nav = el(
        "nav", "com.google.android.material.bottomnavigation.BottomNavigationView",
        bounds = Rect(0, 1520, 720, 1640), role = ElementRole.NAVIGATION,
    )
    val tabBounds = listOf(
        Rect(0, 1540, 120, 1580),
        Rect(120, 1540, 240, 1580),
        Rect(240, 1540, 360, 1580),
        Rect(360, 1540, 480, 1580),
        Rect(480, 1540, 600, 1580),
        Rect(600, 1540, 720, 1580),
    )
    val tabs = tabBounds.mapIndexed { i, b ->
        el("tab$i", "android.widget.ImageView", bounds = b, role = ElementRole.ICON, selected = i == 0)
    }
    return ScreenModel(
        packageName = "com.facebook.katana",
        elements = listOf(nav) + tabs,
        screenBounds = Rect(0, 0, 720, 1640),
    )
}

fun main() {
    val p = planner()

    // ---------- 1. catalogue-first routing ----------
    val openReels = planned(p.plan(req("open Reels", contextApp = "Facebook")))
    check("routing: 'open Reels' plans", openReels != null)
    if (openReels != null) {
        check(
            "routing: 'open Reels' has a NAVIGATE_TAB step",
            openReels.steps.any { it.kind == TaskStepKind.NAVIGATE_TAB },
        )
        check(
            "routing: 'open Reels' has NO launch for an app called 'reels'",
            openReels.steps.none {
                it.kind == TaskStepKind.LAUNCH_APP &&
                    it.params[PlanParams.APP]?.lowercase() == "reels"
            },
        )
        checkEq(
            "routing: 'open Reels' navigates to Reels",
            openReels.steps.firstOrNull { it.kind == TaskStepKind.NAVIGATE_TAB }?.params?.get(PlanParams.TARGET),
            "Reels",
        )
    }
    val openReelsOnFb = planned(p.plan(req("open Reels on Facebook")))
    check("routing: 'open Reels on Facebook' plans", openReelsOnFb != null)
    if (openReelsOnFb != null) {
        check(
            "routing: 'open Reels on Facebook' opens Facebook first",
            openReelsOnFb.steps.any {
                it.kind == TaskStepKind.LAUNCH_APP && it.params[PlanParams.APP] == "Facebook"
            },
        )
        check(
            "routing: 'open Reels on Facebook' then navigates",
            openReelsOnFb.steps.any { it.kind == TaskStepKind.NAVIGATE_TAB },
        )
        checkEq(
            "routing: nav step carries the Facebook package",
            openReelsOnFb.steps.firstOrNull { it.kind == TaskStepKind.NAVIGATE_TAB }?.params?.get(PlanParams.PACKAGE),
            "com.facebook.katana",
        )
    }
    val goToReels = planned(p.plan(req("go to Reels", contextApp = "Facebook")))
    check("routing: 'go to Reels' plans", goToReels != null)
    if (goToReels != null) {
        check(
            "routing: 'go to Reels' has a NAVIGATE_TAB step",
            goToReels.steps.any { it.kind == TaskStepKind.NAVIGATE_TAB },
        )
        check(
            "routing: 'go to Reels' never launches an app called 'reels'",
            goToReels.steps.none {
                it.kind == TaskStepKind.LAUNCH_APP &&
                    it.params[PlanParams.APP]?.lowercase() == "reels"
            },
        )
    }
    val takeMe = planned(p.plan(req("take me to Reels", contextApp = "Facebook")))
    check(
        "routing: 'take me to Reels' navigates",
        takeMe?.steps?.any { it.kind == TaskStepKind.NAVIGATE_TAB } == true,
    )
    val openYoutube = planned(p.plan(req("open YouTube")))
    check("routing: 'open YouTube' plans", openYoutube != null)
    if (openYoutube != null) {
        check(
            "routing: 'open YouTube' stays LAUNCH_APP",
            openYoutube.steps.any { it.kind == TaskStepKind.LAUNCH_APP },
        )
        check(
            "routing: 'open YouTube' has no NAVIGATE_TAB",
            openYoutube.steps.none { it.kind == TaskStepKind.NAVIGATE_TAB },
        )
    }
    val goToYoutube = planned(p.plan(req("go to YouTube")))
    check(
        "routing: 'go to YouTube' stays a launch, never navigation",
        goToYoutube?.steps?.any { it.kind == TaskStepKind.LAUNCH_APP } == true &&
            goToYoutube.steps.none { it.kind == TaskStepKind.NAVIGATE_TAB },
    )

    // ---------- 2. model-proposed LAUNCH_APP "Reels" is rewritten ----------
    val llmReels = planner(llm = {
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open Reels", "params": {"app": "Reels"}, "critical": true}
        ]}"""
    })
    val llmPlan = planned(llmReels.plan(req("open Reels")))
    check("llm rewrite: model plan used", llmPlan != null)
    if (llmPlan != null) {
        check(
            "llm rewrite: LAUNCH_APP 'Reels' became NAVIGATE_TAB",
            llmPlan.steps.any { it.kind == TaskStepKind.NAVIGATE_TAB },
        )
        check(
            "llm rewrite: no LAUNCH_APP 'Reels' survives",
            llmPlan.steps.none {
                it.kind == TaskStepKind.LAUNCH_APP &&
                    it.params[PlanParams.APP]?.lowercase() == "reels"
            },
        )
    }

    // ---------- 3. validator ----------
    val validator = PlanValidator()
    val navPlan = TaskPlan(
        id = "plan-nav-1", goal = "Go to Reels",
        steps = listOf(
            PlanStep(0, TaskStepKind.OBSERVE_SCREEN, "Look at the screen", critical = false),
            PlanStep(1, TaskStepKind.NAVIGATE_TAB, "Go to Reels", mapOf(PlanParams.TARGET to "Reels")),
        ),
        origin = RequestOrigin.DIRECT_USER,
    )
    check(
        "validator: NAVIGATE_TAB with TARGET is valid",
        validator.validate(navPlan) is PlanValidator.ValidationResult.Valid,
    )
    val navPlanNoTarget = navPlan.copy(
        steps = listOf(
            PlanStep(0, TaskStepKind.NAVIGATE_TAB, "Go somewhere", emptyMap()),
        ),
    )
    check(
        "validator: NAVIGATE_TAB without TARGET is invalid",
        validator.validate(navPlanNoTarget) is PlanValidator.ValidationResult.Invalid,
    )

    // ---------- 4. TARGET_GONE replan (N9) ----------
    val analyzer = PlanFailureAnalyzer()
    val navStep = PlanStep(
        1, TaskStepKind.NAVIGATE_TAB, "Go to Reels",
        mapOf(PlanParams.TARGET to "Reels", PlanParams.PACKAGE to "com.facebook.katana"),
    )
    val navGone = analyzer.analyze(
        "plan-nav-1", navStep,
        "target gone: \"Reels\" no longer matches anything on screen",
        // The observation must show the tab gone (no nav container
        // here) — otherwise resolveTarget reads PRESENT and the
        // generic replan branch answers instead of the N9 branch.
        ScreenModel(packageName = "com.google.android.youtube", elements = emptyList()),
    )
    checkEq("analyzer N9: gone NAVIGATE_TAB replans", navGone.decision, PlanFailureAnalyzer.FailureDecision.REPLAN)
    check(
        "analyzer N9: fix suggestion names the positional retry",
        navGone.fixSuggestion.contains("positional"),
    )
    check("analyzer N9: gone nav does not ask David", !navGone.askUser)
    val tapStep = PlanStep(
        1, TaskStepKind.TAP_TARGET, "Tap Save",
        mapOf(PlanParams.TARGET to "Save"),
    )
    val tapGone = analyzer.analyze(
        "plan-tap-1", tapStep,
        "target gone: \"Save\" no longer matches anything on screen",
        facebookFeed(),
    )
    checkEq("analyzer N9: gone plain TAP_TARGET aborts", tapGone.decision, PlanFailureAnalyzer.FailureDecision.ABORT)
    check("analyzer N9: gone tap asks David", tapGone.askUser)

    // ---------- 5. ScreenKnowledge nav tabs ----------
    val kb = ScreenKnowledgeStore()
    kb.recordObservation(facebookFeed())
    val tabs = kb.navTabsFor(facebookFeed())
    checkEq("knowledge: 6 nav tabs recorded for Facebook", tabs.size, 6)
    val preferred = kb.preferredNavTabFor(facebookFeed(), "Reels")
    check("knowledge: preferred tab for Reels found", preferred != null)
    checkEq("knowledge: preferred Reels tab is position 2", preferred?.position, 2)
    check(
        "knowledge: unknown destination has no preferred tab",
        kb.preferredNavTabFor(facebookFeed(), "megaphone") == null,
    )
    // Survives via a second store backed by the same persistence:
    // the in-memory persistence is per-store, so survival here means
    // the record is readable back from the same store after more
    // observations on other screens.
    kb.recordObservation(
        ScreenModel(packageName = "com.google.android.youtube", elements = emptyList()),
    )
    checkEq(
        "knowledge: Facebook tabs still recorded after another screen",
        kb.navTabsFor(facebookFeed()).size, 6,
    )

    // ---------- 6. session/state awareness: control commands are
    // deterministic and NEVER reach the LLM ----------
    // The device defect (2026-11-11 ~21:43 WAT): "pause" / "play"
    // re-opened YouTube Music instead of pausing/resuming — the LLM
    // cannot see the screen, so it re-planned a fresh play-search.
    // The LLM stub below proposes exactly that (a launch plan); the
    // deterministic branch must win over it.
    val launchStub: (String) -> String = {
        """{"goal": "x", "steps": [
          {"kind": "LAUNCH_APP", "description": "Open YouTube Music", "params": {"app": "YouTube Music"}, "critical": true},
          {"kind": "SEARCH_IN_APP", "description": "Search for the song", "params": {"query": "Fall"}, "critical": true}
        ]}"""
    }
    val pauseMusic = planned(planner(llm = launchStub).plan(req("pause the music")))
    check("control: 'pause the music' plans", pauseMusic != null)
    if (pauseMusic != null) {
        checkEq(
            "control: 'pause the music' is OBSERVE + PLAY_PAUSE, never a launch",
            kinds(pauseMusic),
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.PLAY_PAUSE),
        )
        checkEq(
            "control: 'pause the music' asks for pause",
            pauseMusic.steps.firstOrNull { it.kind == TaskStepKind.PLAY_PAUSE }?.params?.get(PlanParams.PLAY_STATE),
            "pause",
        )
        check(
            "control: the LLM's launch plan did not survive",
            pauseMusic.steps.none { it.kind == TaskStepKind.LAUNCH_APP },
        )
    }
    val playAgain = planned(planner(llm = launchStub).plan(req("play the song again")))
    check("control: 'play the song again' plans", playAgain != null)
    if (playAgain != null) {
        checkEq(
            "control: 'play the song again' is OBSERVE + PLAY_PAUSE",
            kinds(playAgain),
            listOf(TaskStepKind.OBSERVE_SCREEN, TaskStepKind.PLAY_PAUSE),
        )
        checkEq(
            "control: 'play the song again' asks for play",
            playAgain.steps.firstOrNull { it.kind == TaskStepKind.PLAY_PAUSE }?.params?.get(PlanParams.PLAY_STATE),
            "play",
        )
    }
    val barePause = planned(p.plan(req("pause")))
    check("control: bare 'pause' plans PLAY_PAUSE", barePause?.steps?.any { it.kind == TaskStepKind.PLAY_PAUSE } == true)
    val resumeMusic = planned(p.plan(req("resume the music")))
    checkEq(
        "control: 'resume the music' asks for play",
        resumeMusic?.steps?.firstOrNull { it.kind == TaskStepKind.PLAY_PAUSE }?.params?.get(PlanParams.PLAY_STATE),
        "play",
    )
    // A named song is NOT a control command — it still goes to the
    // play-search plan (launch + search), as before.
    val playDavido = planned(p.plan(req("play Davido", contextApp = "YouTube Music")))
    check("control: 'play Davido' plans", playDavido != null)
    if (playDavido != null) {
        check(
            "control: 'play Davido' has NO PLAY_PAUSE step (it is a search)",
            playDavido.steps.none { it.kind == TaskStepKind.PLAY_PAUSE },
        )
    }

    println("NavigationPlanTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
