package com.loom.assistant.interaction.pointer

import android.graphics.Rect
import android.graphics.RectF
import com.loom.assistant.core.common.PointerDirection
import com.loom.assistant.execution.screenawareness.ElementRole
import com.loom.assistant.execution.screenawareness.ScreenElement
import com.loom.assistant.execution.screenawareness.ScreenModel
import com.loom.assistant.intelligence.authgate.AuthGate

/**
 * POINTER FEW-CURSOR SPEC DELTAS + MOUSE-VERBS (2026-11-05, David's
 * direction 18:38 + 18:40) — JVM tests for the pointer operation set.
 *
 * Covers, with fakes (no Android, no device):
 *  - PointerMotion: Bezier glide (curved, endpoints exact, long-move
 *    overshoot-and-correct, short moves never overshoot), position
 *    verification (cursor over target / tolerance / null = unverified),
 *    glide durations;
 *  - verify-before-dispatch: requestTap dispatches only when the owned
 *    cursor is over the verified target; a cursor parked elsewhere
 *    refuses with no dispatch; an unreported cursor (hidden policy)
 *    falls back to tree verification only;
 *  - visible feedback: tap ripple + "Tapping …" narration on dispatch,
 *    press state held for the long-press duration, "Moving to …" on show;
 *  - LONG_PRESS: dispatch through the long-press sink with the hold,
 *    clamped to 300–5000ms; no sink → honest Unavailable, no gate spend;
 *  - OPEN: tap-to-open through the tap sink, narrated "Opening …";
 *  - SCROLL: pointer-directed scroll through the `device.scroll` key,
 *    sensitive apps refuse;
 *  - SELECT/COPY: both refuse honestly at the gate naming the proposed
 *    keys (`select.text` / `copy.selection`) — nothing selected, nothing
 *    copied, no selection visual shown; password fields never selected
 *    or copied; copy without a selection refuses;
 *  - PointerSelection: select-all of one element, password/empty refuse.
 *
 * Run (from loom-android):
 *   export JAVA_HOME=~/workspace/.tools/jdk-17.0.21+9-jre
 *   export PATH=$JAVA_HOME/bin:~/workspace/.tools/kotlinc/bin:$PATH
 *   kotlinc -d out -jvm-target 17 \
 *     interaction/pointer/src/test/java/android/graphics/Rect.kt \
 *     interaction/pointer/src/test/java/android/graphics/RectF.kt \
 *     core/common/src/main/java/com/loom/assistant/core/common/PointerMove.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenModel.kt \
 *     execution/screenawareness/src/main/java/com/loom/assistant/execution/screenawareness/ScreenDataPolicy.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/core/common/FakeLoomBus.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/intelligence/authgate/FakeAuthGate.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/ObstructionLayout.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerMotion.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/PointerSelection.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceRendererContract.kt \
 *     interaction/pointer/src/main/java/com/loom/assistant/interaction/pointer/GuidanceSession.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PointerViewStubs.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/GuidanceTreeStub.kt \
 *     interaction/pointer/src/test/java/com/loom/assistant/interaction/pointer/PointerSpecDeltasTest.kt
 *   java -cp out com.loom.assistant.interaction.pointer.PointerSpecDeltasTestKt
 */

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

private fun approx(a: Float, b: Float, tol: Float = 0.5f): Boolean =
    kotlin.math.abs(a - b) <= tol

/**
 * Fake renderer that REPORTS a cursor position — the production
 * GuidanceRenderer does this via its owned cursor. Unlike the real
 * renderer, previewMoveTo does NOT move the reported cursor here: that
 * models the failure the verification exists for (a stale renderer
 * whose draw does not follow the preview). Tests set [reportedCursor]
 * explicitly: on-target, elsewhere, or null (cannot report).
 */
private class ReportingRenderer(val log: MutableList<String>) : GuidanceRendererContract {
    val previews = mutableListOf<RectF>()
    val narrations = mutableListOf<String>()
    val ripples = mutableListOf<String>()
    val pressStates = mutableListOf<Long>()
    val selections = mutableListOf<String>()
    val parks = mutableListOf<String>()
    var reportedCursor: GeomPoint? = null

