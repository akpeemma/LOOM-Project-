package com.loom.assistant.execution.screenawareness

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.loom.assistant.presentation.designsystem.LoomDestination

/**
 * Master implementation §3 — screen perception tests.
 *
 * Drives the REAL AccessibilityScreenAwareness + ScreenDataPolicy +
 * ScreenModelText from fake accessibility trees (harness fake
 * AccessibilityNodeInfo), and the REAL LoomDestination graph for the
 * "What LOOM Sees" navigation check. No pixels, no MediaProjection,
 * no screenshots anywhere in this file.
 *
 * Covers:
 *  1. every element kind the tree can report (roles, labels, hints,
 *     scrollability, bounds, thumbnails);
 *  2. redaction preserved (password text never rendered; sensitive-app
 *     elements bounds+roles only);
 *  3. insufficient-tree honesty (unbound / unavailable / empty tree →
 *     Insufficient with the exact reason, never invented elements);
 *  4. the "What LOOM Sees" screen reachable via navigation.
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

/** Fake tree node. Bounds default to a visible 100x50 box. */
private fun node(
    className: String,
    text: String? = null,
    desc: String? = null,
    hint: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    visible: Boolean = true,
    scrollable: Boolean = false,
    bounds: Rect = Rect(0, 0, 100, 50),
    children: List<AccessibilityNodeInfo> = emptyList(),
): AccessibilityNodeInfo {
    val n = object : AccessibilityNodeInfo() {}
    n.className = className
    n.text = text
    n.contentDescription = desc
    n.hintText = hint
    n.isPassword = password
    n.isEnabled = enabled
    n.isVisibleToUser = visible
    n.isScrollable = scrollable
    n.bounds = bounds
    n.children.addAll(children)
    return n
}

private fun bindTree(root: AccessibilityNodeInfo?, packageName: String?) {
    if (root == null) {
        ScreenAwarenessHolder.unbind()
        return
    }
    root.packageName = packageName
    ScreenAwarenessHolder.bind(object : A11yNodeProvider {
        override fun safeRootInActiveWindow(): AccessibilityNodeInfo = root
    })
}

private fun bindNullRoot() {
    ScreenAwarenessHolder.bind(object : A11yNodeProvider {
        override fun safeRootInActiveWindow(): AccessibilityNodeInfo? = null
    })
}

