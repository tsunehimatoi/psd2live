package io.github.psd2live.ui.views

import io.github.psd2live.ui.parameterKeyMarks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionHandle
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionLoops
import io.github.psd2live.project.MotionTimelineAids
import io.github.psd2live.project.TimelineMarker
import io.github.psd2live.ui.components.IconBookmark
import io.github.psd2live.ui.components.IconCloseLoop
import io.github.psd2live.ui.components.IconFreeze
import io.github.psd2live.ui.components.IconMarker
import io.github.psd2live.ui.components.IconShy
import io.github.psd2live.ui.components.IconTrajectory
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.MotionTimelineGeometry
import io.github.psd2live.ui.TimelineViewport
import io.github.psd2live.ui.ValueViewport
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconAutoKey
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.MotionEditorView
import io.github.psd2live.ui.state.MotionKeyRef
import io.github.psd2live.ui.state.MotionEditorState
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.wheelBindingOf
import io.github.psd2live.ui.tutorial.expandShortcutMarkup
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import java.awt.Cursor

private val ROW_HEIGHT = 22.dp
private val RULER_HEIGHT = 20.dp
private const val MOTION_KEY_FIELD = "motion-key-field"
private const val MOTION_CLIP_FIELD = "motion-clip-field"

/** Distinct hues for curves, cycled by track index. */
private val CURVE_COLORS = listOf(
	Color(0xFF5B9BF0), Color(0xFFE8A33D), Color(0xFF5DBB7A), Color(0xFFE0648A),
	Color(0xFF9B7FE0), Color(0xFF44B8C4), Color(0xFFD7C44A), Color(0xFFE07A52),
)

internal fun motionCurveColor(index: Int): Color = CURVE_COLORS[index.mod(CURVE_COLORS.size)]

internal fun builtinMotionTitle(name: String): String = tr("export.motion.${name.replaceFirstChar(Char::lowercase)}")

/** One choice in the editor's motion picker: a generated motion or a user clip, by the id the editor opens. */
private data class MotionChoice(val clipId: String?, val label: String)

/**
 * The animation editor: a timeline of the clip the animation panel opened, one track per parameter, edited
 * as a dopesheet or as curves. The playhead poses the preview canvas.
 */
@Composable
internal fun AnimationEditorView(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val editor = viewModel.motionEditor
	val clip = viewModel.editingMotionClip(state)
	val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()

	Column(Modifier.fillMaxSize().background(colors.panelBackground)) {
		EditorToolbar(state, viewModel, clip)
		if (clip == null) {
			EmptyEditor(viewModel, hasModel = state.previewModel != null)
			return@Column
		}
		val aids = viewModel.openMotionAids(state)
		val bookmarks = state.timelineAids.bookmarks
		// The tracks shown: shy ones hidden while Hide Shy is on, only bookmarked ones while Bookmarks is.
		val rows = remember(clip, aids.shy, bookmarks, editor.hideShy, editor.bookmarksOnly) {
			clip.curves.filter { curve ->
				!(editor.hideShy && curve.parameterId in aids.shy) && (!editor.bookmarksOnly || curve.parameterId in bookmarks)
			}
		}
		val seams = remember(clip, parameters) { if (clip.loop) MotionLoops.seams(clip, parameters) else emptySet() }
		val trackScroll = rememberScrollState()
		var trackWidth by remember { mutableStateOf(200f) }
		val density = LocalDensity.current
		Row(Modifier.weight(1f).fillMaxWidth()) {
			TrackList(viewModel, clip, rows, aids, bookmarks.toSet(), seams, parameters, trackScroll, Modifier.width(trackWidth.dp).fillMaxHeight())
			Box(
				Modifier.width(4.dp).fillMaxHeight().background(colors.divider)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
					.pointerInput(Unit) {
						detectDragGestures { change, drag ->
							change.consume()
							trackWidth = (trackWidth + with(density) { drag.x.toDp().value }).coerceIn(120f, 420f)
						}
					},
			)
			Timeline(viewModel, clip, rows, aids, seams, parameters, trackScroll, Modifier.weight(1f).fillMaxHeight())
		}
		KeyInspector(viewModel, clip, parameters, state.keymap)
		// An undo can leave the selection pointing at keys that are gone.
		LaunchedEffect(clip) {
			val live = editor.selection.filterTo(HashSet()) { ref ->
				clip.curve(ref.parameterId)?.keys?.any(ref::matches) == true
			}
			if (live.size != editor.selection.size) editor.selection = live
			if (editor.playhead > clip.duration) editor.playhead = clip.duration
		}
	}
}

@Composable
private fun EditorToolbar(state: PSD2LiveState, viewModel: PSD2LiveViewModel, clip: MotionClip?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.motionEditor
	val clips = io.github.psd2live.core.MotionCatalog.entries(state.rigEdits)
	val skeleton = state.rigEdits.skeleton
	val presets = state.rigEdits.motionPresets
	val basic = state.rigEdits.modifierStack == null && state.motionBasic
	val skeletonPresets = state.rigEdits.modifierStack == null && state.motionSkeleton
	val choices = remember(clips, skeleton, presets, basic, skeletonPresets) {
		val builtins = MotionClips.BUILTIN_NAMES.filter { name ->
			presets[name]?.deleted != true && (if (MotionClips.isSkeletonPreset(name)) skeletonPresets else basic) &&
				(MotionClips.overrideOf(clips, name) != null || MotionClips.builtinTracks(name, skeleton).isNotEmpty())
		}.map { name ->
			val edited = MotionClips.overrideOf(clips, name) != null
			MotionChoice(MotionEditorState.presetClipId(name), builtinMotionTitle(name) + if (edited) " •" else "")
		}
		val custom = clips.filter { it.builtin == null }.map { MotionChoice(it.id, it.name) }
		listOf(MotionChoice(null, tr("animation.editor.pick"))) + builtins + custom
	}
	val selected = choices.firstOrNull { clip != null && it.clipId == editor.clipId } ?: choices.first()

	// Too many controls to fold away: a narrow editor scrolls its toolbar sideways.
	PanelToolbar {
		Row(
			Modifier.weight(1f).horizontalScroll(rememberScrollState()),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			CompactDropdown(
				items = choices,
				selectedItem = selected,
				onItemSelected = { choice -> choice.clipId?.let { viewModel.openMotionInEditor(it) } },
				itemLabel = { it.label },
				itemEnabled = { it.clipId != null },
				modifier = Modifier.width(150.dp),
				height = 22.dp,
			)
			CompactIconButton(onClick = { viewModel.createMotionClip() }, tooltip = tr("animation.new"), size = 22.dp) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
			PanelToolbarSeparator()
			val enabled = clip != null
			CompactIconButton(
				onClick = { viewModel.stopMotionEditorPlayback() },
				enabled = enabled,
				tooltip = tr("animation.editor.toStart"),
				size = 22.dp,
			) { IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary) }
			CompactIconButton(
				onClick = { viewModel.setMotionEditorPlaying(!editor.playing) },
				enabled = enabled,
				tooltip = tr(if (editor.playing) "animation.pause" else "animation.play"),
				size = 22.dp,
			) {
				if (editor.playing) IconPause(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
				else IconPlay(modifier = Modifier.size(11.dp), tint = colors.accent)
			}
			if (clip == null) {
				PanelToolbarSeparator()
				OnionSkinControl(state, viewModel, null)
				return@Row
			}
			CompactNumberSpinner(
				value = editor.playhead.toDouble(),
				onValueChange = { viewModel.setMotionPlayhead(it.toFloat()) },
				min = 0.0,
				max = clip.duration.toDouble(),
				step = 1.0 / clip.fps,
				decimals = 2,
				unit = "s",
				height = 22.dp,
				modifier = Modifier.width(72.dp),
			)
			Text("/", color = colors.textMuted, style = typography.caption)
			ToolbarLabel(tr("animation.duration"))
			CompactNumberSpinner(
				value = clip.duration.toDouble(),
				onValueChange = { value -> viewModel.updateMotionClipProperties(clip.id) { it.copy(duration = value.toFloat().coerceIn(0.1f, 600f)) } },
				min = 0.1,
				max = 600.0,
				step = 0.1,
				decimals = 2,
				unit = "s",
				height = 22.dp,
				modifier = Modifier.width(72.dp),
				onEditStart = { viewModel.beginEditorField(MOTION_CLIP_FIELD) },
				onEditEnd = { viewModel.endEditorField(MOTION_CLIP_FIELD) },
			)
			ToolbarLabel("FPS")
			CompactNumberSpinner(
				value = clip.fps.toDouble(),
				onValueChange = { value -> viewModel.updateMotionClipProperties(clip.id) { it.copy(fps = value.toFloat().coerceIn(1f, 120f)) } },
				min = 1.0,
				max = 120.0,
				step = 1.0,
				height = 22.dp,
				modifier = Modifier.width(52.dp),
				onEditStart = { viewModel.beginEditorField(MOTION_CLIP_FIELD) },
				onEditEnd = { viewModel.endEditorField(MOTION_CLIP_FIELD) },
			)
			CompactToggleChip(
				text = tr("animation.loop"),
				selected = clip.loop,
				onToggle = { viewModel.updateMotionClipProperties(clip.id) { it.copy(loop = !it.loop) } },
				height = 22.dp,
			)
			CompactToggleChip(
				text = tr("animation.editor.snap"),
				selected = editor.snapToFrames,
				onToggle = { editor.snapToFrames = !editor.snapToFrames },
				height = 22.dp,
			)
			CompactToggleChip(
				text = tr("animation.editor.autoKey"),
				selected = editor.autoKey,
				onToggle = { viewModel.toggleMotionAutoKey() },
				leadingIcon = {
					IconAutoKey(
						modifier = Modifier.size(11.dp),
						active = editor.autoKey,
						tint = if (editor.autoKey) Color(0xFFE05252) else colors.textMuted,
					)
				},
				showCheckWhenSelected = false,
				tooltip = tr("animation.editor.autoKeyTooltip"),
				height = 22.dp,
			)
			PanelToolbarSeparator()
			AddTrackButton(viewModel, clip, state)
			InsertSavedSkeletonPoseMenu(viewModel, state)
			CompactButton(
				text = tr("animation.editor.keyPose"),
				onClick = { viewModel.keyCurrentPose() },
				enabled = clip.curves.isNotEmpty(),
				height = 22.dp,
			)
			PanelToolbarSeparator()
			CompactToggleChip(
				text = tr("animation.editor.dopesheet"),
				selected = editor.view == MotionEditorView.DOPESHEET,
				onToggle = { editor.view = MotionEditorView.DOPESHEET },
				showCheckWhenSelected = false,
				height = 22.dp,
			)
			CompactToggleChip(
				text = tr("animation.editor.curves"),
				selected = editor.view == MotionEditorView.CURVES,
				onToggle = { editor.view = MotionEditorView.CURVES },
				showCheckWhenSelected = false,
				height = 22.dp,
			)
			PanelToolbarSeparator()
			TimelineAidControls(viewModel, clip, state)
			PanelToolbarSeparator()
			OnionSkinControl(state, viewModel, clip)
		}
	}
}

