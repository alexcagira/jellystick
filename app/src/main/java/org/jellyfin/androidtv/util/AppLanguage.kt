package org.jellyfin.androidtv.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Language of the app's texts, chosen in Settings → Language, independent of the language of the
 * TV (Fire OS only offers a few). Kept in its own small preferences file: it is read before
 * anything else starts (the activities and the application need it to load their texts).
 *
 * The languages are the translations of Jellyfin for Android TV (`res/values-*`). The screens
 * added by Jellystick are translated to English and Spanish only: in the other languages they
 * show in English.
 */
object AppLanguage {
	private const val PREFERENCES = "app_language"
	private const val KEY_LANGUAGE = "language"

	/** A language and how much of the original app is translated to it (percent) */
	data class Option(val tag: String, val translated: Int)

	/**
	 * Translations of the app (BCP 47 tags of its `values-*` folders) with how complete they are.
	 * English is the base language. Folders without translated texts are left out.
	 */
	val options: List<Option> = listOf(
		Option("en", 100),
		Option("es", 99),
		Option("es-419", 98),
		Option("es-AR", 88),
		Option("es-DO", 14),
		Option("es-MX", 79),
		Option("en-GB", 99),
		Option("af", 9),
		Option("ar", 99),
		Option("az", 12),
		Option("be", 99),
		Option("bg", 94),
		Option("bn-BD", 19),
		Option("ca", 99),
		Option("cs", 99),
		Option("cy", 22),
		Option("da", 99),
		Option("de", 99),
		Option("el", 88),
		Option("eo", 66),
		Option("et", 87),
		Option("fa", 69),
		Option("fi", 99),
		Option("fo", 99),
		Option("fr", 99),
		Option("gl", 9),
		Option("he", 99),
		Option("hi", 95),
		Option("hr", 99),
		Option("hu", 99),
		Option("id", 78),
		Option("it", 99),
		Option("ja", 99),
		Option("ka", 79),
		Option("kk", 66),
		Option("kn", 11),
		Option("ko", 79),
		Option("lt", 99),
		Option("lv", 87),
		Option("mk", 73),
		Option("ml", 52),
		Option("mn", 27),
		Option("nb", 87),
		Option("nl", 99),
		Option("pl", 99),
		Option("pt", 18),
		Option("pt-BR", 99),
		Option("pt-PT", 92),
		Option("ro", 93),
		Option("ru", 95),
		Option("sk", 98),
		Option("sl", 87),
		Option("sq", 54),
		Option("sr", 94),
		Option("sv", 99),
		Option("sw", 87),
		Option("ta", 99),
		Option("te", 5),
		Option("th", 99),
		Option("tl", 5),
		Option("tr", 99),
		Option("ug", 20),
		Option("uk", 99),
		Option("vi", 95),
		Option("yue-Hant", 99),
		Option("zh-CN", 99),
		Option("zh-TW", 82),
	)

	/** Languages with the screens of Jellystick translated too */
	val complete: Set<String> = setOf("en", "en-GB", "es", "es-419", "es-AR", "es-DO", "es-MX")

	/** Language of the TV, used when none is chosen (the resources of the system don't change with the app) */
	val systemLocale: Locale
		get() = Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()

	private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

	/** Language chosen, "" for the one of the TV */
	fun selected(context: Context): String = preferences(context).getString(KEY_LANGUAGE, "").orEmpty()

	/** Save the language ("" for the one of the TV) and use it for the texts of the application */
	fun select(context: Context, tag: String) {
		preferences(context).edit().putString(KEY_LANGUAGE, tag).commit()
		applyToApplication(context.applicationContext)
	}

	/** The locale the texts use */
	fun locale(context: Context): Locale = selected(context).takeIf { it.isNotEmpty() }
		?.let(Locale::forLanguageTag)
		?: systemLocale

	/** An activity's context with the chosen language: from `attachBaseContext` */
	fun wrap(base: Context): Context {
		if (selected(base).isEmpty()) return base
		val locale = locale(base)
		Locale.setDefault(locale)
		return base.createConfigurationContext(configurationFor(base.resources.configuration, locale))
	}

	/**
	 * The texts of the application context (repositories, notifications, the screensaver) in the
	 * chosen language: from `attachBaseContext` and `onConfigurationChanged` of the application.
	 */
	@Suppress("DEPRECATION")
	fun applyToApplication(context: Context) {
		val locale = locale(context)
		Locale.setDefault(locale)
		val resources = context.resources
		resources.updateConfiguration(configurationFor(resources.configuration, locale), resources.displayMetrics)
	}

	private fun configurationFor(current: Configuration, locale: Locale) = Configuration(current).apply {
		setLocale(locale)
		setLocales(LocaleList(locale))
	}

	/** Name of a language in that language ("Español", "English (United Kingdom)") */
	fun displayName(tag: String): String {
		val locale = Locale.forLanguageTag(tag)
		return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
	}
}
