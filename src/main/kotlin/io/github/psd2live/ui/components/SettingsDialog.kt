package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.Divider
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
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
import java.awt.Cursor
import kotlin.math.roundToInt

/**
 * The categories of the settings window, in sidebar order: the options the user came to change
 * first, and the read-only reference material last.
 *
 * [CANVAS] reuses the existing `settings.canvas.title` label because that string already names the
 * option in all three bundles.
 */
private enum class SettingsSection(val labelKey: String) {
	SCALE("dialog.settings.category.scale"),
	THEME("dialog.settings.category.theme"),
	LANGUAGE("dialog.settings.category.language"),
	CANVAS("settings.canvas.title"),
	PROMPTS("dialog.settings.category.prompts"),
	SHORTCUTS("dialog.settings.category.shortcuts"),
	ENVIRONMENT("dialog.settings.category.environment"),
}

/** The section shown when the preferences window is next composed. */
private var lastSettingsSection = SettingsSection.SCALE

/**
 * Preferences window: a category sidebar on the left, the selected category's content on the right.
 *
 * The panel takes its size from the main window rather than a constant, so it grows with the window
 * instead of looking cramped on a large display or overflowing a small one.
 *
 * Every option here applies immediately — there is no draft state and "OK" is just a dismiss. That
 * is deliberate: [AppSettings.uiScale] falls back to the detected display scale when the user has
 * never set one, so there is no previous value a Cancel could restore.
 *
 * [currentLanguage] must be passed from observable state rather than read from [I18n] directly:
 * `tr()` reads a plain `@Volatile` field, so without a changed parameter Compose would skip the
 * recomposition and the window would keep rendering the old language after a switch made *inside*
 * this very window.
 */
