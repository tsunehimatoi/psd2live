package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewControlCanvas
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

private const val MOTION_SETTINGS_FIELD = "motion-settings"

@Composable
internal fun AnimationPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val scrollState = rememberScrollState()
	val previewState = state.previewPanelState()

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.panelBackground)
			.verticalScroll(scrollState)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// 1. Master Playback & Quick Controls
		MasterPlaybackCard(viewModel, previewState, state.previewLive)

		// 2. Generated motions, each editable in place.
		val previewCanvas = state.previewControlCanvas()
		val motionsTitle = if (state.activeWorkspace.canvases.size > 1)
			"${viewModel.canvasTitle(previewCanvas)} · ${tr("animation.builtinSection")}"
		else tr("animation.builtinSection")
		CompactSectionHeader(title = motionsTitle)
		BuiltinMotionsSection(viewModel, previewState)

		// 3. The user's own motions.
		CompactSectionHeader(
			title = tr("animation.customSection"),
			trailing = { NewMotionButton(viewModel, state.rigEdits.skeleton) },
		)
		CustomMotionsSection(viewModel, previewState)

		Spacer(Modifier.height(8.dp))
	}
}

/** Master Playback & Controls Header Card */
@Composable
private fun MasterPlaybackCard(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	previewVisible: Boolean,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isPlaying = state.animationEnabled && !state.meshOnly

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(colors.windowBackground)
			.border(BorderStroke(1.dp, if (isPlaying) colors.accent.copy(alpha = 0.5f) else colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			// Row 1: Play/Pause, Reset, Status
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactButton(
						text = if (isPlaying) tr("animation.pause") else tr("animation.play"),
						onClick = { viewModel.setAnimationEnabled(!state.animationEnabled) },
						leadingIcon = {
							if (isPlaying) IconPause(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
							else IconPlay(modifier = Modifier.size(11.dp), tint = colors.accent)
						},
						height = 24.dp,
					)

					CompactButton(
						text = tr("animation.resetPose"),
						onClick = { viewModel.resetPreviewParameters() },
						leadingIcon = { IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary) },
						height = 24.dp,
					)
				}

				// Status Badge
				val badgeBg = if (isPlaying) colors.accent.copy(alpha = 0.16f) else colors.controlHover
				val badgeTextColor = if (isPlaying) colors.accent else colors.textMuted
				Box(
					modifier = Modifier
						.clip(RoundedCornerShape(3.dp))
						.background(badgeBg)
						.border(BorderStroke(0.5.dp, if (isPlaying) colors.accent.copy(alpha = 0.35f) else Color.Transparent), RoundedCornerShape(3.dp))
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(
						text = if (isPlaying) tr("animation.status.running") else tr("animation.status.paused"),
						style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
						color = badgeTextColor,
					)
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)

			// Row 2: Mouse Eye Tracking Checkbox
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactCheckbox(
					checked = state.mouseTrackingEnabled,
					onCheckedChange = { viewModel.setMouseTrackingEnabled(it) },
					label = tr("animation.mouseTracking"),
				)

				if (!previewVisible) {
					CompactButton(
						text = tr("window.showPreview"),
						onClick = { viewModel.ensurePreviewCanvas(focus = true) },
						height = 20.dp,
					)
				}
			}
		}
	}
}

/** True when [override] still matches what [name] generates, so it reads as unmodified. */
private fun isPristine(override: MotionClip, name: String, skeleton: SkeletonSpec?): Boolean {
	val tracks = MotionClips.builtinTracks(name, skeleton)
	val fresh = MotionClips.fromTracks(override.id, override.name, override.builtin, MotionClips.isLoopBuiltin(name), tracks,
		MotionClips.builtinDuration(name, tracks))
	return fresh.copy(enabled = override.enabled) == override
}