/**
 * The onion skin of the edit canvas the editor works with ([PSD2LiveViewModel.aidCanvas]): its switch, saying what
 * the ghosts show, and its settings beside it. With [clip] open they are its frames around the playhead; otherwise
 * the keys of the parameter being edited around its value.
 */
@Composable
private fun OnionSkinControl(state: PSD2LiveState, viewModel: PSD2LiveViewModel, clip: MotionClip?) {
	val colors = LocalToolColors.current
	val canvas = viewModel.aidCanvas(state)
	val aids = canvas?.view?.aids ?: io.github.psd2live.ui.CanvasAidOptions()
	val on = canvas != null && aids.onionSkin
	val parameter = canvas?.let { viewModel.canvasEditorFor(it.id).parameter }
	val source = when {
		canvas == null -> tr("canvas.aids.onion.noCanvas")
		clip != null -> tr("canvas.aids.onion.frames", clip.name)
		parameter != null -> tr("canvas.aids.onion.keys",
			state.previewModel?.rig?.puppet?.parameters?.firstOrNull { it.id.raw == parameter }?.name ?: parameter)
		else -> tr("canvas.aids.onion.none")
	}
	val shortcut = state.keymap.labelFor(ShortcutAction.TOGGLE_ONION_SKIN)
	var menu by remember { mutableStateOf(false) }
	Row(verticalAlignment = Alignment.CenterVertically) {
		CompactToggleChip(
			text = tr("canvas.aids.onion"),
			selected = on,
			onToggle = { viewModel.setOnionSkin { it.copy(onionSkin = !it.onionSkin) } },
			enabled = canvas != null,
			leadingIcon = { IconOnionSkin(if (on) colors.accent else colors.textMuted) },
			showCheckWhenSelected = false,
			tooltip = if (shortcut != null) "$source ($shortcut)" else source,
			height = 22.dp,
		)
		Box {
			CompactIconButton(onClick = { menu = !menu }, enabled = canvas != null, tooltip = tr("canvas.aids.onion.settings"), size = 22.dp) {
				IconChevron(expanded = true, modifier = Modifier.size(10.dp))
			}
			io.github.psd2live.ui.views.texture.FloatingMenu(expanded = menu, onDismiss = { menu = false }, width = 260.dp) {
				OnionSkinMenuContent(aids, { updated -> viewModel.setOnionSkin { updated } }, source)
			}
		}
	}
}

@Composable
private fun ToolbarLabel(text: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp), maxLines = 1)
}

/** A parameter picker for a new track, filtered as the user types. */
@Composable
private fun AddTrackButton(viewModel: PSD2LiveViewModel, clip: MotionClip, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	var open by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()
	Box {
		CompactButton(
			text = tr("animation.editor.addTrack"),
			onClick = { open = true; query = "" },
			enabled = parameters.isNotEmpty(),
			leadingIcon = { IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary) },
			height = 22.dp,
		)
		TreeContextMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 220.dp, maxWidth = 300.dp) {
			CompactTextField(
				value = query,
				onValueChange = { query = it },
				placeholder = tr("animation.editor.searchParameter"),
				modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
				height = 22.dp,
			)
			val used = clip.curves.mapTo(HashSet()) { it.parameterId }
			val bookmarks = state.timelineAids.bookmarks.toSet()
			// Bookmarked parameters lead the list.
			val matches = parameters.filter { parameter ->
				parameter.id.raw !in used && (query.isBlank() ||
					parameter.id.raw.contains(query, ignoreCase = true) || parameter.name.contains(query, ignoreCase = true))
			}.sortedBy { it.id.raw !in bookmarks }
			Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
				val unusedBookmarks = parameters.filter { it.id.raw in bookmarks && it.id.raw !in used }.map { it.id.raw }
				if (query.isBlank() && unusedBookmarks.isNotEmpty()) {
					CompactMenuItem(
						text = tr("animation.editor.addBookmarked", unusedBookmarks.size),
						onClick = {
							open = false
							viewModel.addMotionCurves(unusedBookmarks)
						},
					)
				}
				if (matches.isEmpty()) {
					Text(
						tr("animation.editor.noParameter"),
						color = colors.textMuted,
						fontSize = 10.sp,
						modifier = Modifier.padding(8.dp),
					)
				}
				for (parameter in matches) {
					CompactMenuItem(
						text = (if (parameter.id.raw in bookmarks) "★ " else "") + parameter.name.ifBlank { parameter.id.raw },
						trailingText = parameter.id.raw.takeIf { it != parameter.name },
						onClick = {
							open = false
							viewModel.addMotionCurve(parameter.id.raw)
						},
					)
				}
			}
		}
	}
}

@Composable
private fun EmptyEditor(viewModel: PSD2LiveViewModel, hasModel: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				tr(if (hasModel) "animation.editor.empty" else "animation.editor.noModel"),
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 11.sp),
			)
			if (hasModel) {
				CompactButton(text = tr("animation.new"), onClick = { viewModel.createMotionClip() }, isPrimary = true, height = 24.dp)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(
	viewModel: PSD2LiveViewModel,
	clip: MotionClip,
	rows: List<MotionCurve>,
	aids: MotionTimelineAids,
	bookmarks: Set<String>,
	seams: Set<String>,
	parameters: List<org.umamo.runtime.model.Parameter>,
	scroll: androidx.compose.foundation.ScrollState,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.motionEditor
	val byId = parameters.associateBy { it.id.raw }
	Column(modifier.background(colors.panelBackground)) {
		Row(
			Modifier.fillMaxWidth().height(RULER_HEIGHT).background(colors.panelElevated).padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				tr("animation.editor.tracks", clip.curves.size) +
					(clip.curves.size - rows.size).takeIf { it > 0 }?.let { " · " + tr("animation.editor.hiddenTracks", it) }.orEmpty(),
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (editor.focusedCurve != null) {
				Text(
					tr("animation.editor.showAll"),
					color = colors.accent,
					style = typography.caption.copy(fontSize = 10.sp),
					modifier = Modifier.clickable { viewModel.focusMotionCurve(null) },
				)
			}
		}
		Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
			val colorIndex = clip.curves.withIndex().associate { it.value.parameterId to it.index }
			rows.forEachIndexed { row, curve ->
				TrackRow(viewModel, clip, curve, row, colorIndex[curve.parameterId] ?: row, byId[curve.parameterId],
					TrackAids(curve.parameterId in bookmarks, curve.parameterId in aids.shy, aids.frozen[curve.parameterId], curve.parameterId in seams))
			}
			if (clip.curves.isEmpty()) {
				Text(
					tr("animation.editor.noTracks"),
					color = colors.textMuted,
					style = typography.caption.copy(fontSize = 10.sp),
					modifier = Modifier.padding(8.dp),
				)
			}
		}
	}
}

