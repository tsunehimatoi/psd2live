package io.github.psd2live.ui.views

import io.github.psd2live.ui.components.AppDropdownMenu
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.layeredSheets
import io.github.psd2live.ui.components.meshPatch
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor

/**
 * "+" menu of the workspace strip: presets on the left, each with its icon and a small layout
 * sketch; the hovered one's arrangement is drawn larger on the right with its purpose.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NewWorkspaceMenu(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	onCreate: (WorkspacePreset) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var hovered by remember(expanded) { mutableStateOf(WorkspacePreset.EDIT) }
	AppDropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
	) {
		AppMenuHeader(tr("workspace.new"))
		Row(Modifier.padding(bottom = 4.dp)) {
			Column(Modifier.width(196.dp)) {
				WorkspacePreset.entries.forEach { preset ->
					if (preset == WorkspacePreset.BLANK) {
						Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(colors.divider))
					}
					PresetRow(
						preset = preset,
						highlighted = preset == hovered,
						onHover = { hovered = preset },
						onClick = {
							onDismissRequest()
							onCreate(preset)
						},
					)
				}
			}
			Box(Modifier.width(1.dp).height(PREVIEW_PANE_HEIGHT).background(colors.divider))
			Column(
				modifier = Modifier.width(236.dp).height(PREVIEW_PANE_HEIGHT).padding(horizontal = 10.dp, vertical = 4.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				WorkspaceLayoutThumbnail(
					preset = hovered,
					labels = true,
					modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
				)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					WorkspacePresetIcon(hovered, colors.textPrimary, Modifier.size(13.dp))
					Text(
						text = hovered.title(),
						style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)
				}
				Text(
					text = hovered.description(),
					style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
					color = colors.textMuted,
				)
			}
		}
	}
}

private val PREVIEW_PANE_HEIGHT = 236.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PresetRow(
	preset: WorkspacePreset,
	highlighted: Boolean,
	onHover: () -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val content = if (highlighted) colors.selectionText else colors.textPrimary
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(30.dp)
			.background(if (highlighted) colors.selection else Color.Transparent)
			.handCursor()
			.onPointerEvent(PointerEventType.Enter) { onHover() }
			.clickable(onClick = onClick)
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
			WorkspacePresetIcon(preset, content, Modifier.size(14.dp))
		}
		Text(
			text = preset.title(),
			style = typography.body.copy(fontSize = 11.5.sp),
			color = content,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		WorkspaceLayoutThumbnail(preset, labels = false, modifier = Modifier.width(34.dp).height(21.dp))
	}
}

/**
 * The dock a new workspace of [preset] opens with, drawn from the same tree the dock builds.
 * Canvases are tinted (edit in the accent, preview in green); [labels] names each panel group.
 */
@Composable
internal fun WorkspaceLayoutThumbnail(
	preset: WorkspacePreset,
	labels: Boolean,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val (layout, workspace) = remember(preset) { presetVisibleLayout(preset) }
	val modes = remember(workspace) { workspace.canvases.associate { it.id to it.mode } }
	val gap = if (labels) 3.dp else 1.dp
	val shape = RoundedCornerShape(if (labels) 4.dp else 2.dp)
	Box(
		modifier
			.clip(shape)
			.background(colors.windowBackground)
			.border(1.dp, colors.border, shape)
			.padding(gap),
	) {
		if (layout == null) {
			EmptyLayoutSketch(labels, Modifier.fillMaxSize())
		} else {
			LayoutSketchNode(layout, modes, labels, gap, Modifier.fillMaxSize())
		}
	}
}

@Composable
private fun LayoutSketchNode(
	node: DockNode,
	modes: Map<String, CanvasMode>,
	labels: Boolean,
	gap: Dp,
	modifier: Modifier,
) {
	val first = node.first
	val second = node.second
	if (first != null && second != null) {
		if (node.horizontal) Row(modifier) {
			LayoutSketchNode(first, modes, labels, gap, Modifier.weight(node.ratio).fillMaxHeight())
			Spacer(Modifier.width(gap))
			LayoutSketchNode(second, modes, labels, gap, Modifier.weight(1f - node.ratio).fillMaxHeight())
		} else Column(modifier) {
			LayoutSketchNode(first, modes, labels, gap, Modifier.weight(node.ratio).fillMaxWidth())
			Spacer(Modifier.height(gap))
			LayoutSketchNode(second, modes, labels, gap, Modifier.weight(1f - node.ratio).fillMaxWidth())
		}
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val lead = node.selected.takeIf { it in node.modules } ?: node.modules.firstOrNull().orEmpty()
	val mode = modes[lead]
	val tint = when (mode) {
		CanvasMode.EDIT -> colors.accent
		CanvasMode.PREVIEW -> colors.success
		null -> colors.textMuted
	}
	val fill = if (mode != null) tint.copy(alpha = if (labels) 0.22f else 0.45f) else colors.controlBackground
	Box(modifier.clip(RoundedCornerShape(if (labels) 3.dp else 1.dp)).background(fill)) {
		if (!labels) return@Box
		Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
			// The tint and glyph already mark a canvas; its label only needs the mode.
			val title = if (mode != null) tr(if (mode == CanvasMode.EDIT) "tab.edit" else "tab.preview") else moduleTitle(lead)
			val extra = node.modules.size - 1
			Text(
				text = if (extra > 0) "$title +$extra" else title,
				style = typography.caption.copy(fontSize = 8.5.sp, lineHeight = 10.sp),
				color = if (mode != null) colors.textPrimary else colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (mode != null) {
				Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					if (mode == CanvasMode.EDIT) IconPencil(tint, Modifier.size(14.dp))
					else IconEye(visible = true, modifier = Modifier.size(16.dp), tint = tint)
				}
			}
		}
	}
}

