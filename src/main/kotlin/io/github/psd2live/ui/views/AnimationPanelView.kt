package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.MotionEditorState
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewControlCanvas
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import io.github.psd2live.ui.theme.Motion

internal const val MOTION_SETTINGS_FIELD = "motion-settings"
private const val BUILTIN_SECTION = "builtin"
private const val CUSTOM_SECTION = "custom"
internal val MotionRowHeight = 24.dp
internal val MotionRowIndent = 12.dp

/** One motion as the panel lists it, generated or the user's own. */
private data class MotionEntry(
	val key: String,
	/** The id the animation editor opens it under; the panel and the editor play it by this id. */
	val editorId: String,
	val title: String,
	val loop: Boolean,
	val duration: Float,
	val enabled: Boolean,
	val onEnabledChange: (Boolean) -> Unit,
	val modified: Boolean,
	val onRename: ((String) -> Unit)?,
	val settings: @Composable () -> Unit,
	val menu: @Composable ((() -> Unit)) -> Unit,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AnimationPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val previewState = state.previewPanelState()
	val skeleton = state.rigEdits.skeleton
	var query by remember { mutableStateOf("") }
	val openSections = remember { mutableStateMapOf(BUILTIN_SECTION to true, CUSTOM_SECTION to true, *SIDECAR_SECTIONS.map { it to true }.toTypedArray()) }
	val openSettings = remember { mutableStateMapOf<String, Boolean>() }
	var newMenuOpen by remember { mutableStateOf(false) }

	val presets = state.rigEdits.motionPresets
	val clips = state.rigEdits.motionClips
	val basic = state.motionBasic
	val skeletonPresets = state.motionSkeleton
	// Generating the idle expands its poses onto the bones: only when what it is made from changes. A group
	// the model presets switch off is not in the model, so it is not listed.
	val renames = state.rigEdits.idRenames.parameters
	val generated = remember(skeleton, presets, clips, basic, skeletonPresets, renames) {
		MotionClips.BUILTIN_NAMES.associateWith { name ->
			val settings = presets[name] ?: MotionPresetSettings()
			if (settings.deleted || !(if (MotionClips.isSkeletonPreset(name)) skeletonPresets else basic)) null
			else MotionClips.overrideOf(clips, name) ?: MotionPresets.clip(name, name, skeleton, settings, renames).takeIf { it.curves.isNotEmpty() }
		}
	}
	val builtins = builtinEntries(viewModel, previewState, generated)
	val customs = customEntries(viewModel, previewState)
	val needle = query.trim()
	fun List<MotionEntry>.matching() = if (needle.isEmpty()) this else filter { it.title.contains(needle, ignoreCase = true) }
	val shownBuiltins = builtins.matching()
	val shownCustoms = customs.matching()

	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		val labels = listOf(tr("animation.new"))
		PanelToolbar(
			labels = labels,
			iconCount = if (state.previewLive) 4 else 5,
			search = PanelSearch(query, { query = it }, tr("animation.search")),
		) { labelsShown ->
			Box {
				PanelToolButton(
					label = labels[0],
					showLabel = labelsShown > 0,
					onClick = { newMenuOpen = true },
					enabled = state.previewModel != null,
					tooltip = tr("animation.new"),
				) {
					IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
				}
				NewMotionMenu(viewModel, state, newMenuOpen) { newMenuOpen = false }
			}
			Spacer(Modifier.weight(1f))
			PanelShowPreviewButton(state, viewModel)
			PanelExpandCollapseButtons(
				onExpandAll = { openSections.keys.toList().forEach { openSections[it] = true } },
				onCollapseAll = {
					openSections.keys.toList().forEach { openSections[it] = false }
					openSettings.clear()
				},
			)
			PanelResetButton(onClick = { viewModel.resetPreviewParameters() }, enabled = state.previewModel != null, tooltip = tr("animation.resetPose"))
		}

		if (state.previewModel == null || (needle.isNotEmpty() && shownBuiltins.isEmpty() && shownCustoms.isEmpty() && !sidecarsMatch(state, needle))) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = tr(if (state.previewModel == null) "animation.editor.noModel" else "animation.noResults"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(12.dp),
				)
			}
			return@Column
		}

		val builtinTitle = if (state.activeWorkspace.canvases.size > 1)
			"${viewModel.canvasTitle(state.previewControlCanvas())} · ${tr("animation.builtinSection")}"
		else tr("animation.builtinSection")
		val listState = rememberLazyListState()
		Box(Modifier.fillMaxSize()) {
			LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 6.dp)) {
				fun LazyListScope.section(id: String, title: String, entries: List<MotionEntry>, empty: String?) {
					val open = needle.isNotEmpty() || openSections[id] != false
					item(key = "s:$id") {
						PanelSectionRow(title, open, { openSections[id] = !open }, count = entries.size)
						Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
					}
					if (!open) return
					items(entries, key = { "m:${it.key}" }) { entry ->
						MotionRow(
							viewModel = viewModel,
							entry = entry,
							settingsOpen = openSettings[entry.key] == true,
							onToggleSettings = { openSettings[entry.key] = openSettings[entry.key] != true },
						)
						Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
					}
					if (entries.isEmpty() && empty != null) {
						item(key = "e:$id") {
							Text(
								text = empty,
								style = typography.caption.copy(fontSize = 10.5.sp),
								color = colors.textMuted,
								modifier = Modifier.fillMaxWidth().padding(start = MotionRowIndent + 4.dp, top = 6.dp, bottom = 6.dp),
							)
						}
					}
				}
				section(BUILTIN_SECTION, builtinTitle, shownBuiltins, null)
				section(CUSTOM_SECTION, tr("animation.customSection"), shownCustoms, tr("animation.customEmpty").takeIf { needle.isEmpty() })
				sidecarSections(viewModel, state, needle, openSections, openSettings)
			}
			VerticalScrollbar(
				adapter = rememberScrollbarAdapter(listState),
				modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
			)
		}
	}
}