@Composable
private fun BuiltinMotionsSection(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val clips = state.rigEdits.motionClips
	val skeleton = state.rigEdits.skeleton
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		for (name in MotionClips.BUILTIN_NAMES) {
			val override = MotionClips.overrideOf(clips, name)
			val tracks = MotionClips.builtinTracks(name, skeleton)
			// Skeleton presets only list when the current skeleton can play them.
			if (override == null && tracks.isEmpty()) continue
			val (enabled, setEnabled) = when (name) {
				"Idle" -> state.motionIdle to viewModel::setMotionIdle
				"Blink" -> state.motionBlink to viewModel::setMotionBlink
				"Nod" -> state.motionNod to viewModel::setMotionNod
				"Shake" -> state.motionShake to viewModel::setMotionShake
				else -> state.motionSkeleton to viewModel::setMotionSkeleton
			}
			val summary = override?.let(::summaryOf) ?: MotionSummary(
				loop = MotionClips.isLoopBuiltin(name),
				duration = MotionClips.builtinDuration(name, tracks),
				fps = 30f,
				fadeIn = 1f,
				fadeOut = 1f,
				curves = tracks.map { (id, points) -> id to (points.minOf { it.second } to points.maxOf { it.second }) },
			)
			MotionRow(
				viewModel = viewModel,
				state = state,
				title = builtinMotionTitle(name),
				playName = name,
				clipId = override?.id,
				summary = summary,
				enabled = enabled,
				onEnabledChange = setEnabled,
				modified = override != null && !isPristine(override, name, skeleton),
				onEdit = { viewModel.editBuiltinMotion(name) },
				onEditProperties = { transform -> viewModel.updateMotionClipProperties(viewModel.ensureBuiltinOverride(name), transform) },
				onFocusParameter = { id ->
					viewModel.editBuiltinMotion(name)
					viewModel.focusMotionCurve(id)
				},
				menu = { dismiss ->
					CompactMenuItem(text = tr("animation.duplicateAsCustom"), onClick = { dismiss(); viewModel.createMotionClip(fromBuiltin = name) })
					CompactMenuItem(
						text = tr("animation.resetDefault"),
						enabled = override != null,
						onClick = { dismiss(); viewModel.resetBuiltinMotion(name) },
					)
				},
			)
		}
	}
}

@Composable
private fun CustomMotionsSection(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val clips = state.rigEdits.motionClips.filter { it.builtin == null }
	val stems = MotionClips.exportStems(state.rigEdits.motionClips)
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		if (clips.isEmpty()) {
			Box(
				Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
					.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
					.padding(10.dp),
				contentAlignment = Alignment.Center,
			) {
				Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(tr("animation.customEmpty"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp))
					CompactButton(text = tr("animation.new"), onClick = { viewModel.createMotionClip() }, isPrimary = true, height = 22.dp)
				}
			}
		}
		for (clip in clips) {
			MotionRow(
				viewModel = viewModel,
				state = state,
				title = clip.name,
				playName = stems.getValue(clip.id),
				clipId = clip.id,
				summary = summaryOf(clip),
				enabled = clip.enabled,
				onEnabledChange = { value -> viewModel.updateMotionClipProperties(clip.id) { it.copy(enabled = value) } },
				modified = false,
				onEdit = { viewModel.openMotionInEditor(clip.id) },
				onEditProperties = { transform -> viewModel.updateMotionClipProperties(clip.id, transform) },
				onFocusParameter = { id ->
					viewModel.openMotionInEditor(clip.id)
					viewModel.focusMotionCurve(id)
				},
				onRename = { viewModel.renameMotionClip(clip.id, it) },
				menu = { dismiss ->
					CompactMenuItem(text = tr("animation.duplicate"), onClick = { dismiss(); viewModel.duplicateMotionClip(clip.id) })
					CompactMenuItem(text = tr("animation.delete"), danger = true, onClick = { dismiss(); viewModel.deleteMotionClip(clip.id) })
				},
			)
		}
	}
}

/** "+ New" with a blank clip or a copy of a generated motion. */
@Composable
private fun NewMotionButton(viewModel: PSD2LiveViewModel, skeleton: SkeletonSpec?) {
	var open by remember { mutableStateOf(false) }
	Box {
		CompactButton(text = "+ ${tr("animation.new")}", onClick = { open = true }, height = 18.dp)
		TreeContextMenu(expanded = open, onDismissRequest = { open = false }) {
			CompactMenuItem(text = tr("animation.newBlank"), onClick = { open = false; viewModel.createMotionClip() })
			CompactMenuDivider()
			CompactMenuSection(tr("animation.newFromPreset"))
			for (name in MotionClips.BUILTIN_NAMES) {
				if (MotionClips.builtinTracks(name, skeleton).isEmpty()) continue
				CompactMenuItem(text = builtinMotionTitle(name), onClick = { open = false; viewModel.createMotionClip(fromBuiltin = name) })
			}
		}
	}
}

