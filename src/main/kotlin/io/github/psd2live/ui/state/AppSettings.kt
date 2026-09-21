package io.github.psd2live.ui.state

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * Global user preferences and display scaling configuration.
 * Persisted using standard Java Preferences API.
 */
object AppSettings {
	private const val PREFS_NODE_NAME = "io.github.psd2live.settings"
	private const val KEY_UI_SCALE = "ui_scale"
	private const val KEY_FONT_SCALE = "font_scale"
	private const val KEY_CUSTOM_SCALE_SET = "has_custom_ui_scale"
	private const val KEY_DARK_THEME = "dark_theme"

	private val preferences by lazy {
		Preferences.userRoot().node(PREFS_NODE_NAME)
	}

	data class DisplayMetrics(
		val physicalWidth: Int,
		val physicalHeight: Int,
		val systemScalePercent: Int,
		val recommendedScale: Float,
	)

	val currentDisplayMetrics: DisplayMetrics by lazy {
		detectDisplayMetrics()
	}

	var hasCustomUiScale: Boolean
		get() = runCatching { preferences.getBoolean(KEY_CUSTOM_SCALE_SET, false) }.getOrDefault(false)
		private set(value) {
			runCatching { preferences.putBoolean(KEY_CUSTOM_SCALE_SET, value) }
		}

	var uiScale: Float
		get() {
			val saved = runCatching {
				if (hasCustomUiScale) preferences.getFloat(KEY_UI_SCALE, -1f) else -1f
			}.getOrDefault(-1f)
			return if (saved in 0.5f..4.0f) saved else detectOptimalScale()
		}
		set(value) {
			val clamped = value.coerceIn(0.75f, 3.0f)
			hasCustomUiScale = true
			runCatching {
				preferences.putFloat(KEY_UI_SCALE, clamped)
				preferences.flush()
			}
		}

	var fontScale: Float
		get() {
			val saved = runCatching { preferences.getFloat(KEY_FONT_SCALE, 1.0f) }.getOrDefault(1.0f)
			return saved.coerceIn(0.85f, 1.5f)
		}
		set(value) {
			val clamped = value.coerceIn(0.85f, 1.5f)
			runCatching {
				preferences.putFloat(KEY_FONT_SCALE, clamped)
				preferences.flush()
			}
		}

	/** Dark chrome is the shipped look; light is an explicit preference. */
	var darkTheme: Boolean
		get() = runCatching { preferences.getBoolean(KEY_DARK_THEME, true) }.getOrDefault(true)
		set(value) {
			runCatching {
				preferences.putBoolean(KEY_DARK_THEME, value)
				preferences.flush()
			}
		}

	private const val KEY_CLICK_TO_SELECT_LAYER = "click_to_select_layer"
	private const val KEY_RECENT_FILES = "recent_files"

	var clickToSelectLayer: Boolean
		get() = runCatching { preferences.getBoolean(KEY_CLICK_TO_SELECT_LAYER, true) }.getOrDefault(true)
		set(value) {
			runCatching {
				preferences.putBoolean(KEY_CLICK_TO_SELECT_LAYER, value)
				preferences.flush()
			}
		}

	// ---------------------------------------------------------------------------------------
	// Keyboard shortcuts
	//
	// Only the *overrides* are stored, relative to the active preset, so restoring a single action
	// is just removing a key and switching presets cannot inherit a stale customisation.
	// ---------------------------------------------------------------------------------------

	private const val KEYMAP_PREFIX = "keymap_"
	private const val KEY_KEYMAP_VERSION = "${KEYMAP_PREFIX}version"
	private const val KEY_KEYMAP_PRESET = "${KEYMAP_PREFIX}preset"
	private const val KEYMAP_OVERRIDE_PREFIX = "${KEYMAP_PREFIX}override_"

	/** Bump when the override encoding changes; a newer value makes us ignore every override. */
	private const val KEYMAP_VERSION = 1

	/** Written for an action the user deliberately unbound, as opposed to never having touched it. */
	private const val UNBOUND_MARKER = "-"

	internal var keymapPreset: KeymapPreset
		get() = runCatching { KeymapPreset.fromId(preferences.get(KEY_KEYMAP_PRESET, null)) }
			.getOrDefault(KeymapPreset.PHOTOSHOP)
		set(value) {
			runCatching {
				preferences.put(KEY_KEYMAP_PRESET, value.id)
				preferences.flush()
			}
		}