/** Blank clip, a copy of a generated motion, or a deleted generated motion put back; opened from the toolbar. */
@Composable
private fun NewMotionMenu(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	open: Boolean,
	onDismiss: () -> Unit,
) {
	val presets = state.rigEdits.motionPresets
	TreeContextMenu(expanded = open, onDismissRequest = onDismiss) {
		CompactMenuItem(text = tr("animation.newBlank"), onClick = { onDismiss(); viewModel.createMotionClip() })
		val playable = MotionClips.BUILTIN_NAMES.filter {
			state.motionPresetGroupOn(it) && MotionClips.builtinTracks(it, state.rigEdits.skeleton).isNotEmpty()
		}
		CompactMenuDivider()
		CompactMenuSection(tr("animation.newFromPreset"))
		for (name in playable) {
			CompactMenuItem(text = builtinMotionTitle(name), onClick = { onDismiss(); viewModel.createMotionClip(fromBuiltin = name) })
		}
		val deleted = playable.filter { presets[it]?.deleted == true }
		if (deleted.isNotEmpty()) {
			CompactMenuDivider()
			CompactMenuSection(tr("animation.restorePreset"))
			for (name in deleted) {
				CompactMenuItem(text = builtinMotionTitle(name), onClick = { onDismiss(); viewModel.restoreMotionPreset(name) })
			}
		}
		NewExpressionMenuItems(viewModel, state, onDismiss)
	}
}