/** What a motion row shows: its timing and each curve's range. */
private data class MotionSummary(
	val loop: Boolean,
	val duration: Float,
	val fps: Float,
	val fadeIn: Float,
	val fadeOut: Float,
	val curves: List<Pair<String, Pair<Float, Float>>>,
)

private fun summaryOf(clip: MotionClip) = MotionSummary(
	loop = clip.loop,
	duration = clip.duration,
	fps = clip.fps,
	fadeIn = clip.fadeIn,
	fadeOut = clip.fadeOut,
	curves = clip.curves.map { curve -> curve.parameterId to (curve.keys.minOf { it.value } to curve.keys.maxOf { it.value }) },
)

/** One motion: enable, play, open in the editor, and its settings folded underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MotionRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	title: String,
	playName: String,
	clipId: String?,
	summary: MotionSummary,
	enabled: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	modified: Boolean,
	onEdit: () -> Unit,
	onEditProperties: ((MotionClip) -> MotionClip) -> Unit,
	onFocusParameter: (String) -> Unit,
	menu: @Composable ((() -> Unit)) -> Unit,
	onRename: ((String) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var showSettings by remember { mutableStateOf(false) }
	var menuOpen by remember { mutableStateOf(false) }
	var renaming by remember { mutableStateOf(false) }
	var draftName by remember(title) { mutableStateOf(title) }
	val activeMotionName = viewModel.activeMotionName
	val editor = viewModel.motionEditor

	val isCurrentlyPlaying = playName.equals(activeMotionName, ignoreCase = true) ||
		(playName.equals("Idle", ignoreCase = true) && state.animationEnabled && enabled && activeMotionName == null)
	val isEditing = clipId != null && editor.clipId == clipId

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(if (isEditing) colors.selection.copy(alpha = 0.35f) else colors.windowBackground)
			.border(
				BorderStroke(1.dp, when {
					isEditing -> colors.accent
					isCurrentlyPlaying -> colors.accent.copy(alpha = 0.5f)
					else -> colors.divider
				}),
				RoundedCornerShape(4.dp),
			)
			.padding(6.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactCheckbox(checked = enabled, onCheckedChange = onEnabledChange)

				if (renaming && onRename != null) {
					CompactTextField(
						value = draftName,
						onValueChange = { draftName = it },
						onCommit = { onRename(draftName); renaming = false },
						onFocusLost = { if (renaming) { onRename(draftName); renaming = false } },
						selectAllOnFocus = true,
						height = 20.dp,
						modifier = Modifier.weight(1f),
					)
				} else {
					Text(
						text = title,
						style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
						color = if (enabled) colors.textPrimary else colors.textMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false).combinedClickable(
							interactionSource = remember { MutableInteractionSource() },
							indication = null,
							onClick = {},
							onDoubleClick = if (onRename != null) ({ draftName = title; renaming = true }) else null,
						),
					)
					if (modified) MotionBadge(tr("animation.modified"), colors.warning)
					if (isCurrentlyPlaying) MotionBadge(if (summary.loop) tr("animation.looping") else tr("animation.playing"), colors.accent)
					if (isEditing) MotionBadge(tr("animation.editing"), colors.accent)
					Spacer(Modifier.weight(1f))
				}

				DccTag(label = if (summary.loop) tr("animation.loop") else tr("animation.once"))
				DccTag(label = "%.1fs".format(summary.duration))
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				DccTag(label = "${summary.curves.size} ${tr("animation.curves")}")
				Spacer(Modifier.weight(1f))
				CompactButton(
					text = tr("animation.trigger"),
					onClick = { viewModel.triggerMotion(playName) },
					leadingIcon = { IconPlay(modifier = Modifier.size(9.dp), tint = colors.accent) },
					height = 20.dp,
				)
				CompactButton(text = tr("animation.edit"), onClick = onEdit, height = 20.dp, isPrimary = isEditing)
				CompactButton(
					text = if (showSettings) tr("animation.hideSettings") else tr("animation.settingsPanel"),
					onClick = { showSettings = !showSettings },
					height = 20.dp,
				)
				Box {
					CompactButton(text = "⋯", onClick = { menuOpen = true }, height = 20.dp)
					TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 160.dp) {
						if (onRename != null) {
							CompactMenuItem(text = tr("animation.rename"), onClick = { menuOpen = false; draftName = title; renaming = true })
						}
						menu { menuOpen = false }
					}
				}
			}

			AnimatedVisibility(
				visible = showSettings,
				enter = expandVertically() + fadeIn(),
				exit = shrinkVertically() + fadeOut(),
			) {
				MotionSettings(viewModel, summary, onEditProperties, onFocusParameter)
			}
		}
	}
}

@Composable
private fun MotionSettings(
	viewModel: PSD2LiveViewModel,
	summary: MotionSummary,
	onEditProperties: ((MotionClip) -> MotionClip) -> Unit,
	onFocusParameter: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(3.dp))
			.background(colors.panelBackground)
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(3.dp))
			.padding(6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = tr("animation.motionSettings"),
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SettingField(tr("animation.duration"), summary.duration, 0.1f, 600f, 0.1, 2, Modifier.weight(1f), viewModel) { value ->
				onEditProperties { it.copy(duration = value) }
			}
			SettingField("FPS", summary.fps, 1f, 120f, 1.0, 0, Modifier.weight(1f), viewModel) { value ->
				onEditProperties { it.copy(fps = value) }
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SettingField(tr("animation.fadeInTime"), summary.fadeIn, 0f, 5f, 0.1, 1, Modifier.weight(1f), viewModel) { value ->
				onEditProperties { it.copy(fadeIn = value) }
			}
			SettingField(tr("animation.fadeOutTime"), summary.fadeOut, 0f, 5f, 0.1, 1, Modifier.weight(1f), viewModel) { value ->
				onEditProperties { it.copy(fadeOut = value) }
			}
		}
		CompactCheckbox(
			checked = summary.loop,
			onCheckedChange = { value -> onEditProperties { it.copy(loop = value) } },
			label = tr("animation.loopPlayback"),
		)

		Divider(color = colors.divider.copy(alpha = 0.3f), thickness = 0.5.dp)

		Text(
			text = tr("animation.affectedParams"),
			style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
			color = colors.textPrimary,
		)
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			summary.curves.forEach { (paramId, range) ->
				Row(
					modifier = Modifier.fillMaxWidth().clickable { onFocusParameter(paramId) },
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = paramId,
						style = typography.caption.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
						color = colors.accent,
					)
					Text(
						text = "%.1f ~ %.1f".format(range.first, range.second),
						style = typography.caption.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
						color = colors.textMuted,
					)
				}
			}
		}
	}
}

@Composable
private fun SettingField(
	label: String,
	value: Float,
	min: Float,
	max: Float,
	step: Double,
	decimals: Int,
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
		Text(text = label, style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
		CompactNumberSpinner(
			value = value.toDouble(),
			onValueChange = { onChange(it.toFloat().coerceIn(min, max)) },
			min = min.toDouble(),
			max = max.toDouble(),
			step = step,
			decimals = decimals,
			height = 20.dp,
			modifier = Modifier.width(60.dp),
			onEditStart = { viewModel.beginEditorField(MOTION_SETTINGS_FIELD) },
			onEditEnd = { viewModel.endEditorField(MOTION_SETTINGS_FIELD) },
		)
	}
}

@Composable
private fun MotionBadge(label: String, tint: Color) {
	val typography = LocalToolTypography.current
	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(3.dp))
			.background(tint.copy(alpha = 0.16f))
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
			color = tint,
			maxLines = 1,
		)
	}
}

/** Mini DCC Tag Chip */
@Composable
private fun DccTag(label: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(2.dp))
			.background(colors.controlHover)
			.padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 8.5.sp),
			color = colors.textMuted,
			maxLines = 1,
		)
	}
}