@Composable
fun SettingsDialog(
	uiScale: Float,
	fontScale: Float,
	themeId: String = ThemeCatalog.DARK_ID,
	customThemes: List<CustomTheme> = emptyList(),
	mutedPrompts: Set<AppPrompt> = AppSettings.mutedPrompts(),
	keymap: Keymap = Keymap.DEFAULT,
	keyPreset: KeymapPreset = KeymapPreset.PHOTOSHOP,
	keyCapture: KeyCapture? = null,
	currentLanguage: AppLanguage = I18n.currentLanguage,
	onUiScaleChange: (Float) -> Unit,
	onFontScaleChange: (Float) -> Unit,
	onThemeSelect: (String) -> Unit = {},
	onThemeDuplicate: (String) -> Unit = {},
	onCustomThemeChange: (CustomTheme) -> Unit = {},
	onCustomThemeDelete: (String) -> Unit = {},
	onThemeImport: (String) -> Boolean = { false },
	onPromptEnabledChange: (AppPrompt, Boolean) -> Unit = AppSettings::setPromptEnabled,
	onRestorePrompts: () -> Unit = {},
	onLanguageChange: (AppLanguage) -> Unit = {},
	onKeyCapture: (ShortcutAction, Int) -> Unit = { _, _ -> },
	onKeyCaptureBinding: (KeyBinding) -> Unit = {},
	onKeyRemoveBinding: (ShortcutAction, Int) -> Unit = { _, _ -> },
	onKeyResetBinding: (ShortcutAction) -> Unit = {},
	onKeyPresetChange: (KeymapPreset) -> Unit = {},
	onCaptureKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Unit = {},
	onResetDefaults: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	// Read once per open, and deliberately hoisted out of the sections: the device query touches
	// GraphicsEnvironment and is slow on multi-monitor Windows setups, so re-running it on every
	// section switch would be visible. Hoisting also guarantees the "Auto Recommend" button and the
	// environment readout describe the same snapshot.
	val displayMetrics = remember { AppSettings.detectDisplayMetrics() }

	// Kept past this composition: a language switch rebuilds the window and this dialog with it,
	// which should land back on the Language section rather than the first one.
	var selectedSection by remember { mutableStateOf(lastSettingsSection) }
	SideEffect { lastSettingsSection = selectedSection }
	val contentScroll = rememberScrollState()
	// The scroll state is shared by every section, so without this a switch made while scrolled down
	// would land the new section mid-scroll.
	LaunchedEffect(selectedSection) { contentScroll.scrollTo(0) }

	// Follow the main window rather than a fixed size. The bounds matter at the extremes: in a
	// maximised window an unbounded 78% would stretch every row until each label drifted away
	// from the control it belongs to, and in a small window 76% of the height is not enough to
	// show a section plus the action row.
	ModalDialogFrame(
		title = tr("dialog.settings.title"),
		onDismiss = onDismiss,
		fit = { maxWidth, maxHeight ->
			DpSize((maxWidth * 0.8f).coerceIn(560.dp, 1100.dp).coerceAtMost(maxWidth),
				(maxHeight * 0.8f).coerceIn(420.dp, 860.dp).coerceAtMost(maxHeight))
		},
		scrollable = false,
		headerDivider = true,
		// The dialog holds the keyboard, so a shortcut being recorded is handed over here rather than by the
		// root dispatcher; while one is, no key closes or confirms the dialog.
		onKeyDown = { event -> if (keyCapture != null) { onCaptureKeyEvent(event); true } else false },
		// No padding: the sidebar has to reach the panel edge.
		bodyPadding = PaddingValues(0.dp),
		bodySpacing = 0.dp,
		titleTrailing = {
			Text(text = "v3.3.1", style = typography.monoSmall.copy(fontSize = 10.sp), color = colors.textMuted)
		},
		footerStart = {
			CompactButton(text = tr("dialog.settings.resetDefaults"), onClick = onResetDefaults)
		},
		footer = {
			CompactButton(text = tr("dialog.ok"), onClick = onDismiss, isPrimary = true)
		},
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
		) {
			SettingsSidebar(
				selected = selectedSection,
				onSelect = { selectedSection = it },
			)

			Box(
				modifier = Modifier
					.width(1.dp)
					.fillMaxHeight()
					.background(colors.divider),
			)

			Column(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					.verticalScroll(contentScroll)
					.padding(horizontal = 16.dp, vertical = 14.dp),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				when (selectedSection) {
					SettingsSection.SCALE -> SettingsScaleSection(
						uiScale = uiScale,
						fontScale = fontScale,
						recommendedScale = displayMetrics.recommendedScale,
						onUiScaleChange = onUiScaleChange,
						onFontScaleChange = onFontScaleChange,
					)
					SettingsSection.THEME -> SettingsThemeSection(
						themeId = themeId,
						customThemes = customThemes,
						onSelect = onThemeSelect,
						onDuplicate = onThemeDuplicate,
						onChange = onCustomThemeChange,
						onDelete = onCustomThemeDelete,
						onImport = onThemeImport,
					)
					SettingsSection.LANGUAGE -> SettingsLanguageSection(
						currentLanguage = currentLanguage,
						onLanguageChange = onLanguageChange,
					)
					SettingsSection.CANVAS -> SettingsCanvasSection()
					SettingsSection.PROMPTS -> SettingsPromptsSection(
						mutedPrompts = mutedPrompts,
						onPromptEnabledChange = onPromptEnabledChange,
						onRestore = onRestorePrompts,
					)
					SettingsSection.SHORTCUTS -> SettingsShortcutsSection(
						keymap = keymap,
						preset = keyPreset,
						capture = keyCapture,
						onBeginCapture = onKeyCapture,
						onCaptureBinding = onKeyCaptureBinding,
						onRemoveBinding = onKeyRemoveBinding,
						onResetBinding = onKeyResetBinding,
						onPresetChange = onKeyPresetChange,
					)
					SettingsSection.ENVIRONMENT -> SettingsEnvironmentSection(displayMetrics)
				}
			}
		}
	}
}