private fun builtinEntries(viewModel: PSD2LiveViewModel, state: PSD2LiveState, generated: Map<String, MotionClip?>): List<MotionEntry> =
	MotionClips.BUILTIN_NAMES.mapNotNull { name ->
		// Deleted presets, switched-off groups and skeleton presets the current skeleton cannot play are not listed.
		val clip = generated[name] ?: return@mapNotNull null
		val (enabled, setEnabled) = when (name) {
			"Idle" -> state.motionIdle to viewModel::setMotionIdle
			"Blink" -> state.motionBlink to viewModel::setMotionBlink
			"Nod" -> state.motionNod to viewModel::setMotionNod
			"Shake" -> state.motionShake to viewModel::setMotionShake
			else -> (state.rigEdits.motionPresets[name]?.disabled != true) to { on: Boolean -> viewModel.setMotionPresetEnabled(name, on) }
		}
		val edited = clip.id != name
		MotionEntry(
			key = "builtin:$name",
			editorId = MotionEditorState.presetClipId(name),
			title = builtinMotionTitle(name),
			loop = clip.loop,
			duration = clip.duration,
			enabled = enabled,
			onEnabledChange = setEnabled,
			modified = edited,
			onRename = null,
			settings = { PresetSettings(viewModel, name, state.rigEdits.motionPresets[name] ?: MotionPresetSettings(), edited) },
			menu = { dismiss ->
				CompactMenuItem(text = tr("animation.duplicateAsCustom"), onClick = { dismiss(); viewModel.createMotionClip(fromBuiltin = name) })
				CompactMenuItem(
					text = tr("animation.resetDefault"),
					enabled = edited || state.rigEdits.motionPresets[name]?.values?.isNotEmpty() == true,
					onClick = { dismiss(); viewModel.resetMotionPreset(name) },
				)
				CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deleteMotionPreset(name) })
			},
		)
	}

private fun customEntries(viewModel: PSD2LiveViewModel, state: PSD2LiveState): List<MotionEntry> =
	state.rigEdits.motionClips.filter { it.builtin == null }.map { clip ->
		MotionEntry(
			key = "clip:${clip.id}",
			editorId = clip.id,
			title = clip.name,
			loop = clip.loop,
			duration = clip.duration,
			enabled = clip.enabled,
			onEnabledChange = { value -> viewModel.updateMotionClipProperties(clip.id) { it.copy(enabled = value) } },
			modified = false,
			onRename = { viewModel.renameMotionClip(clip.id, it) },
			settings = { ClipSettings(viewModel, clip) },
			menu = { dismiss ->
				CompactMenuItem(text = tr("animation.duplicate"), onClick = { dismiss(); viewModel.duplicateMotionClip(clip.id) })
				CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deleteMotionClip(clip.id) })
			},
		)
	}

