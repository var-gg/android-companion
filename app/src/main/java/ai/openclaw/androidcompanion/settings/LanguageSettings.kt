package ai.openclaw.androidcompanion.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LanguageSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language"
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_EN = "en"
    const val LANGUAGE_KO = "ko"

    fun applySaved(context: Context) {
        setLanguage(context, getSavedLanguage(context), persist = false)
    }

    fun getSavedLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANGUAGE_SYSTEM)
            ?: LANGUAGE_SYSTEM
    }

    fun setLanguage(context: Context, language: String, persist: Boolean = true) {
        val normalized = when (language) {
            LANGUAGE_EN, LANGUAGE_KO, LANGUAGE_SYSTEM -> language
            else -> LANGUAGE_SYSTEM
        }
        if (persist) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, normalized)
                .apply()
        }
        val locales = when (normalized) {
            LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
            LANGUAGE_KO -> LocaleListCompat.forLanguageTags("ko")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