@Composable
private fun EmptyLayoutSketch(labels: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(modifier, contentAlignment = Alignment.Center) {
		Canvas(Modifier.fillMaxSize()) {
			val inset = if (labels) 4.dp.toPx() else 1.dp.toPx()
			drawRoundRect(
				color = colors.textMuted.copy(alpha = 0.6f),
				topLeft = Offset(inset, inset),
				size = Size(size.width - inset * 2, size.height - inset * 2),
				cornerRadius = androidx.compose.ui.geometry.CornerRadius(if (labels) 3.dp.toPx() else 1f),
				style = Stroke(
					width = 1f,
					pathEffect = PathEffect.dashPathEffect(if (labels) floatArrayOf(5f, 4f) else floatArrayOf(2f, 2f)),
				),
			)
		}
		if (labels) {
			Text(
				text = tr("workspace.preset.blank.hint"),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
			)
		}
	}
}

/** One glyph per preset, shared by the "+" menu and the workspace chips. */
@Composable
internal fun WorkspacePresetIcon(preset: WorkspacePreset, tint: Color, modifier: Modifier = Modifier.size(14.dp)) {
	when (preset) {
		WorkspacePreset.EDIT -> IconPencil(tint, modifier)
		WorkspacePreset.PREVIEW -> IconEye(visible = true, modifier = modifier, tint = tint)
		WorkspacePreset.PHYSICS -> IconPhysics(active = false, modifier = modifier, tint = tint)
		else -> GridIcon(modifier, tint) {
			when (preset) {
				// The mesh, as the hierarchy and the mode bar draw it.
				WorkspacePreset.MESH -> meshPatch()
				// Two parameter sliders.
				WorkspacePreset.RIG -> listOf(5.8f to 5.8f, 12.4f to 11.6f).forEach { (y, knob) ->
					behind(circlePath(knob, y, 2.4f)) { line(2.4f, y, 15.6f, y, ICON_FINE) }
					dot(knob, y, 2.2f, soft)
					ring(knob, y, 2.2f)
				}
				// A timeline with keyframes and the playhead.
				WorkspacePreset.ANIMATION -> {
					line(1.8f, 11f, 16.2f, 11f, ICON_FINE)
					listOf(3.8f, 10f, 14.4f).forEach { x ->
						fill(path { m(x, 9.1f); l(x + 1.9f, 11f); l(x, 12.9f); l(x - 1.9f, 11f); z() })
					}
					line(7f, 4.4f, 7f, 15.4f)
					fill(path { m(5.2f, 2.6f); l(8.8f, 2.6f); l(7f, 4.8f); z() })
				}
				// An atlas page: a frame packed with tiles of different sizes.
				WorkspacePreset.TEXTURE -> {
					box(2f, 2f, 14f, 14f, 1.2f)
					val packed = color.copy(alpha = color.alpha * 0.55f)
					fillBox(4f, 4f, 5f, 5f, 0.8f)
					fillBox(10.4f, 4f, 3.6f, 3.6f, 0.8f, packed)
					fillBox(4f, 10.4f, 3.2f, 3.6f, 0.8f, packed)
					fillBox(8.6f, 9.4f, 5.4f, 4.6f, 0.8f, soft)
				}
				// Stacked layer sheets: materials and layered import.
				WorkspacePreset.LAYOUT -> layeredSheets()
				// A trunk with one branch forking off, like the history tree.
				WorkspacePreset.HISTORY -> {
					line(5.4f, 15f, 5.4f, 3f)
					outline(path { m(5.4f, 9f); q(13.2f, 9f, 13.2f, 4.4f) })
					listOf(5.4f to 15f, 5.4f to 9f, 5.4f to 3f, 13.2f to 4.4f).forEach { (x, y) -> dot(x, y, 1.8f) }
				}
				// An empty dashed frame with a plus.
				else -> {
					box(2.2f, 2.2f, 13.6f, 13.6f, 2f, ICON_FINE, dash = floatArrayOf(1.6f, 2.4f))
					line(9f, 5.8f, 9f, 12.2f)
					line(5.8f, 9f, 12.2f, 9f)
				}
			}
		}
	}
}

/** The edit canvas: the paint pencil. */
@Composable
private fun IconPencil(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) { pencil() }
