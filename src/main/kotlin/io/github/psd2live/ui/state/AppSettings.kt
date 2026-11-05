package io.github.psd2live.ui.state

import io.github.psd2live.ui.theme.CustomTheme
import io.github.psd2live.ui.theme.ThemeCatalog
import io.github.psd2live.ui.theme.ThemeCodec
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.security.MessageDigest
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

	/** The window's windowed size in dp, the one every other mode returns to; 1280 x 820 the first time. */
	val windowWidth: Float get() = runCatching { preferences.getFloat("window_width", 1280f) }.getOrDefault(1280f).let { if (it.isFinite()) it.coerceIn(640f, 8192f) else 1280f }
	val windowHeight: Float get() = runCatching { preferences.getFloat("window_height", 820f) }.getOrDefault(820f).let { if (it.isFinite()) it.coerceIn(400f, 8192f) else 820f }

	fun rememberWindowSize(width: Float, height: Float) {
		if (!width.isFinite() || !height.isFinite()) return
		runCatching {
			preferences.putFloat("window_width", width); preferences.putFloat("window_height", height)
			preferences.flush()
		}
	}

	/** A stored mode by name; before modes, only whether the window was maximized was kept. */
	internal fun windowModeOf(stored: String?, legacyMaximized: Boolean): WindowMode =
		WindowMode.entries.firstOrNull { it.name == stored } ?: if (legacyMaximized) WindowMode.MAXIMIZED else WindowMode.WINDOWED

	private fun storedWindowMode(key: String): WindowMode? =
		runCatching { preferences.get(key, null) }.getOrNull()?.let { name -> WindowMode.entries.firstOrNull { it.name == name } }

	private val legacyMaximized: Boolean get() = runCatching { preferences.getBoolean("window_maximized", false) }.getOrDefault(false)

	private val windowModeState = kotlinx.coroutines.flow.MutableStateFlow(
		windowModeOf(runCatching { preferences.get("window_mode", null) }.getOrNull(), legacyMaximized))

	/** The main window's mode; the window follows it as it changes, and opens in it next time. */
	val windowModeFlow: kotlinx.coroutines.flow.StateFlow<WindowMode> get() = windowModeState

	var windowMode: WindowMode
		get() = windowModeState.value
		set(value) {
			windowModeState.value = value
			runCatching {
				preferences.put("window_mode", value.name)
				if (value != WindowMode.FULLSCREEN) preferences.put("window_restore_mode", value.name)
			}
		}

	/** The windowed or maximized mode last used, which leaving full screen returns to. */
	val restoreMode: WindowMode
		get() = storedWindowMode("window_restore_mode")?.takeIf { it != WindowMode.FULLSCREEN } ?: windowModeOf(null, legacyMaximized)

	/** Full screen, or back out of it. */
	fun toggleFullscreen() {
		windowMode = if (windowMode == WindowMode.FULLSCREEN) restoreMode else WindowMode.FULLSCREEN
	}

	/** The title bar's maximize button: windowed and maximized swap; from full screen, back to where it came from. */
	fun toggleMaximized() {
		windowMode = when (windowMode) {
			WindowMode.WINDOWED -> WindowMode.MAXIMIZED
			WindowMode.MAXIMIZED -> WindowMode.WINDOWED
			else -> restoreMode
		}
	}

	/** Wide enough for names like "Angle X" or "Eye L Open"; 40 dp cut nearly every name to its first word. */
	const val DEFAULT_PARAMETER_NAME_WIDTH = 96f

	/** Panel dimensions are in dp; like colour dragging, writes sync asynchronously. */
	var parameterNameWidth: Float
		get() = runCatching { preferences.getFloat("parameter_name_width", DEFAULT_PARAMETER_NAME_WIDTH) }
			.getOrDefault(DEFAULT_PARAMETER_NAME_WIDTH).let { if (it.isFinite()) it.coerceIn(24f, 240f) else DEFAULT_PARAMETER_NAME_WIDTH }
		set(value) {
			if (value.isFinite()) runCatching { preferences.putFloat("parameter_name_width", value.coerceIn(24f, 240f)) }
		}

	private val graphicsState = kotlinx.coroutines.flow.MutableStateFlow(runCatching {
		io.github.psd2live.render.GraphicsOptions.stored { key -> preferences.get(key, null) }
	}.getOrDefault(io.github.psd2live.render.GraphicsOptions()))

	/** How the window and canvases draw; what waits for the next start is said in [io.github.psd2live.render.GraphicsOptions]. */
	val graphicsFlow: kotlinx.coroutines.flow.StateFlow<io.github.psd2live.render.GraphicsOptions> get() = graphicsState

	var graphics: io.github.psd2live.render.GraphicsOptions
		get() = graphicsState.value
		set(value) {
			val previous = graphicsState.value
			graphicsState.value = value
			softwareCanvasState.value = value.renderPath != io.github.psd2live.render.RenderPath.GPU
			previewFrameRateState.value = value.frameRateLimit.fps
			io.github.psd2live.render.GraphicsSettings.update(value)
			runCatching { for ((key, stored) in value.toStored()) preferences.put(key, stored) }
			// Windows keeps the card per program in its own settings; written now, read when PSD2Live next starts.
			if (value.gpu != previous.gpu && io.github.psd2live.render.GraphicsSettings.isWindows) {
				Thread({ io.github.psd2live.render.GraphicsDevices.applyWindowsPreference(value.gpu) }, "psd2live-gpu-preference").apply { isDaemon = true }.start()
			}
		}

	private val softwareCanvasState = kotlinx.coroutines.flow.MutableStateFlow(graphicsState.value.renderPath != io.github.psd2live.render.RenderPath.GPU)

	/** The editing canvas paints in software instead of on its GPU renderer ([graphics]' render path). */
	val softwareCanvasFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = softwareCanvasState

	val softwareCanvas: Boolean get() = softwareCanvasState.value

	private val previewBackendState = kotlinx.coroutines.flow.MutableStateFlow(
		runCatching { io.github.psd2live.core.PreviewBackend.valueOf(preferences.get("preview_backend", "CUBISM")) }
			.getOrDefault(io.github.psd2live.core.PreviewBackend.CUBISM),
	)

	/** The runtime the preview canvases draw with; Cubism by default, p2lrt when Cubism cannot start anyway. */
	var previewBackend: io.github.psd2live.core.PreviewBackend
		get() = previewBackendState.value
		set(value) {
			previewBackendState.value = value
			runCatching { preferences.put("preview_backend", value.name) }
		}

	private val previewAdvancedState = kotlinx.coroutines.flow.MutableStateFlow(
		runCatching { preferences.getBoolean("preview_advanced", false) }.getOrDefault(false),
	)

	/** The p2lrt runtime's advanced mode in the preview: skinning along arcs, exact links, live simulation. */
	val previewAdvancedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = previewAdvancedState

	var previewAdvanced: Boolean
		get() = previewAdvancedState.value
		set(value) {
			previewAdvancedState.value = value
			runCatching { preferences.putBoolean("preview_advanced", value) }
		}

	private val canvasRailState = kotlinx.coroutines.flow.MutableStateFlow(
		runCatching { preferences.get("canvas_rail", null)?.let(CanvasRailEntry::parse) }.getOrNull() ?: CanvasRailEntry.Defaults,
	)

	/** The display toggles pinned to the canvas's display rail; the View menu carries all of them. */
	val canvasRailFlow: kotlinx.coroutines.flow.StateFlow<Set<CanvasRailEntry>> get() = canvasRailState

	var canvasRail: Set<CanvasRailEntry>
		get() = canvasRailState.value
		set(value) {
			canvasRailState.value = value
			runCatching { preferences.put("canvas_rail", CanvasRailEntry.encode(value)) }
		}

	/** The preview frame rates offered; 0 follows the display. */
	val previewFrameRates = io.github.psd2live.render.FrameRateLimit.entries.map { it.fps }

	private val previewFrameRateState = kotlinx.coroutines.flow.MutableStateFlow(graphicsState.value.frameRateLimit.fps)

	/** The preview's frame rate cap; 0 (the default) renders on every display refresh. Part of [graphics]. */
	val previewFrameRateFlow: kotlinx.coroutines.flow.StateFlow<Int> get() = previewFrameRateState

	var previewFrameRate: Int
		get() = previewFrameRateState.value
		set(value) {
			graphics = graphics.copy(frameRateLimit = io.github.psd2live.render.FrameRateLimit.ofFps(value))
		}

	/** Whether edits in the simulation panel bake again as they commit; off by default, as a bake takes seconds. */
	var simulationAutoBake: Boolean
		get() = runCatching { preferences.getBoolean("simulation_auto_bake", false) }.getOrDefault(false)
		set(value) {
			runCatching { preferences.putBoolean("simulation_auto_bake", value) }
		}

	private fun parameterPadHeightKey(horizontalId: String, verticalId: String): String {
		val pair = "${horizontalId.length}:$horizontalId$verticalId"
		val digest = MessageDigest.getInstance("SHA-256").digest(pair.toByteArray(Charsets.UTF_8))
		return "param_pad_h_" + digest.joinToString("") { "%02x".format(it) }
	}

	fun parameterPadHeight(horizontalId: String, verticalId: String): Float = runCatching {
		preferences.getFloat(parameterPadHeightKey(horizontalId, verticalId), 84f)
	}.getOrDefault(84f).let { if (it.isFinite()) it.coerceIn(64f, 320f) else 84f }

	fun setParameterPadHeight(horizontalId: String, verticalId: String, height: Float) {
		if (height.isFinite()) runCatching {
			preferences.putFloat(parameterPadHeightKey(horizontalId, verticalId), height.coerceIn(64f, 320f))
		}
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

	// ---------------------------------------------------------------------------------------
	// Colour theme
	//
	// Each custom theme lives under its own key: a Preferences value is capped at 8 KiB, which a
	// list of fully overridden themes would outgrow.
	// ---------------------------------------------------------------------------------------

	private const val KEY_THEME_ID = "theme_id"
	private const val KEY_THEME_LAST_DARK = "theme_last_dark"
	private const val KEY_THEME_LAST_LIGHT = "theme_last_light"
	private const val KEY_THEME_CUSTOM_ORDER = "theme_custom_order"
	private const val THEME_CUSTOM_PREFIX = "theme_custom_"

	/**
	 * The selected theme. Before themes existed only [KEY_DARK_THEME] was stored, so a missing id
	 * falls back to whichever of the two built-ins that flag named.
	 */
	var themeId: String
		get() = runCatching {
			preferences.get(KEY_THEME_ID, null)
				?: if (preferences.getBoolean(KEY_DARK_THEME, true)) ThemeCatalog.DARK_ID else ThemeCatalog.LIGHT_ID
		}.getOrDefault(ThemeCatalog.DARK_ID)
		set(value) {
			runCatching {
				preferences.put(KEY_THEME_ID, value)
				preferences.remove(KEY_DARK_THEME)
				preferences.flush()
			}
		}

	/** The theme the title-bar toggle switches to for [dark]: the one last used on that side. */
	fun lastThemeId(dark: Boolean): String {
		val fallback = if (dark) ThemeCatalog.DARK_ID else ThemeCatalog.LIGHT_ID
		return runCatching {
			preferences.get(if (dark) KEY_THEME_LAST_DARK else KEY_THEME_LAST_LIGHT, fallback)
		}.getOrDefault(fallback)
	}

	fun rememberLastThemeId(dark: Boolean, id: String) {
		runCatching {
			preferences.put(if (dark) KEY_THEME_LAST_DARK else KEY_THEME_LAST_LIGHT, id)
			preferences.flush()
		}
	}

	fun customThemes(): List<CustomTheme> = runCatching {
		val order = preferences.get(KEY_THEME_CUSTOM_ORDER, "").split(',').filter { it.isNotBlank() }
		order.mapNotNull { id ->
			preferences.get(THEME_CUSTOM_PREFIX + id, null)?.let { ThemeCodec.decode(it, id) }
		}
	}.getOrDefault(emptyList())

	fun saveCustomThemes(themes: List<CustomTheme>) {
		runCatching {
			val keep = themes.map { THEME_CUSTOM_PREFIX + it.id }.toSet()
			preferences.keys()
				.filter { it.startsWith(THEME_CUSTOM_PREFIX) && it != KEY_THEME_CUSTOM_ORDER && it !in keep }
				.forEach { preferences.remove(it) }
			for (theme in themes) preferences.put(THEME_CUSTOM_PREFIX + theme.id, ThemeCodec.encode(theme))
			preferences.put(KEY_THEME_CUSTOM_ORDER, themes.joinToString(",") { it.id })
			preferences.flush()
		}
	}

	private const val KEY_CANVAS_BG_KIND = "canvas_bg_kind"
	private const val KEY_CANVAS_BG_SOLID = "canvas_bg_solid"
	private const val KEY_CANVAS_BG_CHECKER_LIGHT = "canvas_bg_checker_light"
	private const val KEY_CANVAS_BG_CHECKER_DARK = "canvas_bg_checker_dark"
	private const val KEY_CANVAS_BG_CHECKER_SIZE = "canvas_bg_checker_size"

	/**
	 * Colours are stored as 0xRRGGBB, with -1 for "follow the theme". The setter does not flush: the
	 * colour picker writes on every drag move, and Preferences syncs on its own and at exit.
	 */
	var canvasBackground: CanvasBackground
		get() = runCatching {
			fun color(key: String) = preferences.getInt(key, -1).takeIf { it in 0..0xFFFFFF }
			CanvasBackground(
				kind = CanvasBackgroundKind.fromId(preferences.get(KEY_CANVAS_BG_KIND, null)),
				solidColor = color(KEY_CANVAS_BG_SOLID),
				checkerLight = color(KEY_CANVAS_BG_CHECKER_LIGHT),
				checkerDark = color(KEY_CANVAS_BG_CHECKER_DARK),
				checkerSize = preferences.getInt(KEY_CANVAS_BG_CHECKER_SIZE, CanvasBackground.DEFAULT_CHECKER_SIZE)
					.coerceIn(4, 128),
			)
		}.getOrDefault(CanvasBackground())
		set(value) {
			runCatching {
				preferences.put(KEY_CANVAS_BG_KIND, value.kind.id)
				preferences.putInt(KEY_CANVAS_BG_SOLID, value.solidColor ?: -1)
				preferences.putInt(KEY_CANVAS_BG_CHECKER_LIGHT, value.checkerLight ?: -1)
				preferences.putInt(KEY_CANVAS_BG_CHECKER_DARK, value.checkerDark ?: -1)
				preferences.putInt(KEY_CANVAS_BG_CHECKER_SIZE, value.checkerSize)
			}
		}

	private const val KEY_RECENT_FILES = "recent_files"

	/** Offers the splits of the layers a layer import adds. */
	var autoDetectMeshSplitsOnImport: Boolean
		get() = promptEnabled(AppPrompt.MESH_SPLITS_ON_IMPORT)
		set(value) = setPromptEnabled(AppPrompt.MESH_SPLITS_ON_IMPORT, value)

	private const val KEY_START_CHOICES = "start_choices"

	/** How [page]'s panels were last arranged; the default arrangement the first time. */
	internal fun pageArrangement(page: AppPage): PageArrangement = runCatching {
		val key = "page_${page.name.lowercase()}"
		PageArrangement(
			layoutJson = preferences.get("${key}_layout", null),
			hiddenModules = preferences.get("${key}_hidden", "").split(',').filter { it in page.modules }.toSet(),
		)
	}.getOrDefault(PageArrangement())

	internal fun setPageArrangement(page: AppPage, arrangement: PageArrangement) {
		val key = "page_${page.name.lowercase()}"
		runCatching {
			// A preference value holds at most 8 KB; a layout past that is left at its previous arrangement.
			val layout = arrangement.layoutJson
			if (layout == null) preferences.remove("${key}_layout")
			else if (layout.length < java.util.prefs.Preferences.MAX_VALUE_LENGTH) preferences.put("${key}_layout", layout)
			preferences.put("${key}_hidden", arrangement.hiddenModules.joinToString(","))
		}
	}

	/** The start page's sizes in dp, as last set: the recent PSD strip's tiles and the recent files' cards. */
	internal fun startPanelSize(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
		runCatching { preferences.getFloat("start_$key", default) }.getOrDefault(default).let { if (it.isFinite()) it.coerceIn(range) else default }

	internal fun setStartPanelSize(key: String, value: Float) {
		if (value.isFinite()) runCatching { preferences.putFloat("start_$key", value) }
	}

	/** The presets a PSD import applies, as last set on the start page; the default set the first time. */
	internal var startChoices: StartPresetChoices
		get() = StartPresetChoices.decode(runCatching { preferences.get(KEY_START_CHOICES, null) }.getOrNull())
			?: StartQuickPreset.RECOMMENDED.choices
		set(value) {
			runCatching { preferences.put(KEY_START_CHOICES, value.encode()) }
		}

	fun promptEnabled(prompt: AppPrompt): Boolean =
		runCatching { preferences.getBoolean(prompt.key, true) }.getOrDefault(true)

	fun setPromptEnabled(prompt: AppPrompt, enabled: Boolean) {
		runCatching {
			preferences.putBoolean(prompt.key, enabled)
			preferences.flush()
		}
	}

	/** The prompts the user turned off with "Don't show again". */
	fun mutedPrompts(): Set<AppPrompt> = AppPrompt.entries.filterTo(mutableSetOf()) { !promptEnabled(it) }

	private const val KEY_LOG_COLUMNS = "log_columns"

	/** The log dock's column order, widths and hidden columns, as the dock encodes them; null for the defaults. */
	var logColumns: String?
		get() = runCatching { preferences.get(KEY_LOG_COLUMNS, null) }.getOrNull()
		set(value) {
			runCatching {
				if (value == null) preferences.remove(KEY_LOG_COLUMNS) else preferences.put(KEY_LOG_COLUMNS, value)
				preferences.flush()
			}
		}

	private const val KEY_HIERARCHY_SORT = "hierarchy_sort"

	/** The hierarchy tree's sibling order, as the tree encodes it; null for the model's order. */
	var hierarchySort: String?
		get() = runCatching { preferences.get(KEY_HIERARCHY_SORT, null) }.getOrNull()
		set(value) {
			runCatching {
				if (value == null) preferences.remove(KEY_HIERARCHY_SORT) else preferences.put(KEY_HIERARCHY_SORT, value)
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
		val parts = splitKeyBindingList(raw)
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
		// Missing files stay listed and are hidden where shown (RecentFileProbe): a drive plugged back
		// in brings them back, and checking here would stall the caller on an offline share.
		saveRecentFiles(rememberRecentPaths(recentFiles(), normalized))
	}

	fun clearRecentFiles() = saveRecentFiles(emptyList())

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
			// Custom themes are the user's work, not a preference, so a reset only deselects them.
			preferences.remove(KEY_THEME_ID)
			preferences.remove(KEY_THEME_LAST_DARK)
			preferences.remove(KEY_THEME_LAST_LIGHT)
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

/**
 * A prompt the user can turn off from the prompt itself ("Don't show again") and back on in Settings › Prompts.
 * [key] is its preference; [labelKey] names it in Settings, phrased as what is shown when it is on.
 */
enum class AppPrompt(internal val key: String, val labelKey: String) {
	/** The splits offered after a layer import. */
	MESH_SPLITS_ON_IMPORT("auto_detect_mesh_splits_on_import", "editor.meshSplit.promptOnImport"),
	/** The dialog that reports a finished export and offers its folder. */
	EXPORT_SUCCESS("prompt_export_success", "prompt.exportSuccess"),
}
