package io.github.psd2live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.SettingsTopic
import io.github.psd2live.ui.state.WindowMode
import io.github.psd2live.ui.views.PageBody
import io.github.psd2live.ui.views.PageNavItem
import io.github.psd2live.ui.views.PageNavList
import io.github.psd2live.ui.state.AppPrompt
import io.github.psd2live.ui.state.CaptureCheck
import io.github.psd2live.ui.state.KeyBinding
import io.github.psd2live.ui.state.KeyCapture
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.KeymapPreset
import io.github.psd2live.ui.state.MouseInput
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.wheelBindingOf
import io.github.psd2live.ui.state.ShortcutCategory
import io.github.psd2live.ui.theme.CustomTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.ThemeCatalog
import kotlin.math.roundToInt
import io.github.psd2live.ui.theme.handCursor

/**
 * One panel of the settings page: [module] picks the section, as the page's dock arranges them. Every option applies
 * at once; there is no draft state, since [AppSettings.uiScale] falls back to the detected display scale when the user
 * has never set one and so has no previous value a Cancel could restore.
 *
 * The language comes from the state rather than [I18n]: `tr()` reads a plain field, so without a changed parameter
 * Compose would skip the recomposition and keep showing the old language after a switch made in this very panel.
 */
@Composable
internal fun SettingsPanel(topic: SettingsTopic, state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	Column(Modifier.fillMaxSize()) { PageBody {
		when (topic) {
			SettingsTopic.SCALE -> SettingsScaleSection(
				uiScale = state.uiScale,
				fontScale = state.fontScale,
				metrics = DisplayMetricsCache.value,
				onUiScaleChange = viewModel::setUiScale,
				onFontScaleChange = viewModel::setFontScale,
			)
			SettingsTopic.THEME -> SettingsThemeSection(
				themeId = state.themeId,
				customThemes = state.customThemes,
				onSelect = viewModel::setTheme,
				onDuplicate = viewModel::duplicateTheme,
				onChange = viewModel::updateCustomTheme,
				onDelete = viewModel::deleteCustomTheme,
				onImport = viewModel::importTheme,
			)
			SettingsTopic.LANGUAGE -> SettingsLanguageSection(
				currentLanguage = state.currentLanguage,
				onLanguageChange = viewModel::setLanguage,
			)
			SettingsTopic.CANVAS -> SettingsCanvasSection()
			SettingsTopic.GRAPHICS -> SettingsGraphicsSection()
			SettingsTopic.PROMPTS -> SettingsPromptsSection(
				mutedPrompts = state.mutedPrompts,
				onPromptEnabledChange = viewModel::setPromptEnabled,
				onRestore = viewModel::restorePrompts,
			)
			SettingsTopic.SHORTCUTS -> SettingsShortcutsSection(
				keymap = state.keymap,
				preset = state.keymapPreset,
				capture = state.keyCapture,
				onBeginCapture = viewModel::beginKeyCapture,
				onCaptureBinding = viewModel::captureBinding,
				onRemoveBinding = viewModel::removeKeyBinding,
				onResetBinding = viewModel::resetKeyBinding,
				onPresetChange = viewModel::applyKeymapPreset,
			)
			SettingsTopic.FILES -> SettingsFilesSection(state, viewModel)
			SettingsTopic.MCP -> SettingsMcpSection()
            SettingsTopic.PLUGINS -> SettingsPluginsSection()
		}
	} }
}

/** The settings page's contents: one row per topic, and beneath them the reset of every setting. */
@Composable
internal fun SettingsNav(selected: SettingsTopic, onSelect: (SettingsTopic) -> Unit, onResetAll: () -> Unit) {
	val topics = SettingsTopic.entries
	PageNavList(
		onStep = { step -> onSelect(topics[(topics.indexOf(selected) + step).mod(topics.size)]) },
		footer = { CompactButton(text = tr("dialog.settings.resetDefaults"), onClick = onResetAll, height = 22.dp, modifier = Modifier.fillMaxWidth()) },
	) {
		topics.forEach { topic -> PageNavItem(tr(topic.labelKey), topic == selected, onClick = { onSelect(topic) }) }
	}
}

