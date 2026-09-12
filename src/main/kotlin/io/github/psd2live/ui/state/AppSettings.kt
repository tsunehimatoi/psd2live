package io.github.psd2live.ui.state

import java.awt.GraphicsEnvironment
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

	fun resetToDefaults() {
		hasCustomUiScale = false
		runCatching {
			preferences.remove(KEY_UI_SCALE)
			preferences.remove(KEY_FONT_SCALE)
			preferences.remove(KEY_CUSTOM_SCALE_SET)
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