/**
 * Vertical category list. Hand-rolled rather than reusing [CompactTabBar], which is horizontal-only:
 * it lays its tabs out in a `Row` that fills the width and draws the selection indicator along the
 * top edge. This mirrors that component's colour language rotated 90° — the indicator moves to the
 * left edge and the selected row uses the same `selection`/`selectionText` pairing as the dropdown
 * and menu bar, so "selected item" reads the same everywhere.
 *
 * The width is fixed on purpose: the whole window sits inside the scaled density, so dragging the UI
 * scale slider rescales this sidebar live, and an intrinsic or fractional width would jitter.
 */
@Composable
private fun SettingsSidebar(
	selected: SettingsSection,
	onSelect: (SettingsSection) -> Unit,
) {
	val colors = LocalToolColors.current

	Column(
		modifier = Modifier
			.width(160.dp)
			.fillMaxHeight()
			.background(colors.windowBackground)
			.padding(vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		for (section in SettingsSection.entries) {
			SettingsSidebarRow(
				label = tr(section.labelKey),
				isSelected = section == selected,
				onClick = { onSelect(section) },
			)
		}
	}
}

@Composable
private fun SettingsSidebarRow(
	label: String,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	val bg = when {
		isSelected -> colors.selection
		isHovered -> colors.controlHover
		else -> Color.Transparent
	}

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.background(bg)
			.drawBehind {
				if (isSelected) {
					drawRect(
						color = colors.accent,
						topLeft = Offset.Zero,
						size = Size(2.dp.toPx(), size.height),
					)
				}
			}
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.padding(start = 12.dp, end = 8.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		Text(
			text = label,
			style = typography.body.copy(
				fontSize = 11.5.sp,
				fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
			),
			color = when {
				isSelected -> colors.selectionText
				isHovered -> colors.textPrimary
				else -> colors.textMuted
			},
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
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

@Composable
private fun SettingsScaleSection(
	uiScale: Float,
	fontScale: Float,
	recommendedScale: Float,
	onUiScaleChange: (Float) -> Unit,
	onFontScaleChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val uiScalePresets = listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.50f, 1.75f, 2.00f, 2.50f)
	val fontScalePresets = listOf(
		0.90f to tr("settings.font.compact"),
		1.00f to tr("settings.font.standard"),
		1.15f to tr("settings.font.large"),
		1.30f to tr("settings.font.extraLarge"),
	)

	SettingsSectionDescription(tr("dialog.settings.scale.desc"))

	// Section 1: UI Scaling (界面缩放)
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = tr("dialog.settings.uiScale"),
				style = typography.header.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = "${(uiScale * 100).roundToInt()}%",
					style = typography.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
					color = colors.accent,
				)
				CompactButton(
					text = tr("dialog.settings.autoScale"),
					onClick = { onUiScaleChange(recommendedScale) },
					height = 20.dp,
				)
			}
		}

		// Slider
		CompactSlider(
			value = uiScale,
			onValueChange = { onUiScaleChange((it * 100).roundToInt() / 100f) },
			valueRange = 0.75f..2.50f,
			modifier = Modifier.fillMaxWidth().height(22.dp),
		)

		// Preset Buttons
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			for (preset in uiScalePresets) {
				val isSelected = kotlin.math.abs(uiScale - preset) < 0.03f
				CompactToggleChip(
					text = "${(preset * 100).toInt()}%",
					selected = isSelected,
					onToggle = { onUiScaleChange(preset) },
					modifier = Modifier.weight(1f),
				)
			}
		}
	}

	// Section 2: Font Scaling (字体缩放)
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = tr("dialog.settings.fontScale"),
				style = typography.header.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)
			Text(
				text = "${(fontScale * 100).roundToInt()}%",
				style = typography.mono.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
				color = colors.accent,
			)
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			for ((scale, label) in fontScalePresets) {
				val isSelected = kotlin.math.abs(fontScale - scale) < 0.04f
				CompactToggleChip(
					text = label,
					selected = isSelected,
					onToggle = { onFontScaleChange(scale) },
					modifier = Modifier.weight(1f),
				)
			}
		}
	}

	// Live Preview Sample Box
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(4.dp))
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = tr("dialog.settings.previewSample"),
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = "Head_FrontHair_01",
				style = typography.body.copy(fontWeight = FontWeight.Medium),
				color = colors.textPrimary,
			)
			CompactButton(
				text = tr("action.analyze"),
				onClick = {},
				isPrimary = true,
				height = 22.dp,
			)
			Text(
				text = "Z: 500  ·  4096×4096",
				style = typography.monoSmall,
				color = colors.textMuted,
			)
		}
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

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(4.dp))
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		val softwareCanvas by AppSettings.softwareCanvasFlow.collectAsState()
		val gpuStatus by io.github.psd2live.render.SkiaGpu.status.collectAsState()
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.clickable { AppSettings.softwareCanvas = !softwareCanvas }
				.padding(vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = if (softwareCanvas) "✓" else " ",
				style = typography.body.copy(fontWeight = FontWeight.Bold),
				color = if (softwareCanvas) colors.accent else Color.Transparent,
				modifier = Modifier.width(16.dp),
			)
			Text(
				text = tr("dialog.settings.canvas.softwareRendering"),
				style = typography.body.copy(fontSize = 11.5.sp),
				color = colors.textPrimary,
			)
		}
		Text(
			text = if (softwareCanvas) tr("dialog.settings.canvas.rendererSoftware") else when (val status = gpuStatus) {
				is io.github.psd2live.render.SkiaGpu.Status.Ready -> tr("dialog.settings.canvas.rendererGpu", status.description)
				is io.github.psd2live.render.SkiaGpu.Status.Unavailable -> tr("dialog.settings.canvas.rendererUnavailable", status.reason)
				io.github.psd2live.render.SkiaGpu.Status.Starting -> tr("dialog.settings.canvas.rendererStarting")
			},
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(start = 24.dp),
		)
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

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.panelElevated, RoundedCornerShape(4.dp))
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
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

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = tr("dialog.settings.shortcuts.preset"),
			style = typography.caption.copy(fontSize = 11.sp),
			color = colors.textPrimary,
		)
		CompactDropdown(
			items = KeymapPreset.entries,
			selectedItem = preset,
			onItemSelected = onPresetChange,
			itemLabel = { tr(it.labelKey) },
			modifier = Modifier.weight(1f),
		)
	}
	Text(
		text = tr("dialog.settings.shortcuts.presetHint"),
		style = typography.caption.copy(fontSize = 10.5.sp),
		color = colors.textMuted,
	)

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
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated, RoundedCornerShape(4.dp))
				.border(
					BorderStroke(1.dp, if (rejected) colors.error else colors.accent),
					RoundedCornerShape(4.dp),
				)
				.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = message,
				style = typography.caption.copy(fontSize = 11.sp),
				color = if (rejected) colors.error else colors.accent,
			)
		}
	}

	for (category in ShortcutCategory.entries) {
		Column(
			modifier = Modifier.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			CompactSectionHeader(tr(category.labelKey))
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(colors.panelElevated, RoundedCornerShape(4.dp))
					.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)),
			) {
				val actions = ShortcutAction.entries.filter { it.category == category }
				actions.forEachIndexed { index, action ->
					if (index > 0) Divider(color = colors.divider, thickness = 1.dp)
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
				.padding(horizontal = 10.dp, vertical = 5.dp),
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
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
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
				modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 5.dp),
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
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
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

@Composable
private fun SettingsEnvironmentSection(displayMetrics: AppSettings.DisplayMetrics) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	SettingsSectionDescription(tr("dialog.settings.environment.desc"))

	// Display & Environment Info
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.inputBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = tr("dialog.settings.highDpiInfo"),
			style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		Text(
			text = tr(
				"dialog.settings.displayMetrics",
				displayMetrics.physicalWidth,
				displayMetrics.physicalHeight,
				displayMetrics.systemScalePercent,
				"${(displayMetrics.recommendedScale * 100).toInt()}%",
			),
			style = typography.caption.copy(fontSize = 10.5.sp),
			color = colors.textMuted,
		)
	}
}