/**
 * The display readout, taken once: the device query touches GraphicsEnvironment and is slow on multi-monitor Windows
 * setups, and the recommended scale and the display readout should describe the same snapshot.
 */
private object DisplayMetricsCache {
	val value: AppSettings.DisplayMetrics by lazy { AppSettings.detectDisplayMetrics() }
}

/** Every setting back to its default: scale, interaction, shortcuts and theme. */
internal fun resetAllSettings(viewModel: PSD2LiveViewModel) {
	AppSettings.resetToDefaults()
	viewModel.resetZoom()
	viewModel.resetInteractionPrefs()
	viewModel.resetKeymap()
	viewModel.setTheme(AppSettings.themeId)
}

/** One-line caption under a category's sidebar label, explaining what the section does. */
@Composable
internal fun SettingsSectionDescription(text: String) {
	Text(
		text = text,
		style = LocalToolTypography.current.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
		color = LocalToolColors.current.textMuted,
	)
}

/** Interface scales offered as one click; any other is a drag of the slider away. */
private val UiScalePresets = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private fun percent(scale: Float) = "${(scale * 100).roundToInt()}%"

/**
 * Scale and display, as the inspector lays out its sections: the interface scale with its common values and the
 * display's own recommendation, the text size, and what the display reports. Everything applies as it changes.
 */