/** What the timeline aids say about one track. */
private data class TrackAids(val bookmarked: Boolean, val shy: Boolean, val frozen: Float?, val seam: Boolean)

@Composable
private fun TrackRow(
	viewModel: PSD2LiveViewModel,
	clip: MotionClip,
	curve: MotionCurve,
	row: Int,
	index: Int,
	parameter: org.umamo.runtime.model.Parameter?,
	trackAids: TrackAids,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.motionEditor
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val focused = editor.focusedCurve == curve.parameterId
	val hasSelection = editor.selection.any { it.parameterId == curve.parameterId }
	Row(
		Modifier.fillMaxWidth().height(ROW_HEIGHT)
			.background(
				when {
					focused -> colors.selection
					hasSelection -> colors.accent.copy(alpha = 0.08f)
					hovered -> colors.controlHover.copy(alpha = 0.5f)
					row % 2 == 1 -> colors.windowBackground.copy(alpha = 0.25f)
					else -> Color.Transparent
				},
			)
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null) {
				viewModel.focusMotionCurve(if (focused) null else curve.parameterId)
			}
			.padding(start = 6.dp, end = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Box(Modifier.size(7.dp).clip(CircleShape).background(motionCurveColor(index)))
        val currentState by viewModel.uiState
        val puppet = currentState.previewModel?.rig?.puppet
        val marks = remember(puppet, curve.parameterId) {
            puppet?.parameterKeyMarks()?.get(org.umamo.runtime.model.ParameterId(curve.parameterId))?.allKeys.orEmpty()
        }
        if (parameter != null && marks.isNotEmpty()) Canvas(Modifier.width(36.dp).height(12.dp)) {
            drawLine(colors.textMuted, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            for (value in marks) {
                val x = MotionTimelineGeometry.normalize(value, parameter.min..parameter.max) * size.width
                drawLine(motionCurveColor(index), Offset(x, 1f), Offset(x, size.height - 1f), 1.5f)
            }
        }
		Text(
			parameter?.name?.ifBlank { null } ?: curve.parameterId,
			color = when {
				parameter == null -> colors.warning
				focused -> colors.selectionText
				trackAids.frozen != null -> colors.textMuted
				else -> colors.textPrimary
			},
			style = typography.caption.copy(fontSize = 10.5.sp),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			"%.2f".format(trackAids.frozen ?: MotionClips.sample(curve, editor.playhead.coerceIn(0f, clip.duration))),
			color = if (trackAids.seam) colors.warning else colors.textMuted,
			style = typography.monoSmall.copy(fontSize = 9.5.sp),
			maxLines = 1,
		)
		CompactIconButton(
			onClick = { viewModel.toggleMotionBookmark(curve.parameterId) },
			tooltip = tr(if (trackAids.bookmarked) "animation.editor.unbookmark" else "animation.editor.bookmark"),
			size = 16.dp,
		) { IconBookmark(trackAids.bookmarked, tint = if (trackAids.bookmarked) colors.accent else colors.textMuted) }
		CompactIconButton(
			onClick = { viewModel.toggleMotionShy(curve.parameterId) },
			tooltip = tr(if (trackAids.shy) "animation.editor.unshy" else "animation.editor.shy"),
			size = 16.dp,
		) { IconShy(trackAids.shy, tint = if (trackAids.shy) colors.accent else colors.textMuted) }
		CompactIconButton(
			onClick = { viewModel.toggleMotionFreeze(curve.parameterId) },
			tooltip = trackAids.frozen?.let { tr("animation.editor.thaw") + " · " + tr("animation.editor.frozen", "%.2f".format(it)) }
				?: tr("animation.editor.freeze"),
			size = 16.dp,
		) { IconFreeze(trackAids.frozen != null, tint = if (trackAids.frozen != null) colors.accent else colors.textMuted) }
		// Key the curve at the playhead with its current value.
		CompactIconButton(
			onClick = { viewModel.setMotionKey(curve.parameterId, editor.playhead) },
			enabled = trackAids.frozen == null,
			tooltip = tr("animation.editor.addKey"),
			size = 18.dp,
		) { KeyGlyph(MotionInterpolation.BEZIER, colors.textMuted, 7.dp) }
		CompactIconButton(
			onClick = { viewModel.removeMotionCurve(curve.parameterId) },
			tooltip = tr("animation.editor.removeTrack"),
			size = 18.dp,
		) { IconClose(modifier = Modifier.size(9.dp), tint = if (hovered) colors.textPrimary else colors.textMuted) }
	}
}

@Composable
private fun KeyGlyph(interpolation: MotionInterpolation, tint: Color, size: androidx.compose.ui.unit.Dp) {
	Canvas(Modifier.size(size)) { drawKey(interpolation, center, this.size.minDimension / 2f, tint, null) }
}

/** A key's mark: a diamond for linear, a circle for Bezier, a square for stepped (hollow when inverse). */
private fun DrawScope.drawKey(interpolation: MotionInterpolation, at: Offset, radius: Float, fill: Color, outline: Color?) {
	when (interpolation) {
		MotionInterpolation.LINEAR -> {
			val path = Path().apply {
				moveTo(at.x, at.y - radius); lineTo(at.x + radius, at.y); lineTo(at.x, at.y + radius); lineTo(at.x - radius, at.y); close()
			}
			drawPath(path, fill)
			if (outline != null) drawPath(path, outline, style = Stroke(1.dp.toPx()))
		}
		MotionInterpolation.BEZIER -> {
			drawCircle(fill, radius * 0.9f, at)
			if (outline != null) drawCircle(outline, radius * 0.9f, at, style = Stroke(1.dp.toPx()))
		}
		MotionInterpolation.STEPPED, MotionInterpolation.INVERSE_STEPPED -> {
			val side = radius * 1.5f
			val topLeft = Offset(at.x - side / 2f, at.y - side / 2f)
			if (interpolation == MotionInterpolation.STEPPED) drawRect(fill, topLeft, Size(side, side))
			else drawRect(fill, topLeft, Size(side, side), style = Stroke(1.5.dp.toPx()))
			if (outline != null) drawRect(outline, topLeft, Size(side, side), style = Stroke(1.dp.toPx()))
		}
	}
}

/** Where a press on the timeline started, so its drag knows what it is moving. */
private sealed interface TimelinePress {
	data object Ruler : TimelinePress
	data class Key(val ref: MotionKeyRef) : TimelinePress
	data class Handle(val ref: MotionKeyRef, val outgoing: Boolean) : TimelinePress
	data object Empty : TimelinePress
	/** A marker on the ruler: a drag moves it, a click edits it. */
	data class Marker(val marker: TimelineMarker) : TimelinePress
	/** The clip's end on the ruler: a drag sets the loop length. */
	data object LoopEnd : TimelinePress
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Timeline(
	viewModel: PSD2LiveViewModel,
	clip: MotionClip,
	rows: List<MotionCurve>,
	aids: MotionTimelineAids,
	seams: Set<String>,
	parameters: List<org.umamo.runtime.model.Parameter>,
	trackScroll: androidx.compose.foundation.ScrollState,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	val editor = viewModel.motionEditor
	val textMeasurer = rememberTextMeasurer()
	val focusRequester = remember { FocusRequester() }
	val ranges = remember(parameters) { parameters.associate { it.id.raw to it.min..it.max } }
    val currentState by viewModel.uiState
        val puppet = currentState.previewModel?.rig?.puppet
    val keyMarks = remember(puppet) { puppet?.parameterKeyMarks().orEmpty() }

	BoxWithConstraints(modifier.background(colors.inputBackground).clip(RoundedCornerShape(0.dp))) {
		val widthPx = constraints.maxWidth.toFloat()
		val heightPx = constraints.maxHeight.toFloat()
		val rulerPx = with(density) { RULER_HEIGHT.toPx() }
		val rowPx = with(density) { ROW_HEIGHT.toPx() }
		var viewport by remember(clip.id) { mutableStateOf(TimelineViewport.fit(clip.duration, widthPx)) }
		var valueViewport by remember(clip.id) { mutableStateOf(ValueViewport()) }
		var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
		LaunchedEffect(clip.id, widthPx > 0f) { if (widthPx > 0f) viewport = TimelineViewport.fit(clip.duration, widthPx) }

		val curvesMode = editor.view == MotionEditorView.CURVES
		val curveIndex = clip.curves.withIndex().associate { it.value.parameterId to it.index }
		val visibleCurves = if (curvesMode) {
			editor.focusedCurve?.let { id -> rows.filter { it.parameterId == id } }?.ifEmpty { null } ?: rows
		} else rows
		val plotTop = rulerPx
		val plotHeight = (heightPx - rulerPx).coerceAtLeast(1f)
		val frozen = aids.frozen
		var markerDrag by remember(clip.id) { mutableStateOf<TimelineMarker?>(null) }
		var loopEndDrag by remember(clip.id) { mutableStateOf<Float?>(null) }
		var editingMarker by remember(clip.id) { mutableStateOf<TimelineMarker?>(null) }
		val markers = aids.markers.map { marker -> markerDrag?.takeIf { it.id == marker.id } ?: marker }

		// A frozen track's keys stay where they are: they cannot be picked.
		fun keyPoints(): List<Pair<MotionKeyRef, Offset>> = if (curvesMode) {
			visibleCurves.filter { it.parameterId !in frozen }.flatMap { curve ->
				val range = ranges[curve.parameterId]
				curve.keys.map { key ->
					MotionKeyRef(curve.parameterId, key.time) to Offset(
						viewport.x(key.time),
						valueViewport.y(MotionTimelineGeometry.normalize(key.value, range), plotTop, plotHeight),
					)
				}
			}
		} else {
			rows.flatMapIndexed { index, curve ->
				if (curve.parameterId in frozen) return@flatMapIndexed emptyList()
				val y = rulerPx + index * rowPx + rowPx / 2f - trackScroll.value
				curve.keys.map { key -> MotionKeyRef(curve.parameterId, key.time) to Offset(viewport.x(key.time), y) }
			}
		}

		/** Bezier handles of the selected keys, as (ref, outgoing) to position. */
		fun handlePoints(): List<Pair<Pair<MotionKeyRef, Boolean>, Offset>> {
			if (!curvesMode) return emptyList()
			return visibleCurves.filter { it.parameterId !in frozen }.flatMap { curve ->
				val range = ranges[curve.parameterId]
				fun point(time: Float, value: Float) = Offset(
					viewport.x(time),
					valueViewport.y(MotionTimelineGeometry.normalize(value, range), plotTop, plotHeight),
				)
				curve.keys.withIndex().flatMap { (i, key) ->
					val ref = MotionKeyRef(curve.parameterId, key.time)
					if (editor.selection.none { it == ref || (it.parameterId == ref.parameterId && it.matches(key)) }) return@flatMap emptyList()
					buildList {
						val next = curve.keys.getOrNull(i + 1)
						if (next != null && key.interpolation == MotionInterpolation.BEZIER) {
							val (c1, _) = MotionClips.controlPoints(key, next)
							add((ref to true) to point(c1.first, c1.second))
						}
						val prev = curve.keys.getOrNull(i - 1)
						if (prev != null && prev.interpolation == MotionInterpolation.BEZIER) {
							val (_, c2) = MotionClips.controlPoints(prev, key)
							add((ref to false) to point(c2.first, c2.second))
						}
					}
				}
			}
		}

		val latestClip by rememberUpdatedState(clip)
		val latestViewport by rememberUpdatedState(viewport)
		val latestValueViewport by rememberUpdatedState(valueViewport)
		val latestKeyPoints by rememberUpdatedState(keyPoints())
		val latestHandles by rememberUpdatedState(handlePoints())
		val latestCurvesMode by rememberUpdatedState(curvesMode)
		val latestVisible by rememberUpdatedState(visibleCurves)
		val latestRows by rememberUpdatedState(rows)
		val latestFrozen by rememberUpdatedState(frozen)
		val latestMarkers by rememberUpdatedState(aids.markers)
		var lastClickNanos by remember { mutableStateOf(0L) }
		var lastClickPosition by remember { mutableStateOf(Offset.Zero) }

		fun snapTime(time: Float) = MotionTimelineGeometry.snap(time, latestClip.fps, editor.snapToFrames)

		fun fitView() {
			viewport = TimelineViewport.fit(latestClip.duration, widthPx)
			if (latestCurvesMode) {
				val chosen = MotionKeyEditsView.selectedOrAll(latestClip, editor.selection, latestVisible)
				valueViewport = ValueViewport.fit(chosen.map { (id, value) -> MotionTimelineGeometry.normalize(value, ranges[id]) })
			}
		}

		// The timeline's commands, from a key, a wheel notch or a button click; all of them rebindable in
		// Settings -> Shortcuts (ShortcutScope.TIMELINE). False lets the input through.
		fun runTimelineCommand(action: ShortcutAction?): Boolean {
			when (action) {
				ShortcutAction.MOTION_DELETE_KEYS -> viewModel.deleteSelectedMotionKeys()
				ShortcutAction.MOTION_COPY_KEYS -> viewModel.copySelectedMotionKeys()
				ShortcutAction.MOTION_PASTE_KEYS -> viewModel.pasteMotionKeys()
				ShortcutAction.MOTION_SELECT_ALL -> editor.selection = latestRows.filter { it.parameterId !in latestFrozen }
					.flatMapTo(HashSet()) { curve -> curve.keys.map { MotionKeyRef(curve.parameterId, it.time) } }
				ShortcutAction.MOTION_PLAY_PAUSE -> viewModel.setMotionEditorPlaying(!editor.playing)
				ShortcutAction.MOTION_TOGGLE_AUTO_KEY -> viewModel.toggleMotionAutoKey()
				ShortcutAction.MOTION_KEY_POSE -> viewModel.keyCurrentPose()
				ShortcutAction.MOTION_FIT_VIEW -> fitView()
				ShortcutAction.MOTION_GO_START -> viewModel.setMotionPlayhead(0f)
				ShortcutAction.MOTION_GO_END -> viewModel.setMotionPlayhead(latestClip.duration)
				ShortcutAction.MOTION_PREV_FRAME -> viewModel.setMotionPlayhead(editor.playhead - 1f / latestClip.fps)
				ShortcutAction.MOTION_NEXT_FRAME -> viewModel.setMotionPlayhead(editor.playhead + 1f / latestClip.fps)
				ShortcutAction.MOTION_ADD_MARKER -> viewModel.addMotionMarker()
				ShortcutAction.MOTION_PREV_MARKER -> viewModel.jumpToMotionMarker(forward = false)
				ShortcutAction.MOTION_NEXT_MARKER -> viewModel.jumpToMotionMarker(forward = true)
				ShortcutAction.MOTION_HIDE_SHY -> editor.hideShy = !editor.hideShy
				ShortcutAction.MOTION_CLOSE_LOOP -> viewModel.closeMotionLoop()
				// The track commands act on the focused track.
				ShortcutAction.MOTION_TOGGLE_BOOKMARK -> editor.focusedCurve?.let(viewModel::toggleMotionBookmark)
				ShortcutAction.MOTION_TOGGLE_SHY -> editor.focusedCurve?.let(viewModel::toggleMotionShy)
				ShortcutAction.MOTION_TOGGLE_FREEZE -> editor.focusedCurve?.let(viewModel::toggleMotionFreeze)
				else -> return false
			}
			return true
		}

		Canvas(
			Modifier.fillMaxSize()
				.focusRequester(focusRequester)
				.focusable()
				.onPreviewKeyEvent { event ->
					if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
					val state = viewModel.state.value
					if (state.keyCapture != null) return@onPreviewKeyEvent false
					runTimelineCommand(state.keymap.match(event, ShortcutScope.TIMELINE))
				}
				// A middle or side button bound to a timeline command fires it.
				.onPointerEvent(PointerEventType.Press) { event ->
					val state = viewModel.state.value
					if (state.keyCapture != null) return@onPointerEvent
					val action = state.keymap.mouseCommand(buttonBindingOf(event.button, event.keyboardModifiers), ShortcutScope.TIMELINE)
					if (runTimelineCommand(action)) event.changes.forEach { it.consume() }
				}
				.onPointerEvent(PointerEventType.Scroll) { event ->
					val change = event.changes.firstOrNull() ?: return@onPointerEvent
					val state = viewModel.state.value
					if (state.keyCapture == null && runTimelineCommand(
							state.keymap.mouseCommand(wheelBindingOf(change.scrollDelta, event.keyboardModifiers), ShortcutScope.TIMELINE),
						)
					) {
						change.consume()
						return@onPointerEvent
					}
					val delta = change.scrollDelta.y
					val mods = event.keyboardModifiers
					when {
						mods.isCtrlPressed || mods.isMetaPressed -> {
							val factor = if (delta < 0) 1.15f else 1f / 1.15f
							if (mods.isShiftPressed && latestCurvesMode) {
								val anchor = latestValueViewport.normalized(change.position.y, plotTop, plotHeight)
								valueViewport = latestValueViewport.zoomed(factor, anchor)
							} else viewport = latestViewport.zoomed(factor, change.position.x)
						}
						mods.isShiftPressed || change.scrollDelta.x != 0f ->
							viewport = latestViewport.panned((if (change.scrollDelta.x != 0f) change.scrollDelta.x else delta) * 40f)
						latestCurvesMode -> {
							val shift = delta * 24f * latestValueViewport.perPixel(plotHeight)
							valueViewport = ValueViewport(latestValueViewport.low - shift, latestValueViewport.high - shift)
						}
						else -> trackScroll.dispatchRawDelta(delta * 40f)
					}
					change.consume()
				}
				.pointerInput(rulerPx, rowPx, plotHeight, ranges) {
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						focusRequester.requestFocus()
						val mods = currentEvent.keyboardModifiers
						val additive = mods.isShiftPressed
						val toggle = mods.isCtrlPressed || mods.isMetaPressed
						val start = down.position
						val slop = viewConfiguration.touchSlop
						val press = when {
							start.y < rulerPx -> {
								val vp = latestViewport
								val reach = 5.dp.toPx()
								val marker = latestMarkers.filter { kotlin.math.abs(vp.x(it.time) - start.x) <= reach }
									.minByOrNull { kotlin.math.abs(vp.x(it.time) - start.x) }
								when {
									marker != null -> TimelinePress.Marker(marker)
									kotlin.math.abs(vp.x(latestClip.duration) - start.x) <= reach -> TimelinePress.LoopEnd
									else -> TimelinePress.Ruler
								}
							}
							else -> MotionTimelineGeometry.hit(latestHandles, start, 7.dp.toPx())
								?.let { (ref, outgoing) -> TimelinePress.Handle(ref, outgoing) }
								?: MotionTimelineGeometry.hit(latestKeyPoints, start, 7.dp.toPx())?.let { TimelinePress.Key(it) }
								?: TimelinePress.Empty
						}
						val clipAtPress = latestClip
						val viewportAtPress = latestViewport
						val valueAtPress = latestValueViewport
						var dragging = false
						var anchorTime = 0f

						when (press) {
							TimelinePress.Ruler -> viewModel.setMotionPlayhead(snapTime(viewportAtPress.time(start.x)))
							is TimelinePress.Key -> {
								anchorTime = press.ref.time
								val selected = editor.selection.any { it.parameterId == press.ref.parameterId && it.time == press.ref.time }
								editor.selection = when {
									toggle -> if (selected) editor.selection - press.ref else editor.selection + press.ref
									additive -> editor.selection + press.ref
									selected -> editor.selection
									else -> setOf(press.ref)
								}
								editor.focusedCurve = if (latestCurvesMode) editor.focusedCurve else press.ref.parameterId
							}
							else -> Unit
						}

						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed) break
							val position = change.position
							if (!dragging && MotionTimelineGeometry.isDrag(start, position, slop)) {
								dragging = true
								when (press) {
									is TimelinePress.Key -> if (!toggle) viewModel.beginMotionKeyDrag()
									is TimelinePress.Handle -> viewModel.beginMotionKeyDrag()
									else -> Unit
								}
							}
							if (!dragging && press != TimelinePress.Ruler) continue
							change.consume()
							when (press) {
								TimelinePress.Ruler -> viewModel.setMotionPlayhead(snapTime(viewportAtPress.time(position.x)))
								is TimelinePress.Key -> if (!toggle) {
									val dt = snapTime(anchorTime + (position.x - start.x) / viewportAtPress.pxPerSecond) - anchorTime
									val dv = if (latestCurvesMode) -(position.y - start.y) * valueAtPress.perPixel(plotHeight) else 0f
									viewModel.dragMotionKeys(dt, dv, normalized = true)
								}
								is TimelinePress.Handle -> {
									val curve = clipAtPress.curve(press.ref.parameterId) ?: continue
									val index = curve.keys.indexOfFirst(press.ref::matches).takeIf { it >= 0 } ?: continue
									val key = curve.keys[index]
									val other = curve.keys.getOrNull(if (press.outgoing) index + 1 else index - 1) ?: continue
									val span = kotlin.math.abs(other.time - key.time).coerceAtLeast(1e-4f)
									val range = ranges[press.ref.parameterId]
									val time = viewportAtPress.time(position.x)
									val value = MotionTimelineGeometry.denormalize(valueAtPress.normalized(position.y, plotTop, plotHeight), range)
									val x = if (press.outgoing) (time - key.time) / span else (key.time - time) / span
									viewModel.dragMotionHandle(press.ref, press.outgoing, MotionHandle(x.coerceIn(0f, 1f), value - key.value))
								}
								TimelinePress.Empty -> box = start to position
								is TimelinePress.Marker -> markerDrag = press.marker.copy(
									time = snapTime(viewportAtPress.time(position.x)).coerceIn(0f, clipAtPress.duration))
								TimelinePress.LoopEnd -> loopEndDrag = snapTime(viewportAtPress.time(position.x)).coerceIn(0.1f, 600f)
							}
						}

						when (press) {
							is TimelinePress.Key, is TimelinePress.Handle -> if (dragging) viewModel.endMotionKeyDrag()
								else if (press is TimelinePress.Key && !toggle && !additive) editor.selection = setOf(press.ref)
							TimelinePress.Empty -> {
								val area = box
								box = null
								if (area != null) {
									val picked = MotionTimelineGeometry.boxSelect(latestKeyPoints, area.first, area.second)
									editor.selection = if (additive || toggle) editor.selection + picked else picked
								} else {
									val now = System.nanoTime()
									val doubleClick = now - lastClickNanos < 400_000_000L && (start - lastClickPosition).getDistance() < 6.dp.toPx()
									lastClickNanos = now
									lastClickPosition = start
									val rowCurve = if (latestCurvesMode) {
										editor.focusedCurve ?: latestVisible.singleOrNull()?.parameterId
									} else {
										val row = ((start.y - rulerPx + trackScroll.value) / rowPx).toInt()
										latestRows.getOrNull(row)?.parameterId
									}
									if (doubleClick && rowCurve != null) {
										val time = snapTime(viewportAtPress.time(start.x))
										val value = if (latestCurvesMode) MotionTimelineGeometry.denormalize(
											valueAtPress.normalized(start.y, plotTop, plotHeight), ranges[rowCurve],
										) else null
										viewModel.setMotionKey(rowCurve, time, value)
									} else if (!additive && !toggle) {
										editor.selection = emptySet()
										if (!latestCurvesMode) editor.focusedCurve = rowCurve
									}
								}
							}
							TimelinePress.Ruler -> Unit
							is TimelinePress.Marker -> {
								val moved = markerDrag
								markerDrag = null
								if (dragging && moved != null) viewModel.updateMotionMarker(moved)
								else {
									viewModel.setMotionPlayhead(press.marker.time)
									editingMarker = press.marker
								}
							}
							TimelinePress.LoopEnd -> {
								val length = loopEndDrag
								loopEndDrag = null
								if (dragging && length != null && length != clipAtPress.duration)
									viewModel.updateMotionClipProperties(clipAtPress.id) { it.copy(duration = length) }
							}
						}
					}
				},
		) {
			val vp = viewport
			val endX = vp.x(loopEndDrag ?: clip.duration)
			val zeroX = vp.x(0f)
			// Outside the clip's span.
			if (zeroX > 0f) drawRect(colors.windowBackground.copy(alpha = 0.55f), Offset(0f, rulerPx), Size(zeroX, size.height))
			if (endX < size.width) drawRect(colors.windowBackground.copy(alpha = 0.55f), Offset(endX, rulerPx), Size(size.width - endX, size.height))

			val major = MotionTimelineGeometry.majorStep(vp.pxPerSecond)
			val minor = MotionTimelineGeometry.minorStep(major)
			val t0 = vp.time(0f)
			val t1 = vp.time(size.width)
			// Frame grid once frames are far enough apart to see.
			val framePx = vp.pxPerSecond / clip.fps
			if (framePx >= 8f) for (t in MotionTimelineGeometry.ticks(t0, t1, 1f / clip.fps)) {
				val x = vp.x(t)
				drawLine(colors.divider.copy(alpha = 0.35f), Offset(x, rulerPx), Offset(x, size.height), 1f)
			}
			for (t in MotionTimelineGeometry.ticks(t0, t1, major)) {
				val x = vp.x(t)
				drawLine(colors.divider, Offset(x, rulerPx), Offset(x, size.height), 1f)
			}

			clipRect(top = rulerPx) {
				val ghost = clip.duration.takeIf { clip.loop }
				if (curvesMode) drawCurves(clip, visibleCurves, curveIndex, ranges, vp, valueViewport, plotTop, plotHeight, editor.selection, colors.textPrimary, colors.divider,
					frozen, ghost, parameters.filter { it.repeat && ranges.containsKey(it.id.raw) }.mapTo(HashSet()) { it.id.raw })
				else drawDopesheet(rows, curveIndex, vp, rulerPx, rowPx, trackScroll.value.toFloat(), editor.selection, editor.focusedCurve, colors.textPrimary, colors.textMuted, colors.selection,
					frozen.keys, seams, ghost, colors.warning)
				// Markers run down the plot as faint rules.
				for (marker in markers) {
					val x = vp.x(marker.time)
					if (x in 0f..size.width) drawLine(motionCurveColor(marker.color).copy(alpha = 0.45f), Offset(x, rulerPx), Offset(x, size.height), 1f,
						pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f)))
				}
                if (curvesMode) for (curve in visibleCurves) {
                    val marks = keyMarks[org.umamo.runtime.model.ParameterId(curve.parameterId)]?.allKeys.orEmpty()
                    val color = motionCurveColor(curveIndex[curve.parameterId] ?: 0)
                    for (value in marks) {
                        val y = valueViewport.y(MotionTimelineGeometry.normalize(value, ranges[curve.parameterId]), plotTop, plotHeight)
                        drawLine(color.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
                        val label = textMeasurer.measure("${curve.parameterId}: ${"%.2f".format(value)}",
                            typography.monoSmall.copy(fontSize = 9.sp, color = color))
                        drawText(label, topLeft = Offset(4.dp.toPx(), y - label.size.height))
                    }
                }
				if (curvesMode) for ((pair, point) in handlePoints()) {
					val (ref, _) = pair
					val curve = clip.curve(ref.parameterId) ?: continue
					val key = curve.keys.firstOrNull(ref::matches) ?: continue
					val anchor = Offset(vp.x(key.time), valueViewport.y(MotionTimelineGeometry.normalize(key.value, ranges[ref.parameterId]), plotTop, plotHeight))
					drawLine(colors.textMuted, anchor, point, 1f)
					drawCircle(colors.panelBackground, 3.5.dp.toPx(), point)
					drawCircle(colors.accent, 3.5.dp.toPx(), point, style = Stroke(1.2.dp.toPx()))
				}
				box?.let { (a, b) ->
					val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
					val boxSize = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
					drawRect(colors.accent.copy(alpha = 0.12f), topLeft, boxSize)
					drawRect(colors.accent, topLeft, boxSize, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
				}
			}

			// Ruler.
			drawRect(colors.panelElevated, Offset.Zero, Size(size.width, rulerPx))
			drawLine(colors.divider, Offset(0f, rulerPx), Offset(size.width, rulerPx), 1f)
			for (t in MotionTimelineGeometry.ticks(t0, t1, minor)) {
				val x = vp.x(t)
				drawLine(colors.textMuted.copy(alpha = 0.5f), Offset(x, rulerPx - 4.dp.toPx()), Offset(x, rulerPx), 1f)
			}
			val labelStyle = typography.monoSmall.copy(fontSize = 9.sp, color = colors.textMuted)
			for (t in MotionTimelineGeometry.ticks(t0, t1, major)) {
				val x = vp.x(t)
				drawLine(colors.textMuted, Offset(x, rulerPx - 8.dp.toPx()), Offset(x, rulerPx), 1f)
				val layout = textMeasurer.measure(MotionTimelineGeometry.formatTime(t, major), labelStyle)
				drawText(layout, topLeft = Offset(x + 3.dp.toPx(), 1.dp.toPx()))
			}
			// A loop's span: a band along the ruler's foot from the start to the end it wraps at.
			if (clip.loop) drawRect(colors.accent.copy(alpha = 0.26f), Offset(zeroX.coerceAtLeast(0f), rulerPx - 3.dp.toPx()),
				Size((endX.coerceAtMost(size.width) - zeroX.coerceAtLeast(0f)).coerceAtLeast(0f), 3.dp.toPx()))
			if (endX in 0f..size.width) {
				drawLine(colors.warning, Offset(endX, 0f), Offset(endX, size.height), if (loopEndDrag != null) 2.5f else 1.5f)
				// The grip that drags the end.
				drawRect(colors.warning, Offset(endX - 2.dp.toPx(), 2.dp.toPx()), Size(4.dp.toPx(), rulerPx - 6.dp.toPx()))
			}
			// Markers: a flag on the ruler, its label beside it.
			for (marker in markers) {
				val x = vp.x(marker.time)
				if (x < -40f || x > size.width) continue
				val tint = motionCurveColor(marker.color)
				val flag = Path().apply {
					moveTo(x, 2.dp.toPx()); lineTo(x + 7.dp.toPx(), 5.dp.toPx()); lineTo(x, 8.dp.toPx()); close()
				}
				drawLine(tint, Offset(x, 2.dp.toPx()), Offset(x, rulerPx), 1.5f)
				drawPath(flag, tint)
				if (marker.label.isNotEmpty()) {
					val layout = textMeasurer.measure(marker.label, typography.monoSmall.copy(fontSize = 9.sp, color = colors.textPrimary), maxLines = 1)
					drawRect(colors.panelElevated, Offset(x + 8.dp.toPx(), 1.dp.toPx()), Size(layout.size.width + 2f, layout.size.height.toFloat()))
					drawText(layout, topLeft = Offset(x + 9.dp.toPx(), 1.dp.toPx()))
				}
			}

			// Playhead.
			val px = vp.x(editor.playhead)
			if (px in -2f..size.width + 2f) {
				drawLine(colors.accent, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
				val head = Path().apply {
					moveTo(px - 5.dp.toPx(), 0f); lineTo(px + 5.dp.toPx(), 0f)
					lineTo(px + 5.dp.toPx(), rulerPx - 7.dp.toPx()); lineTo(px, rulerPx); lineTo(px - 5.dp.toPx(), rulerPx - 7.dp.toPx()); close()
				}
				drawPath(head, colors.accent)
			}
		}
		editingMarker?.let { marker ->
			Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(viewport.x(marker.time).toInt(), rulerPx.toInt()) }) {
				MarkerEditor(viewModel, marker, clip) {
					editingMarker = null
					focusRequester.requestFocus()
				}
			}
		}
	}
}

