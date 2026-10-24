package io.github.psd2live.ui.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.views.physics.CardSubsection
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.PhysicsSection
import org.umamo.runtime.model.PuppetModel

/**
 * The role components [puppet] has that act on [key] (a mesh's ID, `warp:<id>` or `rotation:<id>`): those owning one of
 * its stages or a keyform axis of one, from the stage view.
 */
internal fun componentsActingOn(stages: List<io.github.psd2live.core.modifier.RigStages.Stage>): List<RigComponents.Component> =
	stages.flatMap { s -> listOfNotNull(s.owner) + s.axes.map { it.owner } }
		.filter { it.kind == io.github.psd2live.core.modifier.RigStages.Owner.Kind.COMPONENT }
		.mapNotNull { RigComponents.of(it.id) }.distinct().sortedBy { RigComponents.all.indexOf(it) }

/**
 * A role component's card: its switch, then its members and their roles (the layers' own tags), its rig values (shared,
 * or per side for those the eye and brow layers read one at a time), the settings that belong to it and the parameters
 * it makes. Every edit is a settings change, as the model presets made it.
 */
@Composable
internal fun ComponentCard(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, component: RigComponents.Component, open: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val isBusy = state.isAnalyzing || state.isGenerating || state.workspaceEditBusy || state.editorDraftBusy
	val enabled = component.id !in state.disabledComponents
	val trailing: @Composable RowScope.() -> Unit = {
		if (component.parameters.isNotEmpty()) PanelToolSwitch(tr("modifiers.enabled"), showLabel = false, checked = enabled,
			onCheckedChange = { vm.setComponentEnabled(component.id, it) }, enabled = !isBusy && !state.meshOnly, tooltip = tr("modifiers.componentTip"))
	}
	var folds by remember(component.id) { mutableStateOf(setOf("values")) }
	fun toggle(key: String) { folds = if (key in folds) folds - key else folds + key }
	PhysicsSection(tr("modifiers.component.${component.id}"), open, onToggle, trailing = trailing) {
		if (!enabled) Text(tr("modifiers.componentOff"), style = caption, color = colors.warning)
		ComponentOptions(vm, state, component, isBusy)

		val fields = RigTuning.fields.filter { it.group in component.groups }
		if (fields.isNotEmpty()) CardSubsection(tr("modifiers.componentValues"), "values" in folds, { toggle("values") }) {
			val sided = fields.filter { it.id in RigTuning.SIDED }
			val split = state.rigTuning.left.isNotEmpty() || state.rigTuning.right.isNotEmpty()
			if (sided.isNotEmpty()) CompactCheckbox(split, { vm.setRigTuningSplit(sided.map { it.id }, it) }, label = tr("modifiers.componentSplit"),
				enabled = !isBusy)
			val (advanced, common) = fields.partition { it.advanced }
			for (field in common) TuningRow(vm, state, field, split && field.id in RigTuning.SIDED, isBusy)
			if (advanced.isNotEmpty()) CardSubsection(tr("settings.rigTuning.advanced"), "advanced" in folds, { toggle("advanced") }) {
				for (field in advanced) TuningRow(vm, state, field, split && field.id in RigTuning.SIDED, isBusy)
			}
		}

		val layers = state.previewModel?.analysis?.layers.orEmpty().filter { it.semantic.tag in component.members }
		if (component.members.isNotEmpty()) CardSubsection(tr("modifiers.componentMembers"), "members" in folds, { toggle("members") }, count = layers.size) {
			if (layers.isEmpty()) Text(tr("modifiers.componentNoMembers"), style = caption, color = colors.textMuted)
			// What it recognised, by role: changing a role is changing the layer's own tag.
			for (layer in layers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(layer.source.name, style = caption, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f).handCursor().clickable { vm.selectLayer(layer.source.id.raw) })
				val roles = (component.members.toList() + SemanticTag.UNKNOWN).sortedBy { it.ordinal }
				CompactDropdown(roles, layer.semantic.tag, { tag ->
					vm.setLayerClassification(layer.source.id.raw, LayerClassificationOverride(tag, layer.semantic.side))
				}, Modifier.width(92.dp), itemLabel = { tr("semantic.${it.canonicalName}") }, height = 20.dp, enabled = !isBusy)
				CompactDropdown(Side.entries, layer.semantic.side, { side ->
					vm.setLayerClassification(layer.source.id.raw, LayerClassificationOverride(layer.semantic.tag, side))
				}, Modifier.width(56.dp), itemLabel = { tr("side.${it.name.lowercase()}") }, height = 20.dp, enabled = !isBusy)
			}
		}

		val parameters = puppet.parameters.filter { it.id.raw in component.parameters }
		if (parameters.isNotEmpty()) CardSubsection(tr("modifiers.componentParameters"), "parameters" in folds, { toggle("parameters") }, count = parameters.size) {
			for (p in parameters) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(p.name, style = caption, color = colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(p.id.raw, style = caption, color = colors.textMuted, maxLines = 1)
			}
		}
	}
}