@Composable
private fun SettingsScaleSection(
	uiScale: Float,
	fontScale: Float,
	metrics: AppSettings.DisplayMetrics,
	onUiScaleChange: (Float) -> Unit,
	onFontScaleChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val fontScalePresets = listOf(
		0.90f to tr("settings.font.compact"),
		1.00f to tr("settings.font.standard"),
		1.15f to tr("settings.font.large"),
		1.30f to tr("settings.font.extraLarge"),
	)
	val recommended = metrics.recommendedScale
	val atRecommended = kotlin.math.abs(uiScale - recommended) < 0.005f

	SettingsSectionDescription(tr("dialog.settings.scale.desc"))

	SettingsGroup(tr("settings.scale.group.ui")) {
		SettingsRow(tr("settings.scale.value")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				CompactSlider(
					value = uiScale,
					onValueChange = { onUiScaleChange((it * 100).roundToInt() / 100f) },
					valueRange = 0.75f..2.50f,
					height = 14.dp,
					modifier = Modifier.weight(1f),
				)
				Text(percent(uiScale), style = typography.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
			}
		}
		SettingsRow(tr("settings.scale.presets")) {
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				for (preset in UiScalePresets) {
					CompactToggleChip(
						text = percent(preset),
						selected = kotlin.math.abs(uiScale - preset) < 0.005f,
						onToggle = { onUiScaleChange(preset) },
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
		SettingsRow(
			tr("settings.scale.recommended"),
			description = tr(if (atRecommended) "settings.scale.recommended.current" else "settings.scale.recommended.hint"),
		) {
			CompactButton(
				text = tr("settings.scale.useRecommended", percent(recommended)),
				onClick = { onUiScaleChange(recommended) },
				enabled = !atRecommended,
				height = 22.dp,
			)
		}
	}

	SettingsGroup(tr("settings.scale.group.font")) {
		SettingsRow(tr("settings.scale.fontSize")) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				for ((scale, label) in fontScalePresets) {
					CompactToggleChip(
						text = label,
						selected = kotlin.math.abs(fontScale - scale) < 0.04f,
						onToggle = { onFontScaleChange(scale) },
						modifier = Modifier.weight(1f),
					)
				}
				Text(percent(fontScale), style = typography.monoSmall.copy(fontSize = 11.sp), color = colors.textMuted,
					textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
			}
		}
	}

	SettingsGroup(tr("settings.scale.group.display")) {
		ReadoutRow(tr("settings.display.resolution"), "${metrics.physicalWidth} × ${metrics.physicalHeight}")
		ReadoutRow(tr("settings.display.systemScale"), "${metrics.systemScalePercent}%")
		ReadoutRow(tr("settings.display.recommended"), percent(recommended))
	}
}

/** A read-only value in a group: its label, then the value in the mono face. */
@Composable
private fun ReadoutRow(label: String, value: String) {
	SettingsRow(label) {
		Text(value, style = LocalToolTypography.current.monoSmall.copy(fontSize = 11.sp), color = LocalToolColors.current.textPrimary)
	}
}

/**
 * Language selection. Rendered as radios rather than a dropdown because there are only a few
 * options and all of them should be visible at once. Each label names its own language
 * (`language.chinese` → "简体中文"), so every option stays readable in any locale.
 */
@Composable
private fun SettingsLanguageSection(
	currentLanguage: AppLanguage,
	onLanguageChange: (AppLanguage) -> Unit,
) {
	SettingsSectionDescription(tr("dialog.settings.language.desc"))

	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		for (language in I18n.supportedLanguages) {
			CompactRadioButton(
				selected = language == currentLanguage,
				onClick = { onLanguageChange(language) },
				label = tr(language.displayNameKey),
				modifier = Modifier.padding(vertical = 4.dp),
			)
		}
	}
}

@Composable
private fun SettingsCanvasSection() {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	SettingsSectionDescription(tr("dialog.settings.canvas.desc"))

	// How the main window sits on its screen; it changes as soon as a mode is picked.
	val windowMode by AppSettings.windowModeFlow.collectAsState()
	SettingsGroup(tr("settings.window.title")) {
		for (mode in WindowMode.entries) {
			Column(Modifier.fillMaxWidth()) {
				CompactRadioButton(
					selected = mode == windowMode,
					onClick = { AppSettings.windowMode = mode },
					label = tr(mode.labelKey),
				)
				Text(
					text = tr(mode.descriptionKey),
					style = typography.caption.copy(fontSize = 10.5.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(start = 24.dp),
				)
			}
		}
	}
}

/**
 * Every prompt that carries "Don't show again", as switches that read "shown when on", and a button that turns
 * them all back on. A prompt muted from the prompt itself is unchecked here.
 */
@Composable
private fun SettingsPromptsSection(
	mutedPrompts: Set<AppPrompt>,
	onPromptEnabledChange: (AppPrompt, Boolean) -> Unit,
	onRestore: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	SettingsSectionDescription(tr("dialog.settings.prompts.desc"))

	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		for (prompt in AppPrompt.entries) {
			CompactCheckbox(
				checked = prompt !in mutedPrompts,
				onCheckedChange = { onPromptEnabledChange(prompt, it) },
				label = tr(prompt.labelKey),
			)
		}
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		CompactButton(text = tr("dialog.settings.prompts.restore"), onClick = onRestore, enabled = mutedPrompts.isNotEmpty())
		if (mutedPrompts.isNotEmpty()) Text(
			tr("dialog.settings.prompts.muted", mutedPrompts.size),
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
		)
	}
}

/**
 * Rebinding for every keyboard shortcut, grouped by category, plus the three shipped presets.
 *
 * Recording is deliberately *not* handled here. Compose runs the preview key pass from the root
 * down, so the root dispatcher would grab a chord like Ctrl+O before this panel ever saw it. The
 * root handler therefore special-cases [capture] and consumes everything while it is set; this
 * section only renders that state and asks for it to start.
 *
 * Edits apply immediately, like every other option in this window.
 */
@Composable
private fun SettingsShortcutsSection(
	keymap: Keymap,
	preset: KeymapPreset,
	capture: KeyCapture?,
	onBeginCapture: (ShortcutAction, Int) -> Unit,
	onCaptureBinding: (KeyBinding) -> Unit,
	onRemoveBinding: (ShortcutAction, Int) -> Unit,
	onResetBinding: (ShortcutAction) -> Unit,
	onPresetChange: (KeymapPreset) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	// Built once per keymap rather than per row: ~130 entries, looked up O(1) on each row.
	val conflicts = remember(keymap) { keymap.conflictIndex() }

	SettingsSectionDescription(tr("dialog.settings.shortcuts.desc"))

	SettingsRow(tr("dialog.settings.shortcuts.preset"), description = tr("dialog.settings.shortcuts.presetHint")) {
		CompactDropdown(
			items = KeymapPreset.entries,
			selectedItem = preset,
			onItemSelected = onPresetChange,
			itemLabel = { tr(it.labelKey) },
			modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth(),
		)
	}

	capture?.let { active ->
		// Staying in capture after a refusal lets the user simply try another chord.
		val rejected = active.feedback != null && active.feedback != CaptureCheck.Ok
		val message = when (val feedback = active.feedback) {
			null, CaptureCheck.Ok ->
				tr("dialog.settings.shortcuts.captureFor", tr(active.action.labelKey))
			CaptureCheck.Reserved -> tr("dialog.settings.shortcuts.reserved")
			CaptureCheck.DuplicateSelf -> tr("dialog.settings.shortcuts.duplicateSelf")
			is CaptureCheck.Conflict ->
				tr("dialog.settings.shortcuts.conflict", tr(feedback.action.labelKey))
			is CaptureCheck.Unsupported -> tr(feedback.messageKey)
		}
		// A status line marked by an edge in its colour, not a box: it is the one thing on the page that is live.
		val tint = if (rejected) colors.error else colors.accent
		Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(2.dp).fillMaxHeight().background(tint))
			Text(
				text = message,
				style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
				color = tint,
				modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
			)
		}
	}

	for (category in ShortcutCategory.entries) {
		SettingsGroup(tr(category.labelKey)) {
			// Rows of a list rather than settings: they sit edge to edge, parted by hairlines.
			Column(Modifier.fillMaxWidth()) {
				val actions = ShortcutAction.entries.filter { it.category == category }
				actions.forEachIndexed { index, action ->
					if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider.copy(alpha = 0.5f)))
					ShortcutRow(
						action = action,
						keymap = keymap,
						capture = capture,
						conflicts = conflicts,
						onBeginCapture = onBeginCapture,
						onCaptureBinding = onCaptureBinding,
						onRemoveBinding = onRemoveBinding,
						onResetBinding = onResetBinding,
					)
				}
			}
		}
	}
}

