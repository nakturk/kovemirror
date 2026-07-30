package com.kove.mirror

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val PREF_KEY_LANG = "app_language"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("kove_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_KEY_LANG, null)
        if (saved != null) {
            return saved
        }
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (!currentLocales.isEmpty) {
            val tag = currentLocales.get(0)?.language
            if (!tag.isNullOrEmpty()) return tag
        }
        return Locale.getDefault().language.lowercase()
    }

    fun setLocale(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences("kove_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_LANG, langCode).apply()

        val appLocales = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(appLocales)
    }

    fun applyLocale(context: Context): Context {
        val langCode = getSavedLanguage(context)
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