/** A marker's label, colour and time, and its deletion; each change saves at once. */
@Composable
private fun MarkerEditor(viewModel: PSD2LiveViewModel, marker: TimelineMarker, clip: MotionClip, onDismiss: () -> Unit) {
	val colors = LocalToolColors.current
	var label by remember(marker.id) { mutableStateOf(marker.label) }
	var shown by remember(marker.id) { mutableStateOf(marker) }
	fun save(next: TimelineMarker) {
		shown = next
		viewModel.updateMotionMarker(next)
	}
	// Stepping the time writes once it settles, not once per step: each write is a saved-data edit.
	var time by remember(marker.id) { mutableStateOf(marker.time) }
	LaunchedEffect(time) {
		if (time == shown.time) return@LaunchedEffect
		kotlinx.coroutines.delay(350)
		save(shown.copy(time = time))
	}
	TreeContextMenu(expanded = true, onDismissRequest = {
		if (label != shown.label || time != shown.time) save(shown.copy(label = label, time = time))
		onDismiss()
	},
		minWidth = 200.dp, maxWidth = 240.dp) {
		Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				ToolbarLabel(tr("animation.editor.marker.label"))
				CompactTextField(
					value = label,
					onValueChange = { label = it.take(io.github.psd2live.project.TimelineAidsEdits.MAX_LABEL).filterNot(Char::isISOControl) },
					placeholder = tr("animation.editor.marker.untitled"),
					onCommit = { save(shown.copy(label = label)) },
					modifier = Modifier.weight(1f),
					height = 22.dp,
				)
			}
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				ToolbarLabel(tr("animation.editor.time"))
				CompactNumberSpinner(
					value = time.toDouble(),
					onValueChange = { time = it.toFloat().coerceIn(0f, clip.duration) },
					min = 0.0,
					max = clip.duration.toDouble(),
					step = 1.0 / clip.fps,
					decimals = 3,
					unit = "s",
					height = 22.dp,
					modifier = Modifier.width(88.dp),
				)
			}
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				ToolbarLabel(tr("animation.editor.marker.color"))
				for (index in 0 until io.github.psd2live.project.TimelineAidsEdits.COLORS) {
					val chosen = index == shown.color
					Box(
						Modifier.size(14.dp).clip(CircleShape).background(motionCurveColor(index))
							.border(BorderStroke(if (chosen) 2.dp else 0.dp, if (chosen) colors.textPrimary else Color.Transparent), CircleShape)
							.handCursor()
							.clickable(interactionSource = null, indication = null) { save(shown.copy(color = index)) },
					)
				}
			}
			CompactButton(
				text = tr("animation.editor.marker.delete"),
				onClick = { viewModel.deleteMotionMarker(marker.id); onDismiss() },
				danger = true,
				height = 22.dp,
			)
		}
	}
}

