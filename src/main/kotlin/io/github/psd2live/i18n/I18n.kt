package io.github.psd2live.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.prefs.Preferences

enum class AppLanguage(
	val tag: String,
	val locale: Locale,
	val displayNameKey: String,
) {
	CHINESE("zh", Locale.SIMPLIFIED_CHINESE, "language.chinese"),
	ENGLISH("en", Locale.ENGLISH, "language.english"),
	JAPANESE("ja", Locale.JAPANESE, "language.japanese");

	companion object {
		fun fromTag(tag: String?): AppLanguage? {
			val language = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
			return entries.firstOrNull { it.tag == language }
		}

		fun fromLocale(locale: Locale): AppLanguage = fromTag(locale.language) ?: ENGLISH
	}
}

/** Application-wide translations shared by Swing, CLI, and pipeline diagnostics. */
object I18n {
	private const val BUNDLE_NAME = "i18n.Messages"
	private const val PREFERENCE_KEY = "language"
	private val preferences by lazy { Preferences.userNodeForPackage(I18n::class.java) }

	private data class State(val language: AppLanguage, val bundle: ResourceBundle)

	@Volatile
	private var state: State = preferredLanguage().let { State(it, loadBundle(it)) }

	val currentLanguage: AppLanguage get() = state.language
	val supportedLanguages: List<AppLanguage> get() = AppLanguage.entries

	@Synchronized
	fun setLanguage(value: AppLanguage, persist: Boolean = true) {
		if (value != state.language) state = State(value, loadBundle(value))
		if (persist) runCatching { preferences.put(PREFERENCE_KEY, value.tag) }
	}

	fun text(key: String, vararg arguments: Any?): String {
		val snapshot = state
		val pattern = try {
			snapshot.bundle.getString(key)
		} catch (_: MissingResourceException) {
			return "!$key!"
		}
		return if (arguments.isEmpty()) pattern else MessageFormat(pattern, snapshot.language.locale).format(arguments)
	}

	fun hasKey(key: String): Boolean = state.bundle.containsKey(key)

	private fun preferredLanguage(): AppLanguage {
		val saved = runCatching { preferences.get(PREFERENCE_KEY, null) }.getOrNull()
		return AppLanguage.fromTag(saved) ?: AppLanguage.fromLocale(Locale.getDefault())
	}

	private fun loadBundle(language: AppLanguage): ResourceBundle =
		ResourceBundle.getBundle(BUNDLE_NAME, language.locale, UTF8Control)

	private object UTF8Control : ResourceBundle.Control() {
		override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
	}
}

fun tr(key: String, vararg arguments: Any?): String = I18n.text(key, *arguments)
