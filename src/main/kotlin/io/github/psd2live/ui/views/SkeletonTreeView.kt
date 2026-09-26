package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.SkeletonPalette
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import kotlin.math.roundToInt

// Same geometry as the hierarchy tree, so the two tabs read as one tree control.
private const val ROW_HEIGHT_DP = 20
private const val INDENT_STEP_DP = 14
private const val BASE_PADDING_DP = 4
/** Horizontal center of the leading icon: 1.dp spacer + half of [ICON_SIZE_DP]. */
private const val DOT_OFFSET_DP = 7
private const val ICON_SIZE_DP = 12
private const val CHEVRON_WIDTH_DP = 10

/**
 * The skeleton tab beside the hierarchy: the bone tree with every bone's bound art meshes under it, in
 * the bone's color - the same color the canvas tints those meshes while the skeleton is being edited.
 *
 * The skeleton is a canvas target like a layer: clicking a bone here (or on the canvas in Object mode)
 * selects it, Deform mode then poses it and Edit mode reshapes it. While editing this tab is the editor:
 * pick a bone to drag its joints on the canvas or click meshes to bind, add and remove bones, tune the
 * selected joint. Leaving Edit mode keeps the edit; Cancel throws it away.
 */
@Composable
internal fun SkeletonTreeView(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	// The skeleton is drawn and edited on an edit canvas; a focused preview canvas hands over to one.
	val editCanvas = state.activeCanvas.takeIf { it.mode == CanvasMode.EDIT }
		?: state.activeWorkspace.canvases.firstOrNull { it.mode == CanvasMode.EDIT }
		?: state.activeCanvas
	val editor = viewModel.canvasEditorFor(editCanvas.id)
	val draft = editor.skeletonDraft
	// The authored skeleton, switched on or not: turning it off keeps the bones, so they stay listed.
	val committed = state.rigEdits.skeleton?.takeIf { it.bones.isNotEmpty() }
		?: state.previewModel?.config?.rigEdits?.skeleton?.takeIf { it.bones.isNotEmpty() }
	val shown = draft ?: committed
	val enabled = shown?.enabled == true
	val posing = editor.skeletonSelected && editor.hierarchyMode == EditHierarchyMode.DEFORM
	val rig = state.previewModel?.rig
	// Meshes are listed by their layer's name - what the layers panel and a split named them - rather than
	// the drawable id a split piece is given internally.
	val drawableNames = state.previewModel?.let { preview ->
		val layerNames = preview.analysis.layers.associate { it.source.id.raw to it.source.name }
		preview.rig.puppet.drawables.associate { drawable ->
			drawable.id.raw to (layerNames[preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: drawable.name)
		}
	}.orEmpty()
	// Bones start expanded; the map only records the ones folded away.
	val collapsed = remember { mutableStateMapOf<String, Boolean>() }

	if (state.previewModel == null) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(tr("canvas.hierarchy.empty"), style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textMuted, modifier = Modifier.padding(12.dp))
		}
		return
	}

	Column(Modifier.fillMaxSize()) {
		Toolbar {
			if (draft != null) {
				CompactButton(text = tr("skeleton.panel.add"), onClick = { editor.addBone() },
					enabled = editor.selectedBoneId != null, height = 20.dp)
				CompactIconButton(
					onClick = { editor.removeSelectedBone() },
					enabled = draft.bone(editor.selectedBoneId ?: "")?.role?.let { !it.anchor && !it.body } == true,
					size = 20.dp,
					tooltip = tr("skeleton.panel.delete"),
				) { IconTrash(Modifier.size(11.dp)) }
				ToolbarSeparator()
				for (role in listOf(BoneRole.TAIL, BoneRole.WING)) {
					val present = draft.bones.any { it.role == role }
					CompactToggleChip(
						text = tr(if (role == BoneRole.TAIL) "skeleton.chain.tail" else "skeleton.chain.wing"),
						selected = present,
						onToggle = { editor.setOptionalSkeletonChain(role, !present) },
						height = 20.dp,
					)
				}
			} else {
				if (committed == null) {
					CompactButton(text = tr("skeleton.tree.create"), onClick = { editor.beginSkeletonEdit() }, isPrimary = true, height = 20.dp)
				} else {
					CompactToggleChip(
						text = tr("skeleton.tree.pose"),
						selected = posing,
						onToggle = { if (posing) editor.setHierarchyMode(EditHierarchyMode.SELECT) else editor.beginSkeletonPose() },
						enabled = enabled,
						height = 20.dp,
					)
					CompactButton(text = tr("skeleton.tree.edit"), onClick = { editor.beginSkeletonEdit() }, height = 20.dp)
				}
				if (committed != null && enabled) {
					CompactIconButton(onClick = { editor.resetSkeletonPose() }, size = 20.dp, tooltip = tr("animation.resetPose")) {
						IconReset(Modifier.size(11.dp), tint = colors.textMuted)
					}
					CompactToggleChip(
						text = tr("skeleton.tree.weights"),
						selected = editor.showSkeletonWeights,
						onToggle = { editor.showSkeletonWeights = !editor.showSkeletonWeights },
						height = 20.dp,
					)
				}
			}
			Spacer(Modifier.weight(1f))
			if (shown != null) {
				CompactIconButton(onClick = { collapsed.clear() }, size = 20.dp, tooltip = tr("canvas.hierarchy.expandAll")) {
					IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
				CompactIconButton(
					onClick = { shown.bones.forEach { collapsed[it.id] = true } },
					size = 20.dp,
					tooltip = tr("canvas.hierarchy.collapseAll"),
				) {
					IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
			}
		}

		if (draft != null) {
			Text(
				buildAnnotatedString {
					withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold)) { append(tr("skeleton.tree.editing")) }
					append(" · ")
					append(tr("skeleton.panel.clickMesh"))
				},
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 10.sp),
				modifier = Modifier.fillMaxWidth().background(colors.accent.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
			)
		}

		if (shown != null && !enabled) {
			Row(
				Modifier.fillMaxWidth().background(colors.textMuted.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(tr("skeleton.tree.disabled"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.sp),
					modifier = Modifier.weight(1f))
				CompactButton(text = tr("skeleton.panel.enable"), onClick = { editor.setSkeletonEnabled(true) }, height = 18.dp)
			}
		}

		if (shown == null) {
			Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(tr("skeleton.tree.emptyHint"), color = colors.textMuted, style = typography.caption.copy(fontSize = 10.5.sp),
					textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
			}
			return@Column
		}

		val selectedLayers = state.selectedLayerIds + listOfNotNull(state.selectedLayerId)
		Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
			for (bone in shown.topological()) {
				if (bone.parentId != null && lineage(shown, bone).any { collapsed[it.id] == true }) continue
				val expanded = collapsed[bone.id] != true
				BoneRow(
					spec = shown,
					bone = bone,
					expanded = expanded,
					selected = editor.skeletonSelected && bone.id == editor.selectedBoneId,
					editable = true,
					onToggle = { collapsed[bone.id] = expanded },
					onSelect = { if (draft != null) editor.selectBone(bone.id) else editor.selectSkeleton(bone.id) },
				)
				// Anchor bones are skinned by the body rig, not by bones; their meshes stay out of the tree.
				if (!expanded || bone.role.anchor) continue
				val hasChildBones = shown.children(bone.id).isNotEmpty()
				bone.drawableIds.forEachIndexed { index, id ->
					val layerId = rig?.layerIdByDrawableId?.get(id) ?: id
					MeshRow(
						spec = shown,
						bone = bone,
						name = drawableNames[id] ?: id,
						isLast = index == bone.drawableIds.lastIndex && !hasChildBones,
						selected = layerId in selectedLayers,
						editable = draft != null,
						// While editing, a mesh row points at its bone: picking the layer would end the edit.
						onPick = { if (draft != null) editor.selectBone(bone.id) else viewModel.selectLayer(layerId) },
						onUnbind = { editor.unbindSkeletonDrawable(id) },
					)
				}
			}
		}

		if (draft != null) {
			val selected = draft.bone(editor.selectedBoneId ?: "")
			if (selected != null && !selected.role.anchor) BoneSettings(editor, draft, selected)
			Row(
				Modifier.fillMaxWidth().height(30.dp).background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				if (enabled) {
					CompactButton(text = tr("skeleton.panel.disable"), onClick = { editor.setSkeletonEnabled(false) }, height = 22.dp)
				}
				Spacer(Modifier.weight(1f))
				CompactButton(text = tr("skeleton.panel.cancel"), onClick = { editor.cancelSkeletonEdit() }, height = 22.dp)
				CompactButton(text = tr("skeleton.panel.done"), onClick = { editor.finishSkeletonEdit() }, isPrimary = true, height = 22.dp)
			}
		} else {
			val meshes = shown.bones.filterNot { it.role.anchor }.flatMap { it.drawableIds }.distinct().size
			Text(
				tr("skeleton.tree.summary", shown.bones.count { !it.role.anchor }, meshes),
				color = colors.textMuted,
				style = typography.caption.copy(fontSize = 10.sp),
				maxLines = 1,
				modifier = Modifier.fillMaxWidth().background(colors.panelElevated)
					.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 8.dp, vertical = 3.dp),
			)
		}
	}
}