    override fun showStep(
        anchorX: Float, anchorY: Float, target: RectF,
        stepIndex: Int, stepCount: Int, instruction: String,
    ) {
        log += "show"
        reportedCursor = GeomPoint(target.centerX(), target.centerY())
    }

    override fun hide() {
        log += "hide"
        reportedCursor = null
    }

    override fun previewMoveTo(target: RectF) {
        previews += target
        log += "preview"
        // Deliberately does NOT move reportedCursor (stale-render model).
    }

    override fun pulseOnResult() {
        log += "pulse"
    }

    override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {
        log += "anchor"
    }

    override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean = false

    override fun showTapRipple() {
        ripples += "ripple"
        log += "ripple"
    }

    override fun showPressState(holdMs: Long) {
        pressStates += holdMs
        log += "press:$holdMs"
    }

    override fun setStatusNarration(text: String) {
        narrations += text
        log += "narrate:$text"
    }

    override fun showSelection(target: RectF, label: String) {
        selections += label
        log += "select:$label"
    }

    override fun parkPointer(target: RectF, label: String) {
        parks += label
        log += "park:$label"
        reportedCursor = GeomPoint(target.centerX(), target.centerY())
    }

    override fun clearParked() {
        log += "clearParked"
    }

    override fun cursorPosition(): GeomPoint? = reportedCursor
}

private class FakeTree(var model: ScreenModel?) : GuidanceTreeSource {
    override val isBound: Boolean get() = true
    override fun snapshot(): ScreenModel? = model
}

private fun searchButton() = ScreenElement(
    id = "search-btn",
    text = "Search",
    contentDescription = null,
    className = "android.widget.Button",
    bounds = Rect(100, 200, 300, 260),
    role = ElementRole.BUTTON,
    enabled = true,
    isPassword = false,
)

private fun paragraph() = ScreenElement(
    id = "para-1",
    text = "Lively skin needs water and sleep.",
    contentDescription = null,
    className = "android.widget.TextView",
    bounds = Rect(50, 400, 500, 460),
    role = ElementRole.TEXT,
    enabled = true,
    isPassword = false,
)

private fun passwordField() = ScreenElement(
    id = "pw-1",
    text = "hunter2",
    contentDescription = null,
    className = "android.widget.EditText",
    bounds = Rect(50, 500, 500, 560),
    role = ElementRole.INPUT,
    enabled = true,
    isPassword = true,
)

private fun modelWith(vararg els: ScreenElement, pkg: String = "com.demo.app") =
    ScreenModel(pkg, els.toList())

private fun allowGate(): AuthGate {
    val gate = AuthGate.createForTest()
    gate.scripted = AuthGate.GateResult.Allow(
        com.loom.assistant.intelligence.authgate.AuthToken("tok-1"),
    )
    return gate
}

private fun newSession(
    log: MutableList<String>,
    tree: FakeTree,
    renderer: ReportingRenderer,
    query: String = "Search",
    instruction: String = "Tap Search",
): GuidanceSession {
    val session = GuidanceSession(
        steps = listOf(GuidanceSession.GuideStep(targetQuery = query, instruction = instruction)),
        tree = tree,
        renderer = renderer,
        anchorProvider = { 540f to 300f },
    )
    session.tapDispatcher = { el, _ ->
        log += "dispatch:${el.id}"
        true
    }
    session.longPressDispatcher = { el, _, holdMs ->
        log += "longpress:${el.id}:$holdMs"
        true
    }
    session.scrollDispatcher = { forward, _ ->
        log += "scroll:${if (forward) "down" else "up"}"
        true
    }
    return session
}

