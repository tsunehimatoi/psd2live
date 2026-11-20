package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.render.BackgroundFrameRate
import io.github.psd2live.render.CanvasAntialiasing
import io.github.psd2live.render.FrameRateLimit
import io.github.psd2live.render.GpuCacheSize
import io.github.psd2live.render.GpuChoice
import io.github.psd2live.render.GpuDevice
import io.github.psd2live.render.GraphicsChoice
import io.github.psd2live.render.GraphicsDevices
import io.github.psd2live.render.GraphicsOptions
import io.github.psd2live.render.GraphicsSettings
import io.github.psd2live.render.QualityPreset
import io.github.psd2live.render.RenderPath
import io.github.psd2live.render.SkiaGpu
import io.github.psd2live.render.TextureFiltering
import io.github.psd2live.render.ThreadedGl
import io.github.psd2live.render.VsyncMode
import io.github.psd2live.render.WindowAntialiasing
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The graphics settings, laid out as a game's: a quality preset over the display, antialiasing and texture, and
 * advanced settings, each a labelled choice with what the chosen value does under it. What applies only at the
 * next start says so while it differs from what this process started with.
 */
@Composable
internal fun SettingsGraphicsSection() {
	val colors = LocalToolColors.current
	val graphics by AppSettings.graphicsFlow.collectAsState()
	val iconBitmapCache by AppSettings.iconBitmapCacheFlow.collectAsState()
	val gpuStatus by SkiaGpu.status.collectAsState()
	val startup = GraphicsSettings.startup
	fun update(change: (GraphicsOptions) -> GraphicsOptions) { AppSettings.graphics = change(AppSettings.graphics) }
	// The window and the canvases draw on the GPU: what only they use is moot in software.
	val gpuWindow = graphics.renderPath != RenderPath.SOFTWARE
	val gpuCanvas = graphics.renderPath == RenderPath.GPU

	SettingsSectionDescription(tr("settings.graphics.desc"))

	SettingsGroup(tr("settings.graphics.quality")) {
		val preset = QualityPreset.of(graphics)
		GraphicsRow(tr("settings.graphics.preset"), QualityPreset.entries, preset, tr("settings.graphics.preset.desc"), enabled = gpuWindow,
			optionEnabled = { it != QualityPreset.CUSTOM }) { chosen -> update { chosen.applyTo(it) } }
	}

	SettingsGroup(tr("settings.graphics.display")) {
		val status = when {
			startup?.softwareWindow == true -> tr("settings.graphics.status.softwareWindow")
			graphics.renderPath != RenderPath.GPU -> tr("dialog.settings.canvas.rendererSoftware")
			else -> when (val current = gpuStatus) {
				is SkiaGpu.Status.Ready -> tr("dialog.settings.canvas.rendererGpu", current.description)
				is SkiaGpu.Status.Unavailable -> tr("dialog.settings.canvas.rendererUnavailable", current.reason)
				SkiaGpu.Status.Starting -> tr("dialog.settings.canvas.rendererStarting")
			}
		}
		GraphicsRow(tr("settings.graphics.path"), RenderPath.entries, graphics.renderPath,
			tr(graphics.renderPath.descriptionKey) + "\n" + status) { path -> update { it.copy(renderPath = path) } }

		// Listing the cards asks lspci, which may take a moment: off the UI thread, once.
		val devices by produceState(emptyList<GpuDevice>()) { value = withContext(Dispatchers.IO) { GraphicsDevices.list() } }
		val cards = listOf(GpuChoice.Auto, GpuChoice.HighPerformance, GpuChoice.PowerSaving) + devices.map { GpuChoice.Device(it.id) }
		// A card chosen before and gone now still shows, so the choice can be seen and undone.
		val shownCards = if (graphics.gpu in cards) cards else cards + graphics.gpu
		fun cardLabel(choice: GpuChoice): String = when (choice) {
			GpuChoice.Auto -> tr("settings.graphics.gpu.auto")
			GpuChoice.HighPerformance -> tr("settings.graphics.gpu.highPerformance")
			GpuChoice.PowerSaving -> tr("settings.graphics.gpu.powerSaving")
			is GpuChoice.Device -> devices.firstOrNull { it.id == choice.id }?.name ?: choice.id
		}
		val cardHint = when (val card = graphics.gpu) {
			GpuChoice.Auto -> tr("settings.graphics.gpu.auto.desc")
			GpuChoice.HighPerformance -> tr("settings.graphics.gpu.highPerformance.desc")
			GpuChoice.PowerSaving -> tr("settings.graphics.gpu.powerSaving.desc")
			is GpuChoice.Device -> {
				val device = devices.firstOrNull { it.id == card.id }
				when {
					device == null -> tr("settings.graphics.gpu.device.missing")
					device.primary -> tr("settings.graphics.gpu.device.primary", device.id)
					else -> tr("settings.graphics.gpu.device", device.id)
				}
			}
		}
		val platformHint = when {
			GraphicsSettings.isWindows -> if (GraphicsDevices.windowsLauncher() != null) tr("settings.graphics.gpu.windows") else tr("settings.graphics.gpu.windowsUnpackaged")
			GraphicsSettings.isLinux -> GraphicsSettings.appliedEnvironment.takeIf { it.isNotEmpty() }
				?.let { environment -> tr("settings.graphics.gpu.linuxApplied", environment.entries.joinToString(" ") { "${it.key}=${it.value}" }) }
				?: tr("settings.graphics.gpu.linux")
			else -> tr("settings.graphics.gpu.mac")
		}
		SettingsChoiceRow(tr("settings.graphics.gpu"), shownCards, graphics.gpu, ::cardLabel, "$cardHint\n$platformHint", enabled = gpuWindow) { card -> update { it.copy(gpu = card) } }

		val vsyncModes = VsyncMode.offered(GraphicsSettings.isWindows)
		// Adaptive stored on Windows and read elsewhere (a shared home) acts as on.
		val vsync = graphics.vsync.takeIf { it in vsyncModes } ?: VsyncMode.ON
		GraphicsRow(tr("settings.graphics.vsync"), vsyncModes, vsync,
			tr(if (vsync == VsyncMode.OFF && GraphicsSettings.isLinux) "settings.graphics.vsync.off.linux.desc" else vsync.descriptionKey),
			enabled = gpuWindow) { mode -> update { it.copy(vsync = mode) } }

		GraphicsRow(tr("settings.graphics.frameRate"), FrameRateLimit.entries, graphics.frameRateLimit) { limit -> update { it.copy(frameRateLimit = limit) } }
		GraphicsRow(tr("settings.graphics.backgroundFrameRate"), BackgroundFrameRate.entries, graphics.backgroundFrameRate,
			tr("settings.graphics.frameRate.desc")) { limit -> update { it.copy(backgroundFrameRate = limit) } }

		if (GraphicsSettings.isLinux) {
			GraphicsRow(tr("settings.graphics.threadedGl"), ThreadedGl.entries, graphics.threadedGl, tr("settings.graphics.threadedGl.desc"),
				enabled = gpuWindow) { threaded -> update { it.copy(threadedGl = threaded) } }
		}
	}

	SettingsGroup(tr("settings.graphics.antialiasing")) {
		GraphicsRow(tr("settings.graphics.windowAa"), WindowAntialiasing.entries, graphics.windowAntialiasing,
			tr(graphics.windowAntialiasing.descriptionKey), enabled = gpuWindow) { aa -> update { it.copy(windowAntialiasing = aa) } }
		GraphicsRow(tr("settings.graphics.canvasAa"), CanvasAntialiasing.entries, graphics.canvasAntialiasing,
			tr(graphics.canvasAntialiasing.descriptionKey), enabled = gpuCanvas) { aa -> update { it.copy(canvasAntialiasing = aa) } }
		GraphicsRow(tr("settings.graphics.texture"), TextureFiltering.entries, graphics.textureFiltering,
			tr("settings.graphics.texture.desc"), enabled = gpuCanvas) { filtering -> update { it.copy(textureFiltering = filtering) } }
	}

	SettingsGroup(tr("settings.graphics.advanced")) {
		SettingsRow(tr("settings.graphics.windowCache"), description = tr("settings.graphics.windowCache.desc")) {
			CompactCheckbox(checked = graphics.windowCache, onCheckedChange = { on -> update { it.copy(windowCache = on) } },
				enabled = gpuWindow, label = tr("settings.graphics.windowCache.label"))
		}
		SettingsRow(tr("settings.graphics.iconCache"), description = tr("settings.graphics.iconCache.desc")) {
			CompactCheckbox(checked = iconBitmapCache, onCheckedChange = { AppSettings.iconBitmapCache = it },
				label = tr("settings.graphics.iconCache.label"))
		}
		GraphicsRow(tr("settings.graphics.cache"), GpuCacheSize.entries, graphics.gpuCache, tr("settings.graphics.cache.desc"),
			enabled = gpuWindow) { size -> update { it.copy(gpuCache = size) } }
		SettingsRow(tr("settings.graphics.overlay"), description = tr("settings.graphics.overlay.desc")) {
			CompactCheckbox(checked = graphics.performanceOverlay, onCheckedChange = { on -> update { it.copy(performanceOverlay = on) } },
				enabled = gpuWindow, label = tr("settings.graphics.overlay.label"))
		}
	}

	if (startup != null && graphics.needsRestartFrom(startup)) {
		SettingsHint(tr("settings.graphics.restart"), color = colors.warning)
	}
}

/** The name of [choice] as the settings page shows it. */
private fun choiceLabel(choice: GraphicsChoice): String = choice.labelArg?.let { tr(choice.labelKey, it) } ?: tr(choice.labelKey)

@Composable
private fun <T : GraphicsChoice> GraphicsRow(
	label: String,
	options: List<T>,
	selected: T,
	description: String? = null,
	enabled: Boolean = true,
	optionEnabled: (T) -> Boolean = { true },
	onSelect: (T) -> Unit,
) = SettingsChoiceRow(label, options, selected, ::choiceLabel, description, enabled, optionEnabled, onSelect)

@Composable
private fun <T> SettingsChoiceRow(
	label: String,
	options: List<T>,
	selected: T,
	optionLabel: (T) -> String,
	description: String? = null,
	enabled: Boolean = true,
	optionEnabled: (T) -> Boolean = { true },
	onSelect: (T) -> Unit,
) {
	SettingsRow(label, description = description) {
		CompactSelect(options, selected, optionLabel, onSelect, Modifier.widthIn(max = 260.dp).fillMaxWidth(), enabled, optionEnabled)
	}
}
