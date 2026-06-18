package com.dadigua.hyperbrowser.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.dadigua.hyperbrowser.browser.BrowserSettings
import java.util.Locale

fun Context.withAppLocale(localePreference: String): Context {
    val locale = localeForPreference(localePreference) ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLocales(LocaleList(locale))
    configuration.setLayoutDirection(locale)
    return LocalizedContextWrapper(this, createConfigurationContext(configuration))
}

fun localeTagForPreference(localePreference: String): String =
    localeForPreference(localePreference)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()

private fun localeForPreference(localePreference: String): Locale? =
    when (localePreference) {
        BrowserSettings.LOCALE_CHINESE -> Locale.SIMPLIFIED_CHINESE
        BrowserSettings.LOCALE_ENGLISH -> Locale.ENGLISH
        else -> null
    }

private class LocalizedContextWrapper(
    base: Context,
    private val localizedContext: Context
) : ContextWrapper(base) {
    override fun getAssets() = localizedContext.assets

    override fun getResources(): Resources = localizedContext.resources

    override fun getTheme(): Resources.Theme = localizedContext.theme
}