/** The toolbar's timeline aids: hide shy tracks, bookmarks only, the trajectory, a marker and closing the loop. */
@Composable
private fun TimelineAidControls(viewModel: PSD2LiveViewModel, clip: MotionClip?, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val editor = viewModel.motionEditor
	val enabled = clip != null
	val keymap = state.keymap
	fun hint(text: String, action: ShortcutAction) = keymap.labelFor(action)?.let { "$text ($it)" } ?: text
	CompactToggleChip(
		text = tr("animation.editor.hideShy"),
		selected = editor.hideShy,
		onToggle = { editor.hideShy = !editor.hideShy },
		enabled = enabled,
		leadingIcon = { IconShy(true, tint = if (editor.hideShy) colors.accent else colors.textMuted) },
		showCheckWhenSelected = false,
		tooltip = hint(tr("animation.editor.hideShyTooltip"), ShortcutAction.MOTION_HIDE_SHY),
		height = 22.dp,
	)
	CompactToggleChip(
		text = tr("animation.editor.bookmarksOnly"),
		selected = editor.bookmarksOnly,
		onToggle = { editor.bookmarksOnly = !editor.bookmarksOnly },
		enabled = enabled,
		leadingIcon = { IconBookmark(true, tint = if (editor.bookmarksOnly) colors.accent else colors.textMuted) },
		showCheckWhenSelected = false,
		tooltip = tr("animation.editor.bookmarksOnlyTooltip"),
		height = 22.dp,
	)
	CompactToggleChip(
		text = tr("animation.editor.trajectory"),
		selected = editor.showTrajectory,
		onToggle = { editor.showTrajectory = !editor.showTrajectory },
		enabled = enabled,
		leadingIcon = { IconTrajectory(tint = if (editor.showTrajectory) colors.accent else colors.textMuted) },
		showCheckWhenSelected = false,
		tooltip = tr("animation.editor.trajectoryTooltip"),
		height = 22.dp,
	)
	CompactIconButton(
		onClick = { viewModel.addMotionMarker() },
		enabled = enabled,
		tooltip = hint(tr("animation.editor.addMarker"), ShortcutAction.MOTION_ADD_MARKER),
		size = 22.dp,
	) { IconMarker(tint = colors.textPrimary) }
	CompactIconButton(
		onClick = { viewModel.closeMotionLoop() },
		enabled = enabled && clip?.curves?.isNotEmpty() == true,
		tooltip = hint(tr("animation.editor.closeLoopTooltip"), ShortcutAction.MOTION_CLOSE_LOOP),
		size = 22.dp,
	) { IconCloseLoop(tint = colors.textPrimary) }
}

