package android.content

import java.io.File

/** Minimal android.content fakes — test-only, just enough for PointerPolicy. */
open class Context {
    companion object {
        const val MODE_PRIVATE = 0
    }

    /** Application context — this fake is its own application context. */
    open val applicationContext: Context get() = this

    /** Files dir — the JVM temp dir in tests. */
    open val filesDir: File
        get() = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

    private val prefs = mutableMapOf<String, SharedPreferences>()

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FakeSharedPreferences() }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getFloat(key: String, defValue: Float): Float
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override val all: Map<String, *>
        get() = values.toMap()

    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { values.remove(key) }

            override fun apply() {}
        }
}