/** One rig value: shared, or the left and right sides' own when [split]. */
@Composable
private fun TuningRow(vm: PSD2LiveViewModel, state: PSD2LiveState, field: RigTuning.Field, split: Boolean, isBusy: Boolean) {
	val colors = LocalToolColors.current
	val unit = when (field.unit) { RigTuning.Unit.DEGREES -> "°"; RigTuning.Unit.PERCENT -> "%"; RigTuning.Unit.TORSO_LENGTHS -> tr("settings.unit.x") }
	val decimals = if (field.step < 1f) 1 else 0
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		val value = field.get(state.rigTuning)
		Text(tr("settings.rigTuning.${field.id}"), style = LocalToolTypography.current.body.copy(fontSize = 10.sp),
			color = if (value != field.default || split) colors.accent else colors.textPrimary, textAlign = TextAlign.Right, maxLines = 1,
			overflow = TextOverflow.Ellipsis, modifier = Modifier.width(76.dp))
		if (!split) {
			CompactSlider(value, { vm.setRigTuning(field.id, it) }, onValueChangeStarted = vm::beginEditorGesture, onValueChangeFinished = vm::endEditorGesture,
				valueRange = field.range, enabled = !isBusy, height = 14.dp, modifier = Modifier.weight(1f))
			CompactNumberSpinner(value.toDouble(), { vm.setRigTuning(field.id, it.toFloat()) }, Modifier.width(60.dp),
				min = field.range.start.toDouble(), max = field.range.endInclusive.toDouble(), step = field.step.toDouble(), decimals = decimals, unit = unit,
				enabled = !isBusy, height = 20.dp, onEditStart = { vm.beginEditorField("setRigTuning.${field.id}") }, onEditEnd = { vm.endEditorField("setRigTuning.${field.id}") })
		} else for (side in listOf("left", "right")) {
			val own = (if (side == "left") state.rigTuning.left else state.rigTuning.right)[field.id] ?: value
			FieldLabel(tr("side.$side"), width = 14)
			CompactNumberSpinner(own.toDouble(), { vm.setRigTuningSide(side, field.id, it.toFloat()) }, Modifier.weight(1f),
				min = field.range.start.toDouble(), max = field.range.endInclusive.toDouble(), step = field.step.toDouble(), decimals = decimals, unit = unit,
				enabled = !isBusy, height = 20.dp, onEditStart = { vm.beginEditorField("setRigTuning.$side.${field.id}") }, onEditEnd = { vm.endEditorField("setRigTuning.$side.${field.id}") })
		}
	}
}