@Composable
private fun ShortcutRow(
	action: ShortcutAction,
	keymap: Keymap,
	capture: KeyCapture?,
	conflicts: Map<KeyBinding, List<ShortcutAction>>,
	onBeginCapture: (ShortcutAction, Int) -> Unit,
	onCaptureBinding: (KeyBinding) -> Unit,
	onRemoveBinding: (ShortcutAction, Int) -> Unit,
	onResetBinding: (ShortcutAction) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val bindings = keymap.bindingsFor(action)
	// Compares against the preset rather than "has an override on disk": a value that happens to
	// equal the preset default is deliberately not stored, so the two agree.
	val isCustomised = bindings != Keymap.of(keymap.preset).bindingsFor(action)
	val isRecording = capture?.action == action
	// Only a chord shared with an action that can fire alongside this one clashes: the canvas and the
	// timeline never hold focus together.
	fun clashes(binding: KeyBinding) = conflicts[binding].orEmpty().any { it != action && it.scope.overlaps(action.scope) }
	val clash = bindings.any(::clashes)
	// The cell being recorded also takes the mouse: a wheel notch or a button press over it is recorded
	// as a key press anywhere is.
	val mouseCapture: ((KeyBinding) -> Unit)? = if (isRecording) onCaptureBinding else null
	val jump = action.labelIndex
	val label = if (jump == null) tr(action.labelKey) else tr(action.labelKey, jump)

	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp, vertical = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Text(
				text = label,
				modifier = Modifier.weight(1f),
				style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			if (bindings.isEmpty()) {
				KeyChip(
					text = tr("dialog.settings.shortcuts.unbound"),
					tint = colors.textDisabled,
					highlighted = isRecording,
					onClick = { onBeginCapture(action, 0) },
					onMouseBinding = mouseCapture,
				)
			} else {
				bindings.forEachIndexed { index, binding ->
					KeyChip(
						text = if (isRecording && capture.index == index) {
							tr("dialog.settings.shortcuts.captureShort")
						} else {
							binding.format()
						},
						tint = when {
							isRecording && capture.index == index -> colors.accent
							clashes(binding) -> colors.error
							else -> colors.selectionText
						},
						highlighted = isRecording && capture.index == index,
						onClick = { onBeginCapture(action, index) },
						onMouseBinding = mouseCapture?.takeIf { capture?.index == index },
					)
					if (bindings.size > 1) {
						Text(
							text = "×",
							modifier = Modifier
								.handCursor()
								.clickable { onRemoveBinding(action, index) }
								.padding(horizontal = 1.dp),
							style = typography.monoSmall.copy(fontSize = 10.sp),
							color = colors.textMuted,
						)
					}
				}
			}

			// An alternate being added has no cell of its own yet; this one stands in for it, so the
			// mouse has somewhere to be recorded.
			if (isRecording && bindings.isNotEmpty() && capture.index >= bindings.size) {
				KeyChip(
					text = tr("dialog.settings.shortcuts.captureShort"),
					tint = colors.accent,
					highlighted = true,
					onClick = {},
					onMouseBinding = mouseCapture,
				)
			}
			CompactIconButton(
				onClick = { onBeginCapture(action, bindings.size) },
				size = 18.dp,
				tooltip = tr("dialog.settings.shortcuts.addAlt"),
			) {
				Text(
					text = "+",
					style = typography.monoSmall.copy(fontSize = 12.sp),
					color = colors.textMuted,
				)
			}
			CompactIconButton(
				onClick = { onResetBinding(action) },
				size = 18.dp,
				enabled = isCustomised,
				tooltip = tr("dialog.settings.shortcuts.resetOne"),
			) {
				IconReset(
					modifier = Modifier.size(11.dp),
					tint = if (isCustomised) colors.textMuted else colors.textDisabled,
				)
			}
		}

		if (clash) {
			Text(
				text = tr("dialog.settings.shortcuts.clash"),
				modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 5.dp),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.error,
			)
		}
	}
}

