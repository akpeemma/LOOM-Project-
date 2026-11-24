package com.loom.assistant.platform.apps

/**
 * Master implementation §1 — pure-JVM checks for AppCatalogue.
 * Run: kotlinc AppCatalogue.kt AppCatalogueTest.kt -d out && java -cp out
 * com.loom.assistant.platform.apps.AppCatalogueTestKt
 * (AppCatalogue.kt has no android.* imports, so no fakes are needed.
 * The Android adapters in AndroidAppSources.kt are compile-checked
 * separately against android.jar; the package-change receiver is
 * exercised here through its seam, AppCatalogue.onPackageChanged(),
 * which is exactly what the receiver calls.)
 *
 * Proves: YouTube vs YouTube Music disambiguation, exact match,
 * NotFound (including substring-only queries), Ambiguous (never guess),
 * catalogue refresh on install/remove (fake AppSource standing in for
 * PackageManager), and foreground awareness (Known / Unavailable).
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name $detail")
    }
}

private fun checkFound(name: String, res: AppResolution, pkg: String) {
    val ok = res is AppResolution.Found && res.app.packageName == pkg
    check(name, ok, "got $res, expected Found($pkg)")
}

private fun checkAmbiguous(name: String, res: AppResolution, pkgs: List<String>) {
    val ok = res is AppResolution.Ambiguous &&
        res.candidates.map { it.packageName }.sorted() == pkgs.sorted()
    check(name, ok, "got $res, expected Ambiguous($pkgs)")
}

/** Fake PackageManager: the source list mutates like installs/removes. */
private class FakeAppSource(initial: List<AppInfo>) : AppSource {
    var apps: List<AppInfo> = initial
    override fun queryLaunchableApps(): List<AppInfo> = apps
}

private class FakeForeground(private val probe: ForegroundProbe) : ForegroundSource {
    override fun probe(): ForegroundProbe = probe
}

/** A source that fails until flipped — the unbound-service case. */
private class FlakyAppSource : AppSource {
    var fail = true
    var apps: List<AppInfo> = emptyList()
    override fun queryLaunchableApps(): List<AppInfo> {
        if (fail) throw IllegalStateException("service not bound")
        return apps
    }
}

private val PLAY_STORE = AppInfo(
    "Play Store", "com.android.vending",
    "com.android.vending.AssetBrowserActivity",
)
private val YOUTUB = AppInfo(
    "Youtub", "com.example.youtub", "com.example.youtub.Main",
)
private val YOUTUBE = AppInfo(
    "YouTube", "com.google.android.youtube",
    "com.google.android.apps.youtube.app.WatchWhileActivity",
)
private val YOUTUBE_MUSIC = AppInfo(
    "YouTube Music", "com.google.android.apps.youtube.music",
    "com.google.android.apps.youtube.music.activity.MainActivity",
)
private val MUSIC_PLAYER = AppInfo(
    "Music Player", "com.example.musicplayer", "com.example.musicplayer.Main",
)
private val WHATSAPP = AppInfo("WhatsApp", "com.whatsapp", "com.whatsapp.Main")
private val PHOTOS_A = AppInfo("Photos", "com.google.android.apps.photos", "com.google.android.apps.photos.Main")
private val PHOTOS_B = AppInfo("Photos", "com.example.photos", "com.example.photos.Main")