/** The strip above the tree, styled like the hierarchy's search toolbar. */
@Composable
private fun Toolbar(content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	Row(
		Modifier.fillMaxWidth().height(26.dp).background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 6.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) { content() }
}

@Composable
private fun ToolbarSeparator() {
	Box(Modifier.width(1.dp).height(14.dp).background(LocalToolColors.current.divider))
}

/** Every ancestor of [bone], nearest first. */
private fun lineage(spec: SkeletonSpec, bone: SkeletonBone): List<SkeletonBone> =
	generateSequence(bone.parentId?.let(spec::bone)) { it.parentId?.let(spec::bone) }.toList()

private fun hasNextSibling(spec: SkeletonSpec, bone: SkeletonBone): Boolean =
	spec.children(bone.parentId).lastOrNull()?.id != bone.id

/**
 * Tree guides in the hierarchy's style: a trunk down each open ancestor level, an elbow into this row,
 * and a stub down from this row's icon when its children follow.
 */
private fun Modifier.treeGuides(depth: Int, openLevels: List<Boolean>, isLast: Boolean, hasChildrenBelow: Boolean, color: Color) =
	drawBehind {
		val midY = size.height * 0.5f
		val stroke = 1.2f
		fun levelX(level: Int) = (BASE_PADDING_DP + level * INDENT_STEP_DP + DOT_OFFSET_DP).dp.toPx()
		for (level in 0 until depth - 1) {
			if (openLevels.getOrElse(level) { false }) {
				drawLine(color, Offset(levelX(level), 0f), Offset(levelX(level), size.height), stroke)
			}
		}
		if (depth > 0) {
			val x = levelX(depth - 1)
			drawLine(color, Offset(x, 0f), Offset(x, if (isLast) midY else size.height), stroke)
			drawLine(color, Offset(x, midY), Offset((BASE_PADDING_DP + depth * INDENT_STEP_DP + 1).dp.toPx(), midY), stroke)
		}
		if (hasChildrenBelow) {
			val x = levelX(depth)
			drawLine(color, Offset(x, midY + (ICON_SIZE_DP / 2f).dp.toPx()), Offset(x, size.height), stroke)
		}
	}