/** The settings that belong to [component] alone: its strength, its outline or see-through copies, its motions and physics. */
@Composable
private fun ComponentOptions(vm: PSD2LiveViewModel, state: PSD2LiveState, component: RigComponents.Component, isBusy: Boolean) {
	val editable = !isBusy && !state.meshOnly
	when (component) {
		RigComponents.HEAD -> {
			StrengthRow(tr("settings.headStrength"), state.headStrength, vm::setHeadStrength, "setHeadStrength", vm, isBusy)
			CompactCheckbox(state.featureDisplacementEnabled, vm::setFeatureDisplacementEnabled, label = tr("model.deformer.featureDisplacement"), enabled = editable)
		}
		RigComponents.BODY -> StrengthRow(tr("settings.bodyStrength"), state.bodyStrength, vm::setBodyStrength, "setBodyStrength", vm, isBusy)
		RigComponents.EYES -> {
			CompactCheckbox(state.motionBlink, vm::setMotionBlink, label = tr("modifiers.componentBlink"), enabled = editable)
			CompactCheckbox(state.seeThroughEyes, vm::setSeeThroughEyes, label = tr("seeThrough.eyes"), enabled = editable)
			if (state.seeThroughEyes) SeeThroughOpacityRow(state, vm, isBusy)
			CompactCheckbox(state.physicsEyeJelly, vm::setPhysicsEyeJelly, label = tr("export.physics.eyeJelly"), enabled = editable)
		}
		RigComponents.BROWS -> {
			CompactCheckbox(state.seeThroughBrows, vm::setSeeThroughBrows, label = tr("seeThrough.brows"), enabled = editable)
			if (state.seeThroughBrows) SeeThroughOpacityRow(state, vm, isBusy)
		}
		RigComponents.MOUTH -> MouthOutlineControls(state, vm, isBusy)
		RigComponents.HAIR -> {
			CompactCheckbox(state.physicsFrontHair, vm::setPhysicsFrontHair, label = tr("modifiers.componentFrontHairPhysics"), enabled = editable)
			CompactCheckbox(state.physicsBackHair, vm::setPhysicsBackHair, label = tr("modifiers.componentBackHairPhysics"), enabled = editable)
		}
		else -> Unit
	}
}

@Composable
private fun StrengthRow(label: String, value: Float, set: (Float) -> Unit, token: String, vm: PSD2LiveViewModel, isBusy: Boolean) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, style = LocalToolTypography.current.body.copy(fontSize = 10.sp), color = LocalToolColors.current.textPrimary,
			textAlign = TextAlign.Right, modifier = Modifier.width(76.dp), maxLines = 1)
		CompactSlider(value, set, onValueChangeStarted = vm::beginEditorGesture, onValueChangeFinished = vm::endEditorGesture,
			valueRange = 0f..4f, enabled = !isBusy, height = 14.dp, modifier = Modifier.weight(1f))
		CompactNumberSpinner(value.toDouble(), { set(it.toFloat()) }, Modifier.width(60.dp), min = 0.0, max = 4.0, step = 0.05, decimals = 2,
			unit = tr("settings.unit.x"), enabled = !isBusy, height = 20.dp, onEditStart = { vm.beginEditorField(token) }, onEditEnd = { vm.endEditorField(token) })
	}
}

/** How opaque the copies over the bangs are; one value for the eyes' and the brows'. */
@Composable
private fun SeeThroughOpacityRow(state: PSD2LiveState, vm: PSD2LiveViewModel, isBusy: Boolean) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(tr("seeThrough.opacity"), style = LocalToolTypography.current.body.copy(fontSize = 10.sp), color = LocalToolColors.current.textPrimary,
			textAlign = TextAlign.Right, modifier = Modifier.width(76.dp), maxLines = 1)
		CompactSlider(state.seeThroughOpacity, vm::setSeeThroughOpacity, onValueChangeStarted = vm::beginEditorGesture, onValueChangeFinished = vm::endEditorGesture,
			valueRange = 0f..1f, enabled = !isBusy && !state.meshOnly, height = 14.dp, modifier = Modifier.weight(1f))
		CompactNumberSpinner((state.seeThroughOpacity * 100f).toDouble(), { vm.setSeeThroughOpacity(it.toFloat() / 100f) }, Modifier.width(60.dp),
			min = 0.0, max = 100.0, step = 5.0, decimals = 0, unit = "%", enabled = !isBusy && !state.meshOnly, height = 20.dp,
			onEditStart = { vm.beginEditorField("setSeeThroughOpacity") }, onEditEnd = { vm.endEditorField("setSeeThroughOpacity") })
	}
}