/**
 * A single key combination. Shares the canvas toolbar's chip styling so "a key" reads the same
 * everywhere in the application.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyChip(
	text: String,
	tint: Color,
	highlighted: Boolean,
	onClick: () -> Unit,
	onMouseBinding: ((KeyBinding) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val latestMouseBinding by rememberUpdatedState(onMouseBinding)

	Box(
		modifier = Modifier
			.handCursor()
			// Recording: a wheel notch, or a press of any button but a bare left one (which is still a
			// click on the cell), becomes the binding. Read in the initial pass so the panel's scroll
			// and the cell's click never see it.
			.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
				val record = latestMouseBinding ?: return@onPointerEvent
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				wheelBindingOf(change.scrollDelta, event.keyboardModifiers)?.let(record)
				change.consume()
			}
			.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
				val record = latestMouseBinding ?: return@onPointerEvent
				val binding = buttonBindingOf(event.button, event.keyboardModifiers) ?: return@onPointerEvent
				if (binding.mouse == MouseInput.LEFT && !binding.hasModifier) return@onPointerEvent
				record(binding)
				event.changes.forEach { it.consume() }
			}
			.clickable(onClick = onClick)
			.background(
				if (highlighted) colors.accent.copy(alpha = 0.18f) else colors.inputBackground,
				RoundedCornerShape(2.dp),
			)
			.border(
				1.dp,
				if (highlighted) colors.accent else colors.border,
				RoundedCornerShape(2.dp),
			)
			.padding(horizontal = 5.dp, vertical = 1.dp),
	) {
		Text(
			text = text,
			style = typography.monoSmall.copy(fontSize = 10.sp),
			color = tint,
			maxLines = 1,
		)
	}
}