/** One bone of the tree: color swatch, fold chevron, name, and the parameter it drives. */
@Composable
private fun BoneRow(
	spec: SkeletonSpec,
	bone: SkeletonBone,
	expanded: Boolean,
	selected: Boolean,
	editable: Boolean,
	onToggle: () -> Unit,
	onSelect: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val ancestors = lineage(spec, bone)
	val depth = ancestors.size
	// Level i is open when the ancestor at that depth still has siblings below it.
	val openLevels = ancestors.asReversed().drop(1).map { hasNextSibling(spec, it) }
	val meshCount = if (bone.role.anchor) 0 else bone.drawableIds.size
	val hasChildren = meshCount > 0 || spec.children(bone.id).isNotEmpty()
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val clickable = editable && !bone.role.anchor
	val textColor = when {
		selected -> colors.selectionText
		bone.role.anchor -> colors.textMuted
		else -> colors.textPrimary
	}

	Row(
		Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)
			.background(
				when {
					selected -> colors.selection
					hovered && clickable -> colors.controlHover.copy(alpha = 0.3f)
					else -> Color.Transparent
				}
			)
			.treeGuides(depth, openLevels, !hasNextSibling(spec, bone), expanded && hasChildren, guideColor(colors.textMuted))
			.hoverable(interaction)
			.clickable(enabled = clickable, interactionSource = interaction, indication = null, onClick = onSelect)
			.padding(start = (BASE_PADDING_DP + depth * INDENT_STEP_DP).dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.width(1.dp))
		Box(Modifier.size(ICON_SIZE_DP.dp), contentAlignment = Alignment.Center) {
			Box(Modifier.size(8.dp).background(SkeletonPalette.color(spec, bone.id), CircleShape))
		}
		Spacer(Modifier.width(2.dp))
		Box(
			Modifier.size(CHEVRON_WIDTH_DP.dp)
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.clickable(enabled = hasChildren, onClick = onToggle),
			contentAlignment = Alignment.Center,
		) {
			if (hasChildren) {
				IconChevron(expanded = expanded, modifier = Modifier.size(7.dp), tint = if (selected) colors.selectionText else colors.accent)
			}
		}
		Spacer(Modifier.width(2.dp))
		Text(
			bone.name,
			modifier = Modifier.weight(1f),
			color = textColor,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (!expanded && meshCount > 0) {
			Text("×$meshCount", color = if (selected) colors.selectionText else colors.accent,
				style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
			Spacer(Modifier.width(4.dp))
		}
		if (!bone.role.anchor) {
			Text(bone.parameterId, color = if (selected) colors.selectionText.copy(alpha = 0.7f) else colors.textMuted,
				style = typography.monoSmall.copy(fontSize = 9.sp), maxLines = 1)
		}
	}
}

/** A mesh bound to [bone], one level under it, in the bone's color; picks its layer, or unbinds while editing. */
@Composable
private fun MeshRow(
	spec: SkeletonSpec,
	bone: SkeletonBone,
	name: String,
	isLast: Boolean,
	selected: Boolean,
	editable: Boolean,
	onPick: () -> Unit,
	onUnbind: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val ancestors = lineage(spec, bone)
	val depth = ancestors.size + 1
	val openLevels = ancestors.asReversed().drop(1).map { hasNextSibling(spec, it) } + hasNextSibling(spec, bone)
	val color = SkeletonPalette.color(spec, bone.id)
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()

	Row(
		Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)
			.background(
				when {
					selected -> colors.selection
					hovered -> colors.controlHover.copy(alpha = 0.3f)
					else -> Color.Transparent
				}
			)
			.treeGuides(depth, openLevels, isLast, false, guideColor(colors.textMuted))
			.hoverable(interaction)
			.clickable(interactionSource = interaction, indication = null, onClick = onPick)
			.padding(start = (BASE_PADDING_DP + depth * INDENT_STEP_DP).dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.width(1.dp))
		IconMeshWireframe(tint = color, modifier = Modifier.size(ICON_SIZE_DP.dp))
		Spacer(Modifier.width((CHEVRON_WIDTH_DP + 4).dp))
		Text(
			name,
			modifier = Modifier.weight(1f),
			color = if (selected) colors.selectionText else color,
			style = typography.body.copy(fontSize = 11.sp),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (editable && hovered) {
			Box(
				Modifier.size(14.dp)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable(onClick = onUnbind),
				contentAlignment = Alignment.Center,
			) { IconClose(Modifier.size(7.dp), tint = if (selected) colors.selectionText else colors.textMuted) }
		}
	}
}

private fun guideColor(muted: Color) = muted.copy(alpha = 0.4f)

/**
 * The selected bone's joint: how wide the blend band around its head is, and how far it may turn each
 * way. The band is automatic until moved; the limits are the range of the bone's parameter.
 */
@Composable
private fun BoneSettings(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	// A limb skins apart from the body bone it hangs from, so only a joint inside the limb has a blend band.
	val parent = lineage(spec, bone).firstOrNull { !it.role.anchor }?.takeUnless { it.role.body }
	Row(
		Modifier.fillMaxWidth().height(24.dp).background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider)).padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(8.dp).background(SkeletonPalette.color(spec, bone.id), CircleShape))
		Spacer(Modifier.width(6.dp))
		Text(bone.name, modifier = Modifier.weight(1f), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
			style = typography.header.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
		Text(bone.parameterId, color = colors.textMuted, style = typography.monoSmall.copy(fontSize = 9.sp), maxLines = 1)
	}
	Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		if (parent != null) {
			val limit = (minOf(bone.length, parent.length) * 0.45f).coerceAtLeast(1f)
			val automatic = bone.blendWidth == null
			val shown = bone.blendWidth ?: SkeletonWeights.blendHalfWidth(bone, parent.length).toFloat()
			SettingRow(tr("skeleton.panel.blend"), "${shown.roundToInt()}px") {
				CompactSlider(value = shown.coerceIn(0f, limit), onValueChange = { editor.setBoneBlendWidth(it) },
					valueRange = 0f..limit, modifier = Modifier.weight(1f))
				Spacer(Modifier.width(4.dp))
				CompactToggleChip(text = tr("skeleton.panel.auto"), selected = automatic, onToggle = { editor.setBoneBlendWidth(null) },
					showCheckWhenSelected = false, height = 18.dp)
			}
		}
		SettingRow(tr("skeleton.panel.min"), "${bone.minAngle.roundToInt()}°") {
			CompactSlider(value = bone.minAngle, onValueChange = { editor.setBoneLimits(it, bone.maxAngle) },
				valueRange = -180f..0f, modifier = Modifier.weight(1f))
		}
		SettingRow(tr("skeleton.panel.max"), "${bone.maxAngle.roundToInt()}°") {
			CompactSlider(value = bone.maxAngle, onValueChange = { editor.setBoneLimits(bone.minAngle, it) },
				valueRange = 0f..180f, modifier = Modifier.weight(1f))
		}
	}
}

/** A property row: muted label, the control, and the value right-aligned in a fixed column. */
@Composable
private fun SettingRow(label: String, value: String, control: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(label, color = colors.textMuted, style = typography.caption.copy(fontSize = 9.5.sp), maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.width(56.dp))
		control()
		Text(value, color = colors.textPrimary, style = typography.monoSmall.copy(fontSize = 10.sp), textAlign = TextAlign.End,
			maxLines = 1, modifier = Modifier.width(42.dp))
	}
}