	/**
	 * The user's per-action overrides for the active preset. Corrupt entries, unknown action names
	 * and values written by a newer version are all skipped rather than thrown — a broken preference
	 * store must never keep the app from starting.
	 */
	internal fun keymapOverrides(): Map<ShortcutAction, List<KeyBinding>> {
		return runCatching {
			if (preferences.getInt(KEY_KEYMAP_VERSION, KEYMAP_VERSION) > KEYMAP_VERSION) {
				return emptyMap<ShortcutAction, List<KeyBinding>>()
			}
			val result = mutableMapOf<ShortcutAction, List<KeyBinding>>()
			for (key in preferences.keys()) {
				if (!key.startsWith(KEYMAP_OVERRIDE_PREFIX)) continue
				val name = key.removePrefix(KEYMAP_OVERRIDE_PREFIX)
				val action = ShortcutAction.entries.firstOrNull { it.name == name } ?: continue
				val raw = preferences.get(key, null) ?: continue
				result[action] = parseOverrideList(raw) ?: continue
			}
			result
		}.getOrDefault(emptyMap())
	}

	internal fun putKeymapOverride(action: ShortcutAction, bindings: List<KeyBinding>) {
		runCatching {
			preferences.putInt(KEY_KEYMAP_VERSION, KEYMAP_VERSION)
			preferences.put(
				KEYMAP_OVERRIDE_PREFIX + action.name,
				if (bindings.isEmpty()) UNBOUND_MARKER else bindings.joinToString(";") { it.format() },
			)
			preferences.flush()
		}
	}

	internal fun removeKeymapOverride(action: ShortcutAction) {
		runCatching {
			preferences.remove(KEYMAP_OVERRIDE_PREFIX + action.name)
			preferences.flush()
		}
	}

	/** Drops the preset and every override, returning the keymap to the shipped defaults. */
	internal fun clearKeymap() {
		runCatching {
			preferences.keys().filter { it.startsWith(KEYMAP_PREFIX) }.forEach { preferences.remove(it) }
			preferences.flush()
		}
	}

	/** Null when [raw] is malformed — callers then fall back to the preset default for that action. */
	private fun parseOverrideList(raw: String): List<KeyBinding>? {
		if (raw == UNBOUND_MARKER) return emptyList()
		val parts = raw.split(';')
		val parsed = ArrayList<KeyBinding>(parts.size)
		for (part in parts) parsed.add(parseKeyBinding(part) ?: return null)
		return parsed
	}

	fun recentFiles(): List<String> {
		val raw = runCatching { preferences.get(KEY_RECENT_FILES, "") }.getOrDefault("")
		return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
	}

	fun rememberRecentFile(path: String) {
		val normalized = normalizeRecentPath(path) ?: return
		val next = rememberRecentPaths(recentFiles(), normalized) { candidate ->
			candidate.equals(normalized, ignoreCase = true) ||
				runCatching { Files.isRegularFile(Path.of(candidate)) }.getOrDefault(false)
		}
		saveRecentFiles(next)
	}

	fun forgetRecentFile(path: String) {
		saveRecentFiles(forgetRecentPath(recentFiles(), normalizeRecentPath(path) ?: path))
	}

	private fun saveRecentFiles(paths: List<String>) {
		runCatching {
			preferences.put(KEY_RECENT_FILES, paths.joinToString("\n"))
			preferences.flush()
		}
	}

	private fun normalizeRecentPath(path: String): String? {
		val trimmed = path.trim()
		if (trimmed.isEmpty() || classifyRecentPath(trimmed) == null) return null
		return runCatching { Path.of(trimmed).toAbsolutePath().normalize().toString() }.getOrDefault(trimmed)
	}

	fun resetToDefaults() {
		hasCustomUiScale = false
		runCatching {
			preferences.remove(KEY_UI_SCALE)
			preferences.remove(KEY_FONT_SCALE)
			preferences.remove(KEY_CUSTOM_SCALE_SET)
			preferences.remove(KEY_DARK_THEME)
			preferences.remove(KEY_CLICK_TO_SELECT_LAYER)
			// Enumerated by prefix so there is no action-name list to keep up to date.
			preferences.keys().filter { it.startsWith(KEYMAP_PREFIX) }.forEach { preferences.remove(it) }
			preferences.flush()
		}
	}

	fun defaultUiScale(): Float = detectOptimalScale()

	fun detectDisplayMetrics(): DisplayMetrics {
		return runCatching {
			val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
			val device = ge.defaultScreenDevice
			val mode = device.displayMode
			val config = device.defaultConfiguration
			val transform = config.defaultTransform
			val awtScale = transform.scaleX.toFloat().coerceAtLeast(1.0f)
			val w = mode.width
			val h = mode.height
			val systemPercent = (awtScale * 100).toInt()

			val recommended = when {
				awtScale >= 1.5f -> 1.0f
				w >= 3840 && awtScale <= 1.25f -> 1.5f
				w >= 2560 && awtScale <= 1.0f -> 1.25f
				w >= 1920 && awtScale <= 1.0f -> 1.15f
				else -> 1.0f
			}
			DisplayMetrics(
				physicalWidth = w,
				physicalHeight = h,
				systemScalePercent = systemPercent,
				recommendedScale = recommended,
			)
		}.getOrDefault(DisplayMetrics(1920, 1080, 100, 1.0f))
	}

	fun detectOptimalScale(): Float {
		return detectDisplayMetrics().recommendedScale
	}
}