/**
 * One motion: enable, edit and play on the row, its advanced settings folded underneath and the rest in the
 * right-click menu. Play and pause are the animation editor's: the row plays the motion there, so both show
 * the same state. The motion open in the editor is marked like a related parameter.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MotionRow(
	viewModel: PSD2LiveViewModel,
	entry: MotionEntry,
	settingsOpen: Boolean,
	onToggleSettings: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	var renaming by remember { mutableStateOf(false) }
	var draftName by remember(entry.title) { mutableStateOf(entry.title) }
	val renameFocus = remember { FocusRequester() }
	val editor = viewModel.motionEditor
	val editing = editor.clipId == entry.editorId
	val playing = editing && editor.playing
	fun commitRename() {
		if (!renaming) return
		renaming = false
		val trimmed = draftName.trim()
		if (trimmed.isNotEmpty() && trimmed != entry.title) entry.onRename?.invoke(trimmed)
	}
	LaunchedEffect(renaming) {
		if (renaming) runCatching { renameFocus.requestFocus() }
	}

	Column(Modifier.fillMaxWidth()) {
		Box {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(MotionRowHeight)
					.background(
						when {
							editing -> colors.selection.copy(alpha = 0.35f)
							hovered -> colors.controlHover.copy(alpha = 0.35f)
							else -> Color.Transparent
						},
					)
					.drawWithContent {
						drawContent()
						if (editing) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
					}
					.hoverable(interaction)
					.onPointerEvent(PointerEventType.Press) { event ->
						if (event.button == PointerButton.Secondary) {
							menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
							menuOpen = true
							event.changes.firstOrNull()?.consume()
						}
					}
					.clickable(interactionSource = interaction, indication = null, enabled = !renaming, onClick = onToggleSettings)
					.padding(start = 4.dp, end = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(expanded = settingsOpen, tint = colors.textMuted, modifier = Modifier.size(10.dp))
				Spacer(Modifier.width(2.dp))
				CompactCheckbox(checked = entry.enabled, onCheckedChange = entry.onEnabledChange)
				Spacer(Modifier.width(4.dp))
				if (renaming) {
					CompactTextField(
						value = draftName,
						onValueChange = { draftName = it },
						modifier = Modifier
							.weight(1f)
							.focusRequester(renameFocus)
							.onPreviewKeyEvent { event ->
								if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
									renaming = false
									true
								} else false
							},
						height = 20.dp,
						selectAllOnFocus = true,
						onCommit = { commitRename() },
						onFocusLost = { commitRename() },
					)
				} else {
					// Name and badges take the free width, so the timing and the buttons sit flush right.
					Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = entry.title,
							style = typography.body.copy(fontSize = 11.sp, fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal),
							color = when {
								!entry.enabled -> colors.textMuted
								editing -> colors.accent
								else -> colors.textPrimary
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f, fill = false),
						)
						if (entry.modified) {
							Spacer(Modifier.width(4.dp))
							MotionBadge(tr("animation.modified"), colors.warning)
						}
						if (playing) {
							Spacer(Modifier.width(4.dp))
							MotionBadge(tr(if (entry.loop) "animation.looping" else "animation.playing"), colors.accent)
						}
					}
				}
				Spacer(Modifier.width(6.dp))
				Text(
					text = "${tr(if (entry.loop) "animation.loop" else "animation.once")} · %.1fs".format(entry.duration),
					style = typography.monoSmall.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
					maxLines = 1,
				)
				Spacer(Modifier.width(6.dp))
				CompactIconButton(onClick = { viewModel.openMotionInEditor(entry.editorId) }, size = 18.dp, tooltip = tr("animation.edit")) {
					IconMotionCurve(tint = if (editing) colors.accent else colors.textMuted)
				}
				Spacer(Modifier.width(4.dp))
				CompactIconButton(
					onClick = { viewModel.toggleMotionPlayback(entry.editorId) },
					size = 18.dp,
					tooltip = tr(if (playing) "animation.pause" else "animation.play"),
				) {
					if (playing) IconPause(modifier = Modifier.size(9.dp), tint = colors.textPrimary)
					else IconPlay(modifier = Modifier.size(9.dp), tint = colors.accent)
				}
			}
			TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
				CompactMenuItem(text = tr("animation.edit"), onClick = { menuOpen = false; viewModel.openMotionInEditor(entry.editorId) })
				CompactMenuItem(
					text = tr(if (playing) "animation.pause" else "animation.play"),
					onClick = { menuOpen = false; viewModel.toggleMotionPlayback(entry.editorId) },
				)
				if (entry.onRename != null) {
					CompactMenuItem(text = tr("animation.rename"), onClick = { menuOpen = false; draftName = entry.title; renaming = true })
				}
				CompactMenuDivider()
				entry.menu { menuOpen = false }
			}
		}

		AnimatedVisibility(
			visible = settingsOpen,
			enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) + fadeIn(tween(Motion.Fade)),
			exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) + fadeOut(tween(Motion.Instant)),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(colors.panelElevated.copy(alpha = 0.35f))
					.padding(start = MotionRowIndent + 4.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				entry.settings()
			}
		}
	}
}

/**
 * The advanced settings of a generated motion, its own knobs (see [MotionPresets.knobs]) two to a row and its
 * switches below. Once its keys are edited by hand the knobs no longer apply until it is reset.
 */