private fun DrawScope.drawDopesheet(
	curves: List<MotionCurve>,
	curveIndex: Map<String, Int>,
	vp: TimelineViewport,
	rulerPx: Float,
	rowPx: Float,
	scroll: Float,
	selection: Set<MotionKeyRef>,
	focused: String?,
	selectedColor: Color,
	idleColor: Color,
	focusFill: Color,
	frozen: Set<String>,
	seams: Set<String>,
	/** A looping clip's duration: the keys of the cycles either side show faintly. */
	ghost: Float?,
	warning: Color,
) {
	val radius = 5.dp.toPx()
	curves.forEachIndexed { index, curve ->
		val top = rulerPx + index * rowPx - scroll
		if (top > size.height || top + rowPx < rulerPx) return@forEachIndexed
		if (curve.parameterId == focused) drawRect(focusFill.copy(alpha = 0.45f), Offset(0f, top), Size(size.width, rowPx))
		else if (index % 2 == 1) drawRect(Color.Black.copy(alpha = 0.06f), Offset(0f, top), Size(size.width, rowPx))
		drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, top + rowPx), Offset(size.width, top + rowPx), 1f)
		val y = top + rowPx / 2f
		val isFrozen = curve.parameterId in frozen
		val color = motionCurveColor(curveIndex[curve.parameterId] ?: index).let { if (isFrozen) it.copy(alpha = 0.4f) else it }
		// A frozen track is hatched: it holds one value whatever its keys say.
		if (isFrozen) {
			var x = -rowPx
			while (x < size.width) {
				drawLine(idleColor.copy(alpha = 0.18f), Offset(x, top + rowPx), Offset(x + rowPx, top), 1f)
				x += 6.dp.toPx()
			}
		}
		// A bar where the value changes between keys, none where it holds.
		for ((a, b) in curve.keys.zipWithNext()) {
			if (a.value == b.value) continue
			drawLine(color.copy(alpha = color.alpha * 0.45f), Offset(vp.x(a.time), y), Offset(vp.x(b.time), y), 3.dp.toPx())
		}
		if (ghost != null) for (shift in listOf(-ghost, ghost)) for (key in curve.keys) {
			val at = key.time + shift
			// The clip's own span shows its own keys.
			if (at > -1e-4f && at < ghost + 1e-4f) continue
			drawKey(key.interpolation, Offset(vp.x(at), y), radius * 0.8f, color.copy(alpha = color.alpha * 0.3f), null)
		}
		for (key in curve.keys) {
			val selected = selection.any { it.parameterId == curve.parameterId && it.matches(key) }
			drawKey(key.interpolation, Offset(vp.x(key.time), y), radius, if (selected) selectedColor else color, if (selected) color else idleColor.copy(alpha = 0.6f))
		}
		// A loop that jumps at its seam: a warning tick where it wraps.
		if (ghost != null && curve.parameterId in seams) {
			val x = vp.x(ghost)
			drawLine(warning, Offset(x - 3.dp.toPx(), y - 4.dp.toPx()), Offset(x + 3.dp.toPx(), y + 4.dp.toPx()), 2.dp.toPx())
		}
	}
}

