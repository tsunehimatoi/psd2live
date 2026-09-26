package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.SkeletonPalette
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.roundToInt

/**
 * The skeleton tab beside the hierarchy: the bone tree with every bone's bound art meshes under it, in
 * the bone's color - the same color the canvas tints those meshes while the skeleton is being edited.
 *
 * Outside editing it shows the skeleton the rig was built with and the posing controls. While editing
 * it is the editor: pick a bone to drag its joints on the canvas or click meshes to bind, add and
 * remove bones, tune the selected joint, then confirm or cancel.
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
	val baked = state.previewModel?.config?.rigEdits?.skeleton?.takeIf { it.enabled && it.bones.isNotEmpty() }
	val shown = draft ?: baked
	// Meshes are listed by their layer's name - what the layers panel and a split named them - rather than
	// the drawable id a split piece is given internally.
	val drawableNames = state.previewModel?.let { preview ->
		val layerNames = preview.analysis.layers.associate { it.source.id.raw to it.source.name }
		preview.rig.puppet.drawables.associate { drawable ->
			drawable.id.raw to (layerNames[preview.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: drawable.name)
		}
	}.orEmpty()
	val small = typography.body.copy(fontSize = 10.sp)

	Column(Modifier.fillMaxSize()) {
		CompactSectionHeader(title = if (draft != null) tr("skeleton.tree.editing") else tr("dock.skeleton"))
		Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			when {
				state.previewModel == null -> Text(tr("canvas.hierarchy.empty"), color = colors.textMuted, style = small)
				draft == null -> {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						CompactButton(
							text = tr(if (baked == null) "skeleton.tree.create" else "skeleton.tree.edit"),
							onClick = { editor.beginSkeletonEdit() },
							isPrimary = baked == null,
						)
						if (baked != null) {
							CompactButton(text = tr("animation.resetPose"), onClick = { editor.resetSkeletonPose() })
						}
					}
					if (baked != null) {
						Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							CompactCheckbox(checked = editor.showSkeletonWeights, onCheckedChange = { editor.showSkeletonWeights = it })
							Text(tr("skeleton.pose.weights"), color = colors.textPrimary, style = small)
						}
					} else {
						Text(tr("skeleton.tree.emptyHint"), color = colors.textMuted, style = small)
					}
				}
				else -> DraftControls(editor, draft)
			}
		}
		if (shown != null) {
			Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
			Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
				for (bone in shown.topological()) {
					BoneRow(
						spec = shown,
						bone = bone,
						selected = draft != null && bone.id == editor.selectedBoneId,
						editable = draft != null,
						drawableNames = drawableNames,
						onSelect = { if (draft != null) editor.selectBone(bone.id) },
						onUnbind = editor::unbindSkeletonDrawable,
						onPickMesh = { id -> viewModel.selectLayer(state.previewModel?.rig?.layerIdByDrawableId?.get(id) ?: id) },
					)
				}
			}
			if (draft != null) {
				val selected = draft.bone(editor.selectedBoneId ?: "")
				if (selected != null && !selected.role.anchor) {
					Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
					Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Box(Modifier.size(9.dp).background(SkeletonPalette.color(draft, selected.id), CircleShape))
							Spacer(Modifier.width(6.dp))
							Text(selected.name, color = colors.textPrimary, style = small.copy(fontWeight = FontWeight.SemiBold))
						}
						BoneSettings(editor, draft, selected)
					}
				}
			}
		}
	}
}

/** The editing controls above the tree: what a click does, the optional chains, and confirm/cancel. */
@Composable
private fun DraftControls(editor: CanvasEditor, draft: SkeletonSpec) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val small = typography.body.copy(fontSize = 10.sp)
	Text(tr("skeleton.panel.clickMesh"), color = colors.textMuted, style = small)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
		for (role in listOf(BoneRole.TAIL, BoneRole.WING)) {
			val present = draft.bones.any { it.role == role }
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				CompactCheckbox(checked = present, onCheckedChange = { editor.setOptionalSkeletonChain(role, it) })
				Text(tr(if (role == BoneRole.TAIL) "skeleton.chain.tail" else "skeleton.chain.wing"), color = colors.textPrimary, style = small)
			}
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(text = tr("skeleton.panel.add"), onClick = { editor.addBone() }, enabled = editor.selectedBoneId != null)
		CompactButton(
			text = tr("skeleton.panel.delete"),
			onClick = { editor.removeSelectedBone() },
			enabled = draft.bone(editor.selectedBoneId ?: "")?.role?.anchor == false,
		)
		if (editor.state.rigEdits.skeleton?.enabled == true) {
			CompactButton(text = tr("skeleton.panel.disable"), onClick = { editor.disableSkeleton() }, danger = true)
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(text = tr("skeleton.panel.cancel"), onClick = { editor.cancelSkeletonEdit() })
		CompactButton(text = tr("skeleton.panel.confirm"), onClick = { editor.confirmSkeletonEdit() }, isPrimary = true)
	}
}

