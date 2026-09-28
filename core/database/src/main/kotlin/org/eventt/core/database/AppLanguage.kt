package org.eventt.core.database

import java.util.Locale

/**
 * UI language override. Compose resources resolve strings against [Locale.getDefault], so
 * [apply] must run once at startup before the first composition — a change takes effect on
 * the next launch. Defaults to English regardless of the OS locale.
 */
object AppLanguage {
    private const val SETTING_KEY = "app.language"

    /** Language code → its name in that language (findable from any UI language). */
    val SUPPORTED = linkedMapOf("en" to "English", "ru" to "Русский")

    fun get(): String = StaticDataDao.getSetting(SETTING_KEY)?.takeIf { it in SUPPORTED } ?: "en"

    fun set(code: String) {
        StaticDataDao.setSetting(SETTING_KEY, code)
    }

    fun apply() {
        val code = get()
        // Only the display language changes — number/date formatting keeps following the OS.
        val format = Locale.getDefault(Locale.Category.FORMAT)
        Locale.setDefault(Locale.of(code))
        Locale.setDefault(Locale.Category.FORMAT, format)
    }
}