fun main() {
    // ---------- 1. PointerMotion: Bezier glide ----------
    run {
        val from = GeomPoint(0f, 0f)
        val to = GeomPoint(400f, 0f)
        check("bezier starts at from", PointerMotion.bezierPoint(from, GeomPoint(200f, -72f), to, 0f) == from)
        check("bezier ends at to", PointerMotion.bezierPoint(from, GeomPoint(200f, -72f), to, 1f) == to)
        val mid = PointerMotion.bezierPoint(from, GeomPoint(200f, -72f), to, 0.5f)
        check("bezier midpoint bows off the straight line", mid.y < -10f && approx(mid.x, 200f, 1f))
        check("glide starts at from", PointerMotion.glidePoint(from, to, 0f) == from)
        check("glide ends EXACTLY at to", PointerMotion.glidePoint(from, to, 1f) == to)
        check("glide midpoint is curved (off the line)", PointerMotion.glidePoint(from, to, 0.5f).y < -1f)
        // Long move (600px): overshoots past the target mid-finish, then settles.
        val longTo = GeomPoint(600f, 0f)
        var maxX = 0f
        var t = 0f
        while (t <= 1.001f) {
            val p = PointerMotion.glidePoint(from, longTo, t)
            if (p.x > maxX) maxX = p.x
            t += 0.02f
        }
        check("long move overshoots past the target", maxX > 600f)
        check("long move settles exactly on the target", PointerMotion.glidePoint(from, longTo, 1f) == longTo)
        // Short move: never overshoots — every sample stays between from and to.
        var shortOk = true
        t = 0f
        while (t <= 1.001f) {
            val p = PointerMotion.glidePoint(from, GeomPoint(200f, 0f), t)
            if (p.x < -0.5f || p.x > 200.5f) shortOk = false
            t += 0.02f
        }
        check("short move never overshoots", shortOk)
        check("baseline glide is 250ms", PointerMotion.glideDurationMs(100f) == 250L)
        check("long glide is longer but capped at 300ms", PointerMotion.glideDurationMs(2000f) in 251L..300L)
        check("mid glide scales with distance", PointerMotion.glideDurationMs(800f) == 300L)
    }

    // ---------- 2. PointerMotion: position verification ----------
    run {
        val target = GeomRect(100f, 200f, 300f, 260f)
        check("cursor inside verifies", PointerMotion.isCursorOverTarget(GeomPoint(200f, 230f), target))
        check("cursor outside refuses", !PointerMotion.isCursorOverTarget(GeomPoint(900f, 900f), target))
        check("cursor just outside within tolerance verifies", PointerMotion.isCursorOverTarget(GeomPoint(306f, 230f), target))
        check("cursor far outside tolerance refuses", !PointerMotion.isCursorOverTarget(GeomPoint(320f, 230f), target))
        check("null cursor is unverified", !PointerMotion.isCursorOverTarget(null, target))
        check("settled points settle", PointerMotion.isSettled(GeomPoint(100f, 100f), GeomPoint(105f, 95f)))
        check("distant points do not settle", !PointerMotion.isSettled(GeomPoint(100f, 100f), GeomPoint(200f, 100f)))
    }

    // ---------- 3. Verify-before-dispatch: cursor over target ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            check("start shows the step", session.start() is GuidanceSession.StepResult.Shown)
            check("show narrates Moving to…", renderer.narrations.contains("Moving to Search…"))
            // Cursor is on the target (showStep parked it there).
            val result = session.requestTap()
            check("tap dispatched when cursor is over target", result == GuidanceSession.TapResult.Dispatched)
            check("ripple shown on dispatch", renderer.ripples.size == 1)
            check("pill narrates Tapping…", renderer.narrations.contains("Tapping Search…"))
            check("ripple after dispatch in event order", log.indexOf("ripple") > log.indexOf("dispatch:search-btn"))
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 4. Verify-before-dispatch: cursor elsewhere refuses ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            renderer.reportedCursor = GeomPoint(900f, 900f) // pointer stuck elsewhere
            val result = session.requestTap()
            check("tap refused when cursor is off-target", result is GuidanceSession.TapResult.Unavailable)
            check(
                "off-target refusal names the pointer",
                (result as GuidanceSession.TapResult.Unavailable).reason.contains("pointer is not over"),
            )
            check("no dispatch when cursor is off-target", log.none { it.startsWith("dispatch:") })
            check("no ripple when refused", renderer.ripples.isEmpty())
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 5. Verify-before-dispatch: unreported cursor falls back to tree ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            renderer.reportedCursor = null // hidden-policy renderer: cannot report
            val result = session.requestTap()
            check("tap proceeds on tree verification when cursor is unreported", result == GuidanceSession.TapResult.Dispatched)
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 6. Long-press ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestLongPress()
            check("long-press dispatched", result == GuidanceSession.TapResult.Dispatched)
            check("long-press sink got the default 700ms hold", log.contains("longpress:search-btn:700"))
            check("press state held 700ms", renderer.pressStates.contains(700L))
            check("no tap ripple for a long-press", renderer.ripples.isEmpty())
            check("pill narrates Long-pressing…", renderer.narrations.contains("Long-pressing Search…"))
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            session.requestLongPress(100L) // below the 300ms floor
            check("hold clamps to the 300ms floor", log.contains("longpress:search-btn:300"))
            session.requestLongPress(9000L) // above the 5000ms ceiling
            check("hold clamps to the 5000ms ceiling", log.contains("longpress:search-btn:5000"))
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.longPressDispatcher = null
            session.start()
            val result = session.requestLongPress()
            check("no long-press sink is unavailable", result is GuidanceSession.TapResult.Unavailable)
            check("no dispatch without a sink", log.none { it.startsWith("longpress:") })
            check("gate never asked without a sink", gate.requestedActions.isEmpty())
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 7. Open ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestOpen()
            check("open dispatched through the tap sink", result == GuidanceSession.TapResult.Dispatched)
            check("open uses the tap dispatcher", log.contains("dispatch:search-btn"))
            check("pill narrates Opening…", renderer.narrations.contains("Opening Search…"))
            check("open shows the tap ripple", renderer.ripples.size == 1)
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 8. Scroll ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestScroll(forward = true)
            check("scroll dispatched", result == GuidanceSession.TapResult.Dispatched)
            check("scroll sink got the direction", log.contains("scroll:down"))
            check("pill narrates Scrolling down…", renderer.narrations.contains("Scrolling down…"))
            check("scroll used the device.scroll key", gate.requestedActions.any { it.key == "device.scroll" })
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton(), pkg = "com.example.bank"))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestScroll(forward = true)
            check("scroll refuses in a sensitive app", result is GuidanceSession.TapResult.Unavailable)
            check("no scroll dispatched in a sensitive app", log.none { it.startsWith("scroll:") })
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 9. Select / copy: honest gate refusal, nothing selected/copied ----------
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(paragraph()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer, query = "Lively skin", instruction = "Select this paragraph")
            session.start()
            val result = session.requestSelect()
            check("select refuses at the gate", result is GuidanceSession.TapResult.GateRequired)
            check(
                "select refusal names the proposed select.text key",
                (result as GuidanceSession.TapResult.GateRequired).reason.contains("select.text"),
            )
            check("no selection visual shown without confirmation", renderer.selections.isEmpty())
            check("select still previews + narrates", renderer.previews.isNotEmpty() && renderer.narrations.contains("Selecting Lively skin…"))
            check("select never asks the gate (no key to ask with)", gate.requestedActions.isEmpty())
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(passwordField()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer, query = "hunter2", instruction = "Select the password")
            session.start()
            val result = session.requestSelect()
            check("password field is never selected", result is GuidanceSession.TapResult.Unavailable)
            check(
                "password refusal says so",
                (result as GuidanceSession.TapResult.Unavailable).reason.contains("never selected"),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(paragraph()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer, query = "Lively skin", instruction = "Copy this paragraph")
            session.start()
            val noSelection = session.requestCopy()
            check("copy without a selection refuses", noSelection is GuidanceSession.TapResult.Unavailable)
            // Stage a confirmed selection (only a confirmed select sets
            // this in production; here the test stages it directly).
            session.lastSelection = PointerSelection.selectAll(paragraph())
            val result = session.requestCopy()
            check("copy with a selection refuses at the gate", result is GuidanceSession.TapResult.GateRequired)
            check(
                "copy refusal names the proposed copy.selection key",
                (result as GuidanceSession.TapResult.GateRequired).reason.contains("copy.selection"),
            )
            check("copy never asks the gate (no key to ask with)", gate.requestedActions.isEmpty())
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- 10. PointerSelection model ----------
    run {
        val sel = PointerSelection.selectAll(paragraph())
        check("select-all covers the whole paragraph", sel != null && sel.text == "Lively skin needs water and sleep." && sel.start == 0 && sel.end == sel.text.length)
        check("select-all is selectable", PointerSelection.isSelectable(paragraph()))
        check("password field is not selectable", !PointerSelection.isSelectable(passwordField()))
        check("password select-all is null", PointerSelection.selectAll(passwordField()) == null)
        val empty = paragraph().copy(id = "empty", text = "")
        check("empty text is not selectable", !PointerSelection.isSelectable(empty))
        val disabled = paragraph().copy(id = "off", enabled = false)
        check("disabled element is not selectable", !PointerSelection.isSelectable(disabled))
    }

    // ---------- 11. SPEED: the pill says what a step waits on ----------
    run {
        val log = mutableListOf<String>()
        val gate = AuthGate.createForTest()
        gate.scripted = AuthGate.GateResult.NeedsConfirmation("pending-1")
        AuthGate.setForTest(gate)
        try {
            val tree = FakeTree(modelWith(searchButton()))
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer)
            session.start()
            val result = session.requestTap()
            check("tap awaits confirmation", result is GuidanceSession.TapResult.AwaitingConfirmation)
            check(
                "pill says what the tap waits on",
                renderer.narrations.contains("Waiting for your approval — tap \"Search\""),
            )
            check("no dispatch while awaiting", log.none { it.startsWith("dispatch:") })
            val scrollResult = session.requestScroll(forward = false)
            check("scroll awaits confirmation", scrollResult is GuidanceSession.TapResult.AwaitingConfirmation)
            check(
                "pill says what the scroll waits on",
                renderer.narrations.contains("Waiting for your approval — scroll up"),
            )
        } finally {
            AuthGate.setForTest(null)
        }
    }

    // ---------- Screen search + guidance + navigation (2026-11-10,
    // David's rules): common-sense resolution, an honest not-found
    // answer, a scroll hunt below the fold, and a Shown claim only
    // when the pointer actually drew. ----------
    run {
        // Perspective: "my story" guides to the "Your story" tile.
        val log = mutableListOf<String>()
        val tree = FakeTree(
            modelWith(
                ScreenElement(
                    id = "your-story", text = "Your story", contentDescription = null,
                    className = "android.widget.Button", bounds = Rect(100, 200, 300, 260),
                    role = ElementRole.BUTTON, enabled = true, isPassword = false,
                ),
            ),
        )
        val renderer = ReportingRenderer(log)
        val session = newSession(log, tree, renderer, query = "my story", instruction = "Guide to \"my story\"")
        val result = session.start()
        check("my story guides to Your story", result is GuidanceSession.StepResult.Shown)
        check("pointer parked on the story tile", renderer.reportedCursor == GeomPoint(200f, 230f))
    }
    run {
        // Corrected 2026-11-11 01:54: a not-found answer names what
        // was looked for and WHERE LOOM looked — never a list of what
        // is on the screen ("I can see: …" is not an acceptable
        // substitute for finding the thing). No scrollable element
        // here, so no scroll is dispatched.
        val log = mutableListOf<String>()
        val tree = FakeTree(modelWith(searchButton(), paragraph(), pkg = "com.facebook.katana"))
        val renderer = ReportingRenderer(log)
        val session = newSession(log, tree, renderer, query = "Wi-Fi toggle", instruction = "Guide to \"Wi-Fi toggle\"")
        val result = session.start()
        check("not-found is Unavailable", result is GuidanceSession.StepResult.Unavailable)
        val reason = (result as GuidanceSession.StepResult.Unavailable).reason
        check("not-found names what was looked for", reason.contains("I looked for \"Wi-Fi toggle\""))
        check("not-found names where it looked", reason.contains("Facebook"))
        check("not-found never lists screen contents", !reason.contains("I can see:"))
        check("not-found scrolled nowhere (nothing scrollable)", log.none { it.startsWith("scroll:") })
    }
    run {
        // Scroll hunt: the target is below the fold — the first
        // scroll reveals it and the pointer moves to it.
        val log = mutableListOf<String>()
        val gate = allowGate()
        AuthGate.setForTest(gate)
        try {
            val belowFold = modelWith(
                ScreenElement(
                    id = "list", text = null, contentDescription = null,
                    className = "android.widget.ListView", bounds = Rect(0, 300, 1080, 2000),
                    role = ElementRole.LIST, enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "wifi", text = "Wi-Fi", contentDescription = null,
                    className = "android.widget.TextView", bounds = Rect(100, 1500, 500, 1560),
                    role = ElementRole.TEXT, enabled = true, isPassword = false,
                ),
            )
            val aboveFold = modelWith(
                ScreenElement(
                    id = "list", text = null, contentDescription = null,
                    className = "android.widget.ListView", bounds = Rect(0, 300, 1080, 2000),
                    role = ElementRole.LIST, enabled = true, isPassword = false,
                ),
                ScreenElement(
                    id = "top", text = "Settings", contentDescription = null,
                    className = "android.widget.TextView", bounds = Rect(100, 200, 500, 260),
                    role = ElementRole.TEXT, enabled = true, isPassword = false,
                ),
            )
            val tree = FakeTree(aboveFold)
            val renderer = ReportingRenderer(log)
            val session = newSession(log, tree, renderer, query = "Wi-Fi", instruction = "Guide to \"Wi-Fi\"")
            session.scrollDispatcher = { forward, _ ->
                log += "scroll:${if (forward) "down" else "up"}"
                tree.model = belowFold
                true
            }
            val result = session.start()
            check("scroll hunt finds the target below the fold", result is GuidanceSession.StepResult.Shown)
            check("scroll hunt scrolled down once", log.count { it == "scroll:down" } == 1)
            check("pointer parked on the revealed target", renderer.reportedCursor == GeomPoint(300f, 1530f))
        } finally {
            AuthGate.setForTest(null)
        }
    }
    run {
        // A failed draw is Unavailable, never a Shown the user
        // cannot see (confirm AND perform).
        val log = mutableListOf<String>()
        val tree = FakeTree(modelWith(searchButton()))
        val renderer = object : GuidanceRendererContract {
            var shown = 0
            override fun showStep(anchorX: Float, anchorY: Float, target: RectF, stepIndex: Int, stepCount: Int, instruction: String) { shown++ }
            override fun hide() {}
            override fun previewMoveTo(target: RectF) {}
            override fun pulseOnResult() {}
            override fun onAnchorChanged(newAnchorX: Float, newAnchorY: Float) {}
            override fun nudgePointer(direction: PointerDirection, stepDp: Float): Boolean = true
            override fun lastDrawFailure(): String? = "renderer down"
        }
        val session = GuidanceSession(
            steps = listOf(GuidanceSession.GuideStep(targetQuery = "Search", instruction = "Tap Search")),
            tree = tree,
            renderer = renderer,
            anchorProvider = { 540f to 300f },
        )
        val result = session.start()
        check("failed draw is Unavailable, not Shown", result is GuidanceSession.StepResult.Unavailable)
        check(
            "failed draw names the reason",
            (result as GuidanceSession.StepResult.Unavailable).reason.contains("could not be shown"),
        )
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
