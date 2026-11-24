package android.content

/**
 * Minimal android.content.Context fake — test-only. Compiles the production
 * sources that touch Context/SharedPreferences/Intent against these instead
 * of android.jar, so ModeControllerTest runs on a plain JVM.
 */
abstract class Context {
    companion object {
        const val MODE_PRIVATE = 0
    }

    // Real properties (not getX() functions): production code uses the
    // property syntax `context.packageName`, which resolves against Java
    // getters on android.jar but needs true properties in Kotlin fakes.
    open val applicationContext: Context get() = this
    open val packageName: String get() = "com.loom.assistant.test"

    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    open fun startService(intent: Intent): ComponentName? = null
    open fun startForegroundService(intent: Intent): ComponentName? = null
}

open class ComponentName

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
    }
}

open class Intent {
    constructor()
    constructor(action: String) {
        this.action = action
    }
    constructor(action: String, uri: android.net.Uri)

    var action: String? = null

    open fun setClassName(packageName: String, className: String): Intent = this
    open fun addFlags(flags: Int): Intent = this

    companion object {
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