private fun DrawScope.drawCurves(
	clip: MotionClip,
	curves: List<MotionCurve>,
	curveIndex: Map<String, Int>,
	ranges: Map<String, ClosedFloatingPointRange<Float>>,
	vp: TimelineViewport,
	values: ValueViewport,
	top: Float,
	height: Float,
	selection: Set<MotionKeyRef>,
	selectedColor: Color,
	gridColor: Color,
	frozen: Map<String, Float>,
	/** A looping clip's duration: the cycles either side show faintly. */
	ghost: Float?,
	/** Repeating parameters: each whole turn of a shown one's range gets a rule, and its cycles go on by their turns. */
	repeating: Set<String>,
) {
	val turns = curves.any { it.parameterId in repeating }
	// Parameter minimum, middle and maximum.
	for (level in listOf(0f, 0.5f, 1f)) {
		val y = values.y(level, top, height)
		drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f,
			pathEffect = if (level == 0.5f) PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) else null)
	}
	if (turns) for (level in kotlin.math.floor(values.low).toInt()..kotlin.math.ceil(values.high).toInt()) {
		if (level == 0 || level == 1) continue
		val y = values.y(level.toFloat(), top, height)
		drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
	}
	val step = 2f
	for (curve in curves) {
		val range = ranges[curve.parameterId]
		val frozenValue = frozen[curve.parameterId]
		val color = motionCurveColor(curveIndex[curve.parameterId] ?: 0).let { if (frozenValue != null) it.copy(alpha = 0.4f) else it }
		if (frozenValue != null) {
			val y = values.y(MotionTimelineGeometry.normalize(frozenValue, range), top, height)
			drawLine(color.copy(alpha = 1f), Offset(0f, y), Offset(size.width, y), 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 3f)))
		}
		if (ghost != null) {
			// The neighbouring cycles, where the loop wraps; a spin's cycles each start the turns on its last ended.
			val spun = if (curve.parameterId in repeating && range != null && range.endInclusive > range.start) {
				val span = range.endInclusive - range.start
				kotlin.math.round((MotionClips.sample(curve, ghost) - MotionClips.sample(curve, 0f)) / span) * span
			} else 0f
			val ghostPath = Path()
			var x = 0f
			var pen = false
			while (x <= size.width + step) {
				val t = vp.time(x)
				if (t < 0f || t > ghost) {
					val wrapped = ((t % ghost) + ghost) % ghost
					val cycle = kotlin.math.floor(t / ghost)
					val y = values.y(MotionTimelineGeometry.normalize(MotionClips.sample(curve, wrapped) + cycle * spun, range), top, height)
					if (pen) ghostPath.lineTo(x, y) else ghostPath.moveTo(x, y)
					pen = true
				} else pen = false
				x += step
			}
			drawPath(ghostPath, color.copy(alpha = color.alpha * 0.3f), style = Stroke(1.2.dp.toPx()))
		}
		val path = Path()
		val from = vp.time(0f).coerceAtLeast(0f)
		val to = vp.time(size.width).coerceAtMost(clip.duration)
		var x = vp.x(from)
		var first = true
		while (x <= vp.x(to) + step) {
			val t = vp.time(x).coerceIn(from, to)
			val y = values.y(MotionTimelineGeometry.normalize(MotionClips.sample(curve, t), range), top, height)
			if (first) path.moveTo(x, y) else path.lineTo(x, y)
			first = false
			x += step
		}
		val hasSelection = selection.any { it.parameterId == curve.parameterId }
		drawPath(path, color, style = Stroke(if (hasSelection) 2.dp.toPx() else 1.4.dp.toPx()))
		for (key in curve.keys) {
			val selected = selection.any { it.parameterId == curve.parameterId && it.matches(key) }
			val at = Offset(vp.x(key.time), values.y(MotionTimelineGeometry.normalize(key.value, range), top, height))
			drawKey(key.interpolation, at, 4.5.dp.toPx(), if (selected) selectedColor else color, if (selected) color else null)
		}
	}
}