/** One bone of the tree, indented by depth, with the meshes bound to it listed beneath in its color. */
@Composable
private fun BoneRow(
	spec: SkeletonSpec,
	bone: SkeletonBone,
	selected: Boolean,
	editable: Boolean,
	drawableNames: Map<String, String>,
	onSelect: () -> Unit,
	onUnbind: (String) -> Unit,
	onPickMesh: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val depth = generateSequence(bone.parentId) { spec.bone(it)?.parentId }.count()
	val color = SkeletonPalette.color(spec, bone.id)
	Row(
		Modifier.fillMaxWidth()
			.background(if (selected) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
			.clickable(enabled = editable, onClick = onSelect)
			.padding(start = (8 + depth * 12).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(9.dp).background(color, CircleShape))
		Spacer(Modifier.width(6.dp))
		Text(
			bone.name,
			modifier = Modifier.weight(1f),
			color = if (bone.role.anchor) colors.textMuted else colors.textPrimary,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (!bone.role.anchor) {
			Text(bone.parameterId, color = colors.textMuted, style = typography.body.copy(fontSize = 9.sp), maxLines = 1)
		}
	}
	// Anchor bones list their meshes only for reference; they are skinned by the body rig, not by bones.
	if (bone.role.anchor) return
	for (id in bone.drawableIds) {
		Row(
			Modifier.fillMaxWidth()
				.clickable { onPickMesh(id) }
				.padding(start = (8 + depth * 12 + 15).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(Modifier.size(width = 10.dp, height = 7.dp).background(color.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
			Spacer(Modifier.width(6.dp))
			Text(
				drawableNames[id] ?: id,
				modifier = Modifier.weight(1f),
				color = color,
				style = typography.body.copy(fontSize = 10.sp),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (editable) {
				Text("×", modifier = Modifier.clickable { onUnbind(id) }.padding(horizontal = 4.dp),
					color = colors.textPrimary, style = typography.body.copy(fontSize = 12.sp))
			}
		}
	}
}

/**
 * The selected bone's joint: how wide the blend band around its head is, and how far it may turn each
 * way. The band is automatic until moved; the limits are the range of the bone's parameter.
 */
@Composable
private fun BoneSettings(editor: CanvasEditor, spec: SkeletonSpec, bone: SkeletonBone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val parent = generateSequence(bone.parentId?.let(spec::bone)) { it.parentId?.let(spec::bone) }.firstOrNull { !it.role.anchor }
	val label = typography.body.copy(fontSize = 10.sp)
	if (parent != null) {
		val limit = (minOf(bone.length, parent.length) * 0.45f).coerceAtLeast(1f)
		val automatic = bone.blendWidth == null
		val shown = bone.blendWidth ?: SkeletonWeights.blendHalfWidth(bone, parent.length).toFloat()
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(tr("skeleton.panel.blend", shown.roundToInt()), color = colors.textPrimary, style = label, modifier = Modifier.width(92.dp))
			CompactSlider(value = shown.coerceIn(0f, limit), onValueChange = { editor.setBoneBlendWidth(it) },
				valueRange = 0f..limit, modifier = Modifier.weight(1f))
			Text(tr("skeleton.panel.auto"), modifier = Modifier
				.background(if (automatic) colors.accent.copy(alpha = 0.35f) else colors.border.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
				.clickable { editor.setBoneBlendWidth(null) }.padding(horizontal = 6.dp, vertical = 3.dp),
				color = colors.textPrimary, style = typography.body.copy(fontSize = 9.sp))
		}
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(tr("skeleton.panel.min", bone.minAngle.roundToInt()), color = colors.textPrimary, style = label, modifier = Modifier.width(92.dp))
		CompactSlider(value = bone.minAngle, onValueChange = { editor.setBoneLimits(it, bone.maxAngle) },
			valueRange = -180f..0f, modifier = Modifier.weight(1f))
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(tr("skeleton.panel.max", bone.maxAngle.roundToInt()), color = colors.textPrimary, style = label, modifier = Modifier.width(92.dp))
		CompactSlider(value = bone.maxAngle, onValueChange = { editor.setBoneLimits(bone.minAngle, it) },
			valueRange = 0f..180f, modifier = Modifier.weight(1f))
	}
}