@Composable
private fun PresetSettings(viewModel: PSD2LiveViewModel, name: String, settings: MotionPresetSettings, edited: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val knobs = MotionPresets.knobs(name)
	if (edited) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(
				text = tr("animation.presetEdited"),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.warning,
				modifier = Modifier.weight(1f),
			)
			CompactButton(text = tr("animation.resetDefault"), onClick = { viewModel.resetMotionPreset(name) }, height = 20.dp)
		}
	}
	for (pair in knobs.filterNot { it.toggle }.chunked(2)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			for (knob in pair) {
				SettingField(
					label = tr("animation.knob.${knob.label}"),
					value = settings.value(knob),
					min = knob.min,
					max = knob.max,
					step = knob.step.toDouble(),
					decimals = if (knob.integer) 0 else 2,
					unit = if (knob.integer) "" else "×",
					enabled = !edited,
					modifier = Modifier.weight(1f),
					viewModel = viewModel,
				) { viewModel.setMotionPresetValue(name, knob.id, it) }
			}
			if (pair.size == 1) Spacer(Modifier.weight(1f))
		}
	}
	val toggles = knobs.filter { it.toggle }
	if (toggles.isNotEmpty()) {
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
			for (knob in toggles) {
				CompactCheckbox(
					checked = settings.value(knob) > 0.5f,
					onCheckedChange = { viewModel.setMotionPresetValue(name, knob.id, if (it) 1f else 0f) },
					enabled = !edited,
					label = tr("animation.knob.${knob.label}"),
				)
			}
		}
	}
}

/** The advanced settings of a user clip: its fades, which the editor's toolbar leaves out, and its events. */
@Composable
private fun ClipSettings(viewModel: PSD2LiveViewModel, clip: MotionClip) {
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
		SettingField(tr("animation.fadeInTime"), clip.fadeIn, 0f, 5f, 0.1, 1, "", true, Modifier.weight(1f), viewModel) { value ->
			viewModel.updateMotionClipProperties(clip.id) { it.copy(fadeIn = value, runtimeDefaultFades = false) }
		}
		SettingField(tr("animation.fadeOutTime"), clip.fadeOut, 0f, 5f, 0.1, 1, "", true, Modifier.weight(1f), viewModel) { value ->
			viewModel.updateMotionClipProperties(clip.id) { it.copy(fadeOut = value, runtimeDefaultFades = false) }
		}
	}
	MotionEventSettings(viewModel, clip)
}

@Composable
internal fun SettingField(
	label: String,
	value: Float,
	min: Float,
	max: Float,
	step: Double,
	decimals: Int,
	unit: String,
	enabled: Boolean,
	modifier: Modifier,
	viewModel: PSD2LiveViewModel,
	onChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
		modifier = modifier,
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 10.sp),
			color = if (enabled) colors.textMuted else colors.textMuted.copy(alpha = 0.5f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		CompactNumberSpinner(
			value = value.toDouble(),
			onValueChange = { onChange(it.toFloat().coerceIn(min, max)) },
			min = min.toDouble(),
			max = max.toDouble(),
			step = step,
			decimals = decimals,
			unit = unit,
			enabled = enabled,
			height = 20.dp,
			modifier = Modifier.width(64.dp),
			onEditStart = { viewModel.beginEditorField(MOTION_SETTINGS_FIELD) },
			onEditEnd = { viewModel.endEditorField(MOTION_SETTINGS_FIELD) },
		)
	}
}

@Composable
internal fun MotionBadge(label: String, tint: Color) {
	val typography = LocalToolTypography.current
	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(2.dp))
			.background(tint.copy(alpha = 0.16f))
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
			color = tint,
			maxLines = 1,
		)
	}
}

/** An eased curve between two keys: opens the motion in the animation editor. */
@Composable
private fun IconMotionCurve(tint: Color) = GridIcon(Modifier.size(11.dp), tint) {
	outline(path { m(2.6f, 14.6f); c(9.6f, 14.6f, 8.4f, 3.4f, 15.4f, 3.4f) })
	dot(2.6f, 14.6f, 1.9f)
	dot(15.4f, 3.4f, 1.9f)
}