/** Selection helpers the view needs beyond the view model's edits. */
private object MotionKeyEditsView {
	/** (parameter, value) of the selected keys, or of every key of [visible] when none is selected. */
	fun selectedOrAll(clip: MotionClip, selection: Set<MotionKeyRef>, visible: List<MotionCurve>): List<Pair<String, Float>> {
		val picked = clip.curves.flatMap { curve ->
			curve.keys.filter { key -> selection.any { it.parameterId == curve.parameterId && it.matches(key) } }.map { curve.parameterId to it.value }
		}
		if (picked.isNotEmpty()) return picked
		return visible.flatMap { curve ->
			val sampled = (0..40).map { i -> curve.parameterId to MotionClips.sample(curve, clip.duration * i / 40f) }
			curve.keys.map { curve.parameterId to it.value } + sampled
		}
	}
}

/** The selected keys' time, value and interpolation, or a hint when nothing is selected. */
@Composable
private fun KeyInspector(
	viewModel: PSD2LiveViewModel,
	clip: MotionClip,
	parameters: List<org.umamo.runtime.model.Parameter>,
	keymap: Keymap,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val editor = viewModel.motionEditor
	val keys = clip.curves.flatMap { curve ->
		curve.keys.filter { key -> editor.selection.any { it.parameterId == curve.parameterId && it.matches(key) } }.map { curve.parameterId to it }
	}
	Row(
		Modifier.fillMaxWidth().height(28.dp).background(colors.panelElevated)
			.border(BorderStroke(0.5.dp, colors.divider))
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		if (keys.isEmpty()) {
			Text(expandShortcutMarkup(tr("animation.editor.hint"), keymap), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp), maxLines = 1)
			return@Row
		}
		val interpolations = MotionInterpolation.entries
		val common = keys.map { it.second.interpolation }.distinct().singleOrNull()
		if (keys.size == 1) {
			val (parameterId, key) = keys.single()
			val parameter = parameters.firstOrNull { it.id.raw == parameterId }
			Text(
				parameter?.name?.ifBlank { null } ?: parameterId,
				color = colors.textPrimary,
				style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
				maxLines = 1,
			)
			ToolbarLabel(tr("animation.editor.time"))
			CompactNumberSpinner(
				value = key.time.toDouble(),
				onValueChange = { value -> viewModel.updateSelectedMotionKeys { _, k -> k.copy(time = value.toFloat()) } },
				min = 0.0,
				max = clip.duration.toDouble(),
				step = 1.0 / clip.fps,
				decimals = 3,
				unit = "s",
				height = 22.dp,
				modifier = Modifier.width(80.dp),
				onEditStart = { viewModel.beginEditorField(MOTION_KEY_FIELD) },
				onEditEnd = { viewModel.endEditorField(MOTION_KEY_FIELD) },
			)
			ToolbarLabel(tr("animation.editor.value"))
			val span = parameter?.let { it.max - it.min } ?: 1f
			CompactNumberSpinner(
				value = key.value.toDouble(),
				onValueChange = { value -> viewModel.updateSelectedMotionKeys { _, k -> k.copy(value = value.toFloat()) } },
				// A repeating parameter's keys may run turns past its range.
				min = (parameter?.let { MotionLoops.keyLimits(it).start } ?: -100000f).toDouble(),
				max = (parameter?.let { MotionLoops.keyLimits(it).endInclusive } ?: 100000f).toDouble(),
				step = if (span > 5f) 1.0 else 0.05,
				decimals = if (span > 5f) 1 else 2,
				height = 22.dp,
				modifier = Modifier.width(80.dp),
				onEditStart = { viewModel.beginEditorField(MOTION_KEY_FIELD) },
				onEditEnd = { viewModel.endEditorField(MOTION_KEY_FIELD) },
			)
		} else {
			Text(tr("animation.editor.selectedKeys", keys.size), color = colors.textPrimary, style = typography.caption.copy(fontSize = 10.5.sp))
		}
		ToolbarLabel(tr("animation.editor.interpolation"))
		CompactDropdown(
			items = listOf<MotionInterpolation?>(null) + interpolations,
			selectedItem = common,
			onItemSelected = { chosen -> if (chosen != null) viewModel.updateSelectedMotionKeys { _, k -> k.copy(interpolation = chosen) } },
			itemLabel = { it?.let { value -> tr("animation.interp.${value.name.lowercase()}") } ?: tr("animation.editor.mixed") },
			itemEnabled = { it != null },
			modifier = Modifier.width(110.dp),
			height = 22.dp,
		)
		if (keys.any { it.second.interpolation == MotionInterpolation.BEZIER }) {
			CompactButton(
				text = tr("animation.editor.resetHandles"),
				onClick = { viewModel.updateSelectedMotionKeys { _, k -> k.copy(outHandle = MotionHandle(), inHandle = MotionHandle()) } },
				height = 22.dp,
			)
		}
		CompactButton(text = tr("animation.editor.deleteKeys"), onClick = { viewModel.deleteSelectedMotionKeys() }, danger = true, height = 22.dp)
		Spacer(Modifier.width(4.dp))
	}
}
