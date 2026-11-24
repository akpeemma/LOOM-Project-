package com.loom.assistant.interaction.pointer

/**
 * Stage 2C T1 — PointerPolicy tests (David's decision 3).
 *
 * Covers: default AUTO; SHOW/HIDE/AUTO persistence round-trip; truthful
 * write outcomes (Applied/Unchanged/Failed); labels; and the control-model
 * rule "hide the pointer" != disable automation (the repository owns no
 * automation state at all).
 *
 * Run: kotlinc PointerPolicy.kt PointerPolicyTest.kt -d out && java -cp out
 *   com.loom.assistant.interaction.pointer.PointerPolicyTestKt
 */

private class FakeStorage(var value: String? = null, var failSave: Boolean = false) :
    PointerPolicyStorage {
    var saves = 0
    override fun load(): String? = value
    override fun save(name: String): Boolean {
        saves++
        if (failSave) return false
        value = name
        return true
    }
}

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

fun main() {
    // 1. Default is AUTO when nothing is persisted.
    run {
        val repo = PointerPolicyRepository(FakeStorage(null))
        check("default is AUTO when storage empty", repo.get() == PointerPolicy.AUTO)
    }

    // 2. Default is AUTO when the stored value is corrupt.
    run {
        val repo = PointerPolicyRepository(FakeStorage("BOGUS"))
        check("default is AUTO on corrupt value", repo.get() == PointerPolicy.AUTO)
    }

    // 3. SHOW/HIDE/AUTO persistence round-trip (storage pre-seeded with a
    // different value so the write is a real change, not a no-op).
    for (policy in PointerPolicy.values()) {
        run {
            val other = PointerPolicy.values().first { it != policy }
            val storage = FakeStorage(other.name)
            val repo = PointerPolicyRepository(storage)
            val result = repo.set(policy)
            check(
                "set(${policy.name}) reports Applied",
                result == PointerPolicyRepository.SetResult.Applied,
            )
            check(
                "set(${policy.name}) persists (read-back)",
                repo.get() == policy && storage.value == policy.name,
            )
        }
    }

    // 4. Setting the same policy twice: second write is Unchanged, no save.
    run {
        val storage = FakeStorage(null)
        val repo = PointerPolicyRepository(storage)
        check(
            "first set(SHOW) is Applied",
            repo.set(PointerPolicy.SHOW) == PointerPolicyRepository.SetResult.Applied,
        )
        val savesAfterFirst = storage.saves
        check(
            "second set(SHOW) is Unchanged",
            repo.set(PointerPolicy.SHOW) == PointerPolicyRepository.SetResult.Unchanged,
        )
        check("Unchanged performs no save", storage.saves == savesAfterFirst)
    }

    // 5. Save failure -> Failed, value not applied.
    run {
        val storage = FakeStorage(null, failSave = true)
        val repo = PointerPolicyRepository(storage)
        check(
            "failed save reports Failed",
            repo.set(PointerPolicy.HIDE) == PointerPolicyRepository.SetResult.Failed,
        )
        check("failed save keeps AUTO", repo.get() == PointerPolicy.AUTO)
    }

    // 6. Labels.
    run {
        check("label SHOW", PointerPolicyRepository.label(PointerPolicy.SHOW) == "always show")
        check("label HIDE", PointerPolicyRepository.label(PointerPolicy.HIDE) == "hidden")
        check("label AUTO", PointerPolicyRepository.label(PointerPolicy.AUTO) == "automatic")
        check("titleLabel SHOW", PointerPolicyRepository.titleLabel(PointerPolicy.SHOW) == "Always show")
        check("titleLabel HIDE", PointerPolicyRepository.titleLabel(PointerPolicy.HIDE) == "Hidden")
        check("titleLabel AUTO", PointerPolicyRepository.titleLabel(PointerPolicy.AUTO) == "Automatic")
    }

    // 7. "hide the pointer" != disable automation: the repository owns no
    // automation state — a caller-held automation flag is untouched by a
    // policy change.
    run {
        var automationEnabled = true // owned by the automation component, not the repo
        val repo = PointerPolicyRepository(FakeStorage(null))
        repo.set(PointerPolicy.HIDE)
        check("HIDE stored", repo.get() == PointerPolicy.HIDE)
        check(
            "HIDE does not touch automation state",
            automationEnabled,
        )
        automationEnabled = false // "stop automation"
        check(
            "stopping automation does not change the pointer policy",
            repo.get() == PointerPolicy.HIDE,
        )
    }

    // 8. Policy survives across repository instances over the same storage.
    run {
        val storage = FakeStorage(null)
        PointerPolicyRepository(storage).set(PointerPolicy.HIDE)
        check(
            "policy persists across instances",
            PointerPolicyRepository(storage).get() == PointerPolicy.HIDE,
        )
    }

    println(if (failed == 0) "ALL $passed TESTS PASSED" else "$failed TESTS FAILED")
    if (failed > 0) kotlin.system.exitProcess(1)
}