fun main() {
    val base = listOf(YOUTUBE, YOUTUBE_MUSIC, MUSIC_PLAYER, WHATSAPP, PHOTOS_A, PHOTOS_B)
    val catalogue = AppCatalogue(FakeAppSource(base))

    // ---- Exact match ----
    checkFound("exact YouTube", catalogue.resolveByName("YouTube"), YOUTUBE.packageName)
    checkFound("exact case-insensitive", catalogue.resolveByName("youtube"), YOUTUBE.packageName)
    checkFound("exact extra whitespace", catalogue.resolveByName("  YouTube  "), YOUTUBE.packageName)

    // ---- YouTube vs YouTube Music: the defect that must never recur ----
    checkFound(
        "YouTube Music never resolves to YouTube",
        catalogue.resolveByName("YouTube Music"), YOUTUBE_MUSIC.packageName,
    )
    checkFound(
        "youtube music lowercase",
        catalogue.resolveByName("youtube music"), YOUTUBE_MUSIC.packageName,
    )
    checkFound(
        "YouTube still resolves to YouTube with Music installed",
        catalogue.resolveByName("YouTube"), YOUTUBE.packageName,
    )
    check(
        "YouTube Music result is not the YouTube package",
        (catalogue.resolveByName("YouTube Music") as? AppResolution.Found)?.app?.packageName !=
            YOUTUBE.packageName,
    )

    // ---- Device-evidence fix (2026-11-05, item 9): "YouTube Music" alias ----
    // The real launcher label is "YT Music" — the spoken name matches
    // nothing until the alias fallback runs. A catalogue whose label
    // really is "YouTube Music" still resolves exactly (alias is a
    // fallback, never an override).
    val ytMusicReal = AppInfo(
        "YT Music", "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.music.activity.MainActivity",
    )
    val realCatalogue = AppCatalogue(FakeAppSource(listOf(YOUTUBE, ytMusicReal, WHATSAPP)))
    checkFound(
        "YouTube Music resolves to the YT Music entry",
        realCatalogue.resolveByName("YouTube Music"), ytMusicReal.packageName,
    )
    checkFound(
        "youtube music lowercase resolves to the YT Music entry",
        realCatalogue.resolveByName("youtube music"), ytMusicReal.packageName,
    )
    checkFound(
        "YT Music still resolves directly",
        realCatalogue.resolveByName("YT Music"), ytMusicReal.packageName,
    )
    checkFound(
        "YouTube still resolves to YouTube with YT Music installed",
        realCatalogue.resolveByName("YouTube"), YOUTUBE.packageName,
    )
    check(
        "alias result is not the YouTube package",
        (realCatalogue.resolveByName("YouTube Music") as? AppResolution.Found)?.app?.packageName !=
            YOUTUBE.packageName,
    )

    // ---- Word-boundary matching ----
    checkFound(
        "Music with only one music app finds it",
        AppCatalogue(FakeAppSource(listOf(YOUTUBE_MUSIC, WHATSAPP))).resolveByName("Music"),
        YOUTUBE_MUSIC.packageName,
    )
    checkFound(
        "whole-word phrase inside a longer label",
        AppCatalogue(
            FakeAppSource(listOf(AppInfo("Google Play Music", "com.google.android.music", null))),
        ).resolveByName("Play Music"),
        "com.google.android.music",
    )
    check(
        "tube music is NotFound — 'tube' is not a whole word",
        AppCatalogue(FakeAppSource(listOf(YOUTUBE_MUSIC))).resolveByName("tube music")
            is AppResolution.NotFound,
    )

    // ---- NotFound: substrings never match ----
    check("Tube alone is NotFound", catalogue.resolveByName("Tube") is AppResolution.NotFound)
    check("You alone is NotFound", catalogue.resolveByName("You") is AppResolution.NotFound)
    check("unknown app is NotFound", catalogue.resolveByName("ZebraApp") is AppResolution.NotFound)
    check("blank is NotFound", catalogue.resolveByName("   ") is AppResolution.NotFound)

    // ---- Ambiguous: never guess ----
    checkAmbiguous(
        "Music with two music apps is Ambiguous",
        catalogue.resolveByName("Music"),
        listOf(YOUTUBE_MUSIC.packageName, MUSIC_PLAYER.packageName),
    )
    checkAmbiguous(
        "duplicate exact label Photos is Ambiguous",
        catalogue.resolveByName("Photos"),
        listOf(PHOTOS_A.packageName, PHOTOS_B.packageName),
    )
    checkAmbiguous(
        "YouTube with two YouTube-* apps and no exact is Ambiguous",
        AppCatalogue(
            FakeAppSource(
                listOf(
                    YOUTUBE_MUSIC,
                    AppInfo("YouTube Kids", "com.google.android.apps.youtube.kids", null),
                ),
            ),
        ).resolveByName("YouTube"),
        listOf(YOUTUBE_MUSIC.packageName, "com.google.android.apps.youtube.kids"),
    )

    // ---- Literal package name ----
    checkFound(
        "package literal resolves",
        catalogue.resolveByName("com.whatsapp"), WHATSAPP.packageName,
    )
    check(
        "unknown package literal is NotFound",
        catalogue.resolveByName("com.nothing.here") is AppResolution.NotFound,
    )

    // ---- Catalogue refresh on install / remove (fake PackageManager) ----
    run {
        val source = FakeAppSource(listOf(YOUTUBE, WHATSAPP))
        val cat = AppCatalogue(source)
        check("before install: NewApp NotFound", cat.resolveByName("NewApp") is AppResolution.NotFound)
        source.apps = listOf(YOUTUBE, WHATSAPP, AppInfo("NewApp", "com.example.newapp", null))
        cat.onPackageChanged() // what AppPackageReceiver calls on PACKAGE_ADDED
        checkFound("after install: NewApp Found", cat.resolveByName("NewApp"), "com.example.newapp")
        source.apps = listOf(YOUTUBE) // WhatsApp removed
        cat.onPackageChanged() // what AppPackageReceiver calls on PACKAGE_REMOVED
        check("after remove: WhatsApp NotFound", cat.resolveByName("WhatsApp") is AppResolution.NotFound)
        checkFound("after remove: YouTube still Found", cat.resolveByName("YouTube"), YOUTUBE.packageName)
        check("allApps reflects removal", cat.allApps().map { it.label } == listOf("YouTube"))
    }

    // ---- allApps sorted by label ----
    check(
        "allApps sorted",
        catalogue.allApps().map { it.label } ==
            listOf("Music Player", "Photos", "Photos", "WhatsApp", "YouTube", "YouTube Music"),
    )

    // ---- Foreground awareness ----
    run {
        val fgKnown = AppCatalogue(
            FakeAppSource(base),
            FakeForeground(ForegroundProbe.Found("com.whatsapp")),
        )
        val known = fgKnown.foregroundApp()
        check(
            "foreground Known resolves catalogue app",
            known == ForegroundResult.Known("com.whatsapp", WHATSAPP),
            "got $known",
        )
        val fgSystem = AppCatalogue(
            FakeAppSource(base),
            FakeForeground(ForegroundProbe.Found("com.android.systemui")),
        )
        check(
            "foreground Known for non-launchable package carries app=null",
            fgSystem.foregroundApp() == ForegroundResult.Known("com.android.systemui", null),
        )
        val fgUnavail = AppCatalogue(
            FakeAppSource(base),
            FakeForeground(ForegroundProbe.Unavailable("accessibility off")),
        )
        val unavail = fgUnavail.foregroundApp()
        check(
            "foreground Unavailable carries the reason",
            unavail == ForegroundResult.Unavailable("accessibility off"),
            "got $unavail",
        )
        val fgNone = AppCatalogue(FakeAppSource(base), null)
        check(
            "no foreground source is Unavailable, never a guess",
            fgNone.foregroundApp() is ForegroundResult.Unavailable,
        )
    }

    // ---- MASTER F2b: aliases (fallback only, after exact/phrase
    // fail — never an override). ----
    val storeCatalogue = AppCatalogue(FakeAppSource(base + PLAY_STORE))
    checkFound(
        "google play store alias resolves to Play Store",
        storeCatalogue.resolveByName("Google Play Store"), PLAY_STORE.packageName,
    )
    checkFound(
        "playstore alias resolves to Play Store",
        storeCatalogue.resolveByName("playstore"), PLAY_STORE.packageName,
    )
    checkFound(
        "Play Store still resolves directly",
        storeCatalogue.resolveByName("Play Store"), PLAY_STORE.packageName,
    )
    checkFound(
        "you tube alias resolves to YouTube",
        catalogue.resolveByName("you tube"), YOUTUBE.packageName,
    )
    checkFound(
        "yt alias resolves to YouTube",
        catalogue.resolveByName("yt"), YOUTUBE.packageName,
    )
    // Exact always wins over the alias: an installed app labelled
    // exactly "YT" is the answer, not YouTube.
    val ytLabelCatalogue = AppCatalogue(
        FakeAppSource(listOf(YOUTUBE, AppInfo("YT", "com.example.yt", "com.example.yt.Main"))),
    )
    checkFound(
        "exact YT label wins over the yt alias",
        ytLabelCatalogue.resolveByName("yt"), "com.example.yt",
    )
    // With the real "YT Music" label installed, "yt" is claimed by
    // the phrase stage for YT Music (pre-existing catalogue
    // behaviour) — the alias only fires when nothing claims it.
    checkFound(
        "yt with YT Music installed resolves to YT Music",
        realCatalogue.resolveByName("yt"), ytMusicReal.packageName,
    )

    // ---- MASTER F2c: catalogue poisoning — a failed or empty first
    // load must NOT poison the process; the next resolve re-queries. ----
    val emptySource = FakeAppSource(emptyList())
    val poisonCatalogue = AppCatalogue(emptySource)
    check(
        "empty first load does not resolve",
        poisonCatalogue.resolveByName("YouTube") is AppResolution.NotFound,
    )
    emptySource.apps = base
    checkFound(
        "later successful load resolves after an empty first load",
        poisonCatalogue.resolveByName("YouTube"), YOUTUBE.packageName,
    )
    val flakySource = FlakyAppSource()
    val flakyCatalogue = AppCatalogue(flakySource)
    check(
        "throwing first load does not resolve and does not crash",
        flakyCatalogue.resolveByName("YouTube") is AppResolution.NotFound,
    )
    flakySource.fail = false
    flakySource.apps = base
    checkFound(
        "later successful load resolves after a throwing first load",
        flakyCatalogue.resolveByName("YouTube"), YOUTUBE.packageName,
    )

    // ---- MASTER F2d: bounded fuzzy fallback — exact first, then
    // whole-word, then Levenshtein >= 0.80, single candidate only;
    // ambiguous fuzzy results are Ambiguous, never a pick. ----
    checkFound(
        "youtub (STT garble) resolves to YouTube",
        catalogue.resolveByName("youtub"), YOUTUBE.packageName,
    )
    val exactWinsCatalogue = AppCatalogue(FakeAppSource(listOf(YOUTUBE, YOUTUB)))
    checkFound(
        "exact label still wins over a fuzzy neighbour",
        exactWinsCatalogue.resolveByName("youtub"), YOUTUB.packageName,
    )
    checkAmbiguous(
        "photoz is ambiguously fuzzy across both Photos entries",
        catalogue.resolveByName("photoz"),
        listOf(PHOTOS_A.packageName, PHOTOS_B.packageName),
    )
    check(
        "yout is too far for the fuzzy fallback",
        catalogue.resolveByName("yout") is AppResolution.NotFound,
    )
    check(
        "unrelated gibberish is NotFound, never a fuzzy pick",
        catalogue.resolveByName("ztxq") is AppResolution.NotFound,
    )

    println("AppCatalogueTest: $passed passed, $failed failed")
    if (failed == 0) {
        println("ALL $passed TESTS PASSED")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