/** Full fake screen: one of every element kind the tree can report. */
private fun richTree(): AccessibilityNodeInfo {
    var y = 0
    fun box(): Rect {
        val r = Rect(0, y, 1080, y + 80)
        y += 100
        return r
    }
    return node(
        "android.widget.FrameLayout",
        bounds = Rect(0, 0, 1080, 2400),
        children = listOf(
            node("android.widget.TextView", text = "Settings", bounds = box()),
            node("android.widget.Button", text = "Save", bounds = box()),
            node("android.widget.ImageButton", desc = "Back", bounds = box()),
            node("android.widget.EditText", hint = "Your name", bounds = box()),
            node("android.widget.EditText", hint = "Search settings", bounds = box()),
            node("android.widget.SearchView", hint = "Search", bounds = box()),
            node("android.widget.ImageView", desc = "Profile photo", bounds = box()),
            node("android.widget.CheckBox", text = "Notifications", bounds = box()),
            node("android.view.MenuItem", text = "Share", bounds = box()),
            node("android.app.AlertDialog", text = "Are you sure?", bounds = box()),
            node(
                "com.google.android.material.bottomnavigation.BottomNavigationView",
                bounds = box(),
            ),
            node("androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = box()),
            node("android.widget.Switch", text = "Dark mode", bounds = box()),
            // Thumbnail: image with description + bounds, no text of its own.
            node("android.widget.ImageView", desc = "Video thumbnail", bounds = Rect(0, y, 540, y + 300)),
            // Invisible + empty-bounds nodes must be dropped, not rendered.
            node("android.widget.TextView", text = "Hidden", visible = false, bounds = box()),
            node("android.widget.TextView", text = "Zero size", bounds = Rect(0, 0, 0, 0)),
        ),
    )
}

fun main() {
    // ---------- 1. every element kind, from a fake tree ----------
    bindTree(richTree(), "com.example.app")
    val model = ScreenAwarenessHolder.current.snapshot()
    check("rich tree: snapshot present", model != null)
    val els = model!!.elements
    // 16 children, 2 dropped (invisible / zero-size) = 14 kept, plus the
    // visible root FrameLayout itself (OTHER) = 15 total.
    checkEq("rich tree: element count", els.size, 15)
    fun byLabel(label: String) = els.firstOrNull { it.label() == label }
    checkEq("text role", byLabel("Settings")?.role, ElementRole.TEXT)
    checkEq("button role", byLabel("Save")?.role, ElementRole.BUTTON)
    checkEq("icon role (ImageButton)", byLabel("Back")?.role, ElementRole.ICON)
    checkEq("input role (EditText)", byLabel("Your name")?.role, ElementRole.INPUT)
    checkEq(
        "search role (EditText + search hint)",
        els.firstOrNull { it.hintText == "Search settings" }?.role,
        ElementRole.SEARCH_FIELD,
    )
    checkEq("search role (SearchView)", els.firstOrNull { it.className.contains("SearchView") }?.role, ElementRole.SEARCH_FIELD)
    checkEq("image role", byLabel("Profile photo")?.role, ElementRole.IMAGE)
    checkEq("toggle role (CheckBox)", byLabel("Notifications")?.role, ElementRole.TOGGLE)
    checkEq("toggle role (Switch)", byLabel("Dark mode")?.role, ElementRole.TOGGLE)
    checkEq("menu item role", byLabel("Share")?.role, ElementRole.MENU_ITEM)
    checkEq("dialog role", byLabel("Are you sure?")?.role, ElementRole.DIALOG)
    checkEq(
        "navigation role",
        els.firstOrNull { it.className.contains("BottomNavigation") }?.role,
        ElementRole.NAVIGATION,
    )
    val list = els.firstOrNull { it.className.contains("RecyclerView") }
    checkEq("list role", list?.role, ElementRole.LIST)
    check("list scrollable from tree", list?.scrollable == true)
    // Thumbnail metadata: description + bounds, no text.
    val thumb = byLabel("Video thumbnail")
    checkEq("thumbnail role", thumb?.role, ElementRole.IMAGE)
    checkEq("thumbnail description kept", thumb?.contentDescription, "Video thumbnail")
    checkEq("thumbnail bounds kept", thumb?.bounds, Rect(0, 1300, 540, 1600))
    check("thumbnail has no invented text", thumb?.text == null)
    // Label fallback chain: text → description → hint → class name.
    checkEq("label falls back to hint", byLabel("Your name")?.label(), "Your name")
    checkEq("label falls back to description", byLabel("Back")?.label(), "Back")
    check("hidden node dropped", els.none { it.text == "Hidden" })
    check("zero-size node dropped", els.none { it.text == "Zero size" })
    // findElement matches text, description AND hint.
    val awareness = ScreenAwarenessHolder.current
    checkEq("findElement matches hint", awareness.findElement("search settings")?.hintText, "Search settings")
    checkEq("findElement matches description", awareness.findElement("profile photo")?.role, ElementRole.IMAGE)
    checkEq("findElement matches text", awareness.findElement("save")?.role, ElementRole.BUTTON)

    // ---------- rendering: ScreenModelText lines for every kind ----------
    val lines = ScreenModelText.render(model)
    checkEq("render: header + one line per element", lines.size, els.size + 1)
    check("render: header names app + count", lines[0] == "App: com.example.app · 15 elements")
    check("render: button line has label+role+bounds", lines.any { it.startsWith("Save — BUTTON — [") })
    check("render: search line present", lines.any { it.contains("SEARCH_FIELD") })
    check("render: dialog line present", lines.any { it.contains("DIALOG") })
    check("render: navigation line present", lines.any { it.contains("NAVIGATION") })
    check("render: list line present", lines.any { it.contains("LIST") })
    check("render: scrollable flag present", lines.any { it.contains("scrollable") })
    check("render: thumbnail line present", lines.any { it.startsWith("Video thumbnail — IMAGE — [") })

    // ---------- 2. redaction preserved ----------
    bindTree(
        node(
            "android.widget.LinearLayout",
            bounds = Rect(0, 0, 1080, 2400),
            children = listOf(
                node("android.widget.EditText", text = "hunter2", hint = "Password", password = true, bounds = Rect(0, 0, 1080, 100)),
                node("android.widget.TextView", text = "Username", bounds = Rect(0, 100, 1080, 200)),
            ),
        ),
        "com.example.app",
    )
    val pwModel = ScreenAwarenessHolder.current.snapshot()!!
    val pw = pwModel.elements.first { it.isPassword }
    check("password text redacted", pw.text == null)
    check("password description redacted", pw.contentDescription == null)
    check("password hint redacted", pw.hintText == null)
    check("password bounds+role survive", pw.bounds == Rect(0, 0, 1080, 100) && pw.role == ElementRole.INPUT)
    check("non-password element untouched", pwModel.elements.any { it.text == "Username" })
    val pwLines = ScreenModelText.render(pwModel)
    check("render: password value never rendered", pwLines.none { it.contains("hunter2") })
    check("render: password flag shown", pwLines.any { it.contains("password — text hidden") })

    // Sensitive app: every label cleared, bounds+roles only.
    bindTree(
        node(
            "android.widget.LinearLayout",
            bounds = Rect(0, 0, 1080, 2400),
            children = listOf(
                node("android.widget.Button", text = "Transfer", desc = "Send money", bounds = Rect(0, 0, 1080, 100)),
                node("android.widget.EditText", text = "1234", hint = "Account number", password = true, bounds = Rect(0, 100, 1080, 200)),
            ),
        ),
        "com.kuda.app",
    )
    val bankModel = ScreenAwarenessHolder.current.snapshot()!!
    check("sensitive: all text cleared", bankModel.elements.all { it.text == null })
    check("sensitive: all descriptions cleared", bankModel.elements.all { it.contentDescription == null })
    check("sensitive: all hints cleared", bankModel.elements.all { it.hintText == null })
    checkEq(
        "sensitive: roles survive",
        bankModel.elements.map { it.role },
        listOf(ElementRole.OTHER, ElementRole.BUTTON, ElementRole.INPUT),
    )
    check("sensitive: bounds survive", bankModel.elements.all { !it.bounds.isEmpty })
    val bankLines = ScreenModelText.render(bankModel)
    check("render: sensitive text never rendered", bankLines.none { it.contains("Transfer") || it.contains("1234") })
    check(
        "sensitive notice present",
        ScreenModelText.sensitiveNotice("com.kuda.app")?.contains("Sensitive app") == true,
    )
    check("non-sensitive has no notice", ScreenModelText.sensitiveNotice("com.example.app") == null)

    // ---------- 3. insufficient-tree honesty ----------
    ScreenAwarenessHolder.unbind()
    val unbound = ScreenAwarenessHolder.current.read()
    check(
        "unbound → Insufficient naming enablement",
        unbound is ScreenRead.Insufficient && unbound.reason.contains("not enabled"),
    )
    check("unbound → snapshot null", ScreenAwarenessHolder.current.snapshot() == null)
    check("unbound → findElement null", ScreenAwarenessHolder.current.findElement("x") == null)

    bindNullRoot()
    val noTree = ScreenAwarenessHolder.current.read()
    check(
        "null root → Insufficient naming unavailability",
        noTree is ScreenRead.Insufficient && noTree.reason.contains("unavailable"),
    )

    // Root with zero bounds and no children: the walk adds nothing, so
    // the tree reads as empty — Insufficient, not an invented element.
    bindTree(node("android.widget.FrameLayout", bounds = Rect(0, 0, 0, 0)), "com.example.app")
    val empty = ScreenAwarenessHolder.current.read()
    check(
        "empty tree → Insufficient naming emptiness, no invented elements",
        empty is ScreenRead.Insufficient && empty.reason.contains("empty"),
    )
    check("empty tree → snapshot has zero elements", ScreenAwarenessHolder.current.snapshot()?.elements?.isEmpty() == true)

    // Wrong tree (root with no package) → Insufficient, not a model.
    val noPkg = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
    ScreenAwarenessHolder.bind(object : A11yNodeProvider {
        override fun safeRootInActiveWindow(): AccessibilityNodeInfo = noPkg
    })
    val noPkgRead = ScreenAwarenessHolder.current.read()
    check("no-package root → Insufficient", noPkgRead is ScreenRead.Insufficient)

    // Available path still reports Available with the model.
    bindTree(richTree(), "com.example.app")
    val ok = ScreenAwarenessHolder.current.read()
    check("rich tree → Available", ok is ScreenRead.Available && ok.model.elements.size == 15)

    // ---------- 4. "What LOOM Sees" reachable via navigation ----------
    checkEq(
        "destination resolves from route",
        LoomDestination.fromRoute("what-loom-sees"),
        LoomDestination.WhatLoomSees,
    )
    check("destination in graph", LoomDestination.WhatLoomSees in LoomDestination.all)
    checkEq("destination route", LoomDestination.WhatLoomSees.route, "what-loom-sees")
    check("destination is not a bottom-nav tab", LoomDestination.WhatLoomSees !in LoomDestination.bottomNav)
    checkEq("round-trip route", LoomDestination.fromRoute(LoomDestination.WhatLoomSees.route), LoomDestination.WhatLoomSees)

    // ---------- 5. POINTER EVERYWHERE (2026-11-09): the snapshot
    // carries the measurements the app-window tree cannot supply —
    // root bounds (real screen size), the system Back button, and
    // the keyboard's action key — each null when absent, never
    // guessed. A throwing provider must never take the snapshot
    // down with it. ----------
    run {
        val back = MeasuredControl(Rect(330, 2250, 420, 2340), "Back", "android.widget.ImageButton")
        val key = MeasuredControl(Rect(830, 1950, 1060, 2100), "Search", "android.widget.Button")
        val root = richTree()
        root.packageName = "com.example.app"
        ScreenAwarenessHolder.bind(object : A11yNodeProvider {
            override fun safeRootInActiveWindow(): AccessibilityNodeInfo = root
            override fun navBackButton(): MeasuredControl = back
            override fun keyboardActionKey(): MeasuredControl = key
            override fun isInputMethodVisible(): Boolean = true
        })
        val model = ScreenAwarenessHolder.current.snapshot()
        checkEq("snapshot carries the root bounds as screen size", model?.screenBounds, Rect(0, 0, 1080, 2400))
        checkEq("snapshot carries the measured Back button", model?.backButton, back)
        checkEq("snapshot carries the measured keyboard key", model?.keyboardActionKey, key)
        checkEq("snapshot still reports the keyboard visible", model?.keyboardVisible, true)
    }
    run {
        // Default provider: no Back button, no key, keyboard hidden
        // — all three read null, and the snapshot still succeeds.
        bindTree(richTree(), "com.example.app")
        val model = ScreenAwarenessHolder.current.snapshot()
        checkEq("no Back node reads null, never invented", model?.backButton, null)
        checkEq("no keyboard key reads null, never invented", model?.keyboardActionKey, null)
        checkEq("screen bounds still read from the root", model?.screenBounds, Rect(0, 0, 1080, 2400))
    }
    run {
        // A throwing provider for the extra windows: the snapshot
        // degrades to nulls instead of failing the whole read.
        val root = richTree()
        root.packageName = "com.example.app"
        ScreenAwarenessHolder.bind(object : A11yNodeProvider {
            override fun safeRootInActiveWindow(): AccessibilityNodeInfo = root
            override fun navBackButton(): MeasuredControl = throw IllegalStateException("windows boom")
            override fun keyboardActionKey(): MeasuredControl = throw IllegalStateException("windows boom")
        })
        val model = ScreenAwarenessHolder.current.snapshot()
        check("throwing window reads still snapshot the tree", model != null && model.elements.size == 15)
        checkEq("throwing Back read degrades to null", model?.backButton, null)
        checkEq("throwing key read degrades to null", model?.keyboardActionKey, null)
    }
    run {
        // Redaction preserves the measurements: clearing text in a
        // sensitive app must not drop the bounds the pointer needs.
        val model = ScreenModel(
            packageName = "com.kuda.app",
            elements = listOf(
                ScreenElement(
                    id = "field", text = "secret", contentDescription = null,
                    className = "android.widget.EditText", bounds = Rect(0, 0, 100, 50),
                    role = ElementRole.INPUT, enabled = true, isPassword = true,
                ),
            ),
            screenBounds = Rect(0, 0, 1080, 2400),
            backButton = MeasuredControl(Rect(330, 2250, 420, 2340), "Back", "android.widget.ImageButton"),
            keyboardActionKey = MeasuredControl(Rect(830, 1950, 1060, 2100), "Search", "android.widget.Button"),
        )
        val redacted = ScreenDataPolicy.redact(model)
        checkEq("redaction keeps screen bounds", redacted.screenBounds, Rect(0, 0, 1080, 2400))
        checkEq("redaction keeps the Back button", redacted.backButton, model.backButton)
        checkEq("redaction keeps the keyboard key", redacted.keyboardActionKey, model.keyboardActionKey)
        checkEq("redaction still clears the password text", redacted.elements[0].text, null)
    }

    // ---------- 6. VIEWPORT GROUNDING (2026-11-11, screen fix,
    // CORRECTED): the snapshot keeps the FULL tree — LOOM's own work
    // (search, guidance, navigation) must reach off-screen targets
    // and bring them up, never be limited to what the user sees.
    // The user's point of view is the derived visible subset
    // ([ScreenModel.visibleElements]: bounds intersect the viewport,
    // non-empty). isVisibleToUser alone is loose — off-screen pages,
    // hidden fragments and scrolled content still report visible —
    // so the viewport is what separates the two. ----------
    run {
        val tree = node(
            "android.widget.FrameLayout",
            bounds = Rect(0, 0, 1080, 2400),
            children = listOf(
                node("android.widget.TextView", text = "On screen", bounds = Rect(0, 100, 1080, 200)),
                node("android.widget.TextView", text = "Below the fold", bounds = Rect(0, 3000, 1080, 3100)),
                node("android.widget.TextView", text = "Off to the right", bounds = Rect(2000, 100, 2100, 200)),
                node("android.widget.TextView", text = "Half visible", bounds = Rect(0, 2300, 1080, 2500)),
                node("android.widget.TextView", text = "Above the top", bounds = Rect(0, -300, 1080, -100)),
                node("android.widget.TextView", text = "Zero size", bounds = Rect(0, 0, 0, 0)),
            ),
        )
        bindTree(tree, "com.example.app")
        val model = ScreenAwarenessHolder.current.snapshot()!!
        // Full tree kept: root + 5 children (zero-size dropped by
        // walk, as before) = 6. Nothing off-screen is removed.
        checkEq("viewport: full tree kept in elements", model.elements.size, 6)
        check("viewport: below-fold node kept in the full tree", model.elements.any { it.text == "Below the fold" })
        check("viewport: off-to-the-right node kept in the full tree", model.elements.any { it.text == "Off to the right" })
        // Visible subset: root + On screen + Half visible = 3.
        checkEq("viewport: visible subset is root + visible nodes", model.visibleElements.size, 3)
        check("viewport: on-screen node is visible", model.visibleElements.any { it.text == "On screen" })
        check("viewport: partially visible node is visible", model.visibleElements.any { it.text == "Half visible" })
        check("viewport: below-fold node is not visible", model.visibleElements.none { it.text == "Below the fold" })
        check("viewport: off-to-the-right node is not visible", model.visibleElements.none { it.text == "Off to the right" })
        check("viewport: above-the-top node is not visible", model.visibleElements.none { it.text == "Above the top" })
        check(
            "viewport: isVisibleElement true for a visible node",
            model.isVisibleElement(model.elements.first { it.text == "On screen" }),
        )
        check(
            "viewport: isVisibleElement false for an off-screen node",
            !model.isVisibleElement(model.elements.first { it.text == "Below the fold" }),
        )
        // LOOM's reach: findElement (full tree) FINDS the below-fold
        // target — that is the point of the broader awareness.
        check(
            "viewport: findElement finds a below-fold node (LOOM's reach)",
            ScreenAwarenessHolder.current.findElement("below the fold")?.text == "Below the fold",
        )
        // The user's point of view: findVisibleElement does NOT.
        check(
            "viewport: findVisibleElement cannot find a below-fold node",
            ScreenAwarenessHolder.current.findVisibleElement("below the fold") == null,
        )
        check(
            "viewport: findVisibleElement finds a visible node",
            ScreenAwarenessHolder.current.findVisibleElement("on screen")?.text == "On screen",
        )
        check(
            "viewport: findVisibleElement finds a half-visible node",
            ScreenAwarenessHolder.current.findVisibleElement("half visible")?.text == "Half visible",
        )
        val belowEl = model.elements.first { it.text == "Below the fold" }
        val onEl = model.elements.first { it.text == "On screen" }
        check(
            "viewport: isElementVisible false for the off-screen node",
            !ScreenAwarenessHolder.current.isElementVisible(belowEl),
        )
        check(
            "viewport: isElementVisible true for the visible node",
            ScreenAwarenessHolder.current.isElementVisible(onEl),
        )
    }

    // cleanup
    ScreenAwarenessHolder.unbind()

    println("ScreenPerceptionTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
