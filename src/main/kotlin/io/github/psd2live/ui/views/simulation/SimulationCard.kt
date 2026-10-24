package io.github.psd2live.ui.views.simulation

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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.sim.GlueRole
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimBake
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimInputRange
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.core.sim.SimMaterial
import io.github.psd2live.core.sim.SimMaterialPreset
import io.github.psd2live.core.sim.SimOutput
import io.github.psd2live.core.sim.glueKey
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.SimulationView
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.PanelSectionRow
import io.github.psd2live.ui.views.PanelToolButton
import io.github.psd2live.ui.views.SimSectionIcon
import io.github.psd2live.ui.views.SimSectionIconView
import io.github.psd2live.ui.views.VertexGroupKindIcon
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.Hint
import io.github.psd2live.ui.views.physics.PhysicsRowDivider
import io.github.psd2live.ui.views.physics.PhysicsSection
import io.github.psd2live.ui.views.physics.CardSubsection
import io.github.psd2live.ui.views.physics.RowBadge
import io.github.psd2live.ui.views.physics.StatusDot
import io.github.psd2live.ui.views.vertexGroupKindColor
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.VertexGroupKind
import java.util.Locale
import io.github.psd2live.ui.views.PanelToolbar
import io.github.psd2live.ui.views.PanelToolbarSeparator
import io.github.psd2live.ui.views.PanelToolSwitch
import io.github.psd2live.ui.views.PanelExpandCollapseButtons
import io.github.psd2live.ui.views.PanelResetButton
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import io.github.psd2live.ui.components.CompactTextField
import java.awt.Cursor

/** Whether a simulation exports, and as its current setup. */
internal enum class BakeState { BAKED, STALE, UNBAKED, DISABLED }

/** [sim]'s bake state on [puppet] as [overlay] stands; staleness hashes the targets' keyforms, so callers remember it. */
internal fun bakeState(puppet: PuppetModel?, overlay: io.github.psd2live.core.RigEditOverlay, sim: RigSimEdit): BakeState = when {
	!sim.enabled -> BakeState.DISABLED
	sim.bake == null -> BakeState.UNBAKED
	puppet != null && SimBake.stale(puppet, sim, overlay) -> BakeState.STALE
	else -> BakeState.BAKED
}

@Composable
internal fun BakeState.color(): Color {
	val colors = LocalToolColors.current
	return when (this) {
		BakeState.BAKED -> colors.success
		BakeState.STALE -> colors.warning
		BakeState.UNBAKED -> colors.error
		BakeState.DISABLED -> colors.textDisabled
	}
}

internal fun BakeState.label() = tr("sim.state.${name.lowercase()}")

/**
 * Every simulation of the model, for the world page of the modifiers panel: Bake all with how many bodies export as
 * set up, the auto-bake switch, each body with its state (a click shows it in its mesh's stack, where it is edited) and
 * the last run's status. Bodies are made from the stack's add menu.
 */
@Composable
internal fun SimulationOverview(viewModel: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val sims = state.rigEdits.simEdits
	val status by viewModel.simulationStatus.collectAsState()
	val puppet = state.previewModel?.rig?.puppet
	val overlay = state.rigEdits
	val states = remember(puppet, sims, overlay) { sims.associate { it.id to bakeState(puppet, overlay, it) } }
	val autoBake by viewModel.simulationAutoBake.collectAsState()
	val outdated = states.values.any { it == BakeState.UNBAKED || it == BakeState.STALE }
	Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		CompactButton(tr("sim.bakeAll"), viewModel::bakeAllSimulations, enabled = sims.any { it.enabled } && !state.canvasEditBusy,
			isPrimary = outdated, height = 22.dp)
		CompactCheckbox(autoBake, viewModel::setSimulationAutoBake, label = tr("sim.autoBake"))
		Spacer(Modifier.weight(1f))
		BakeAllStatus(viewModel, sims, states, Modifier)
	}
	PhysicsRowDivider()
	if (sims.isEmpty()) Text(tr("sim.emptyStack"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp))
	for (sim in sims) {
		SimulationRow(viewModel, state, sim, states[sim.id] ?: BakeState.UNBAKED, false) { sim.targets.firstOrNull()?.let(viewModel::showStackOf) }
		PhysicsRowDivider()
	}
	StatusLine(status)
}


/**
 * How many bodies export as set up; task progress is displayed in the status bar.
 */
@Composable
private fun BakeAllStatus(
	viewModel: PSD2LiveViewModel,
	sims: List<RigSimEdit>,
	states: Map<String, BakeState>,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 10.sp)
	val baking by viewModel.simulationBaking.collectAsState()
	val enabled = sims.count { it.enabled }
	val outdated = states.values.count { it == BakeState.UNBAKED || it == BakeState.STALE }
	if (baking != null) return
	Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		if (enabled == 0) return@Row
		Dot(if (outdated > 0) colors.warning else colors.success)
		Text(if (outdated > 0) tr("sim.outdated", outdated, enabled) else tr("sim.allBaked", enabled, enabled),
			style = caption, color = if (outdated > 0) colors.warning else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape))

/** One body, styled like a physics group row; right-click for bake, clear and delete. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SimulationRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	sim: RigSimEdit,
	bakeState: BakeState,
	selected: Boolean,
	onSelect: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	val live = state.simulationPreviewId == sim.id
	Box {
		Row(
			Modifier
				.fillMaxWidth()
				.height(24.dp)
				.background(
					when {
						selected -> colors.selection.copy(alpha = 0.35f)
						hovered -> colors.controlHover.copy(alpha = 0.35f)
						else -> Color.Transparent
					},
				)
				.drawWithContent {
					drawContent()
					if (selected) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
				}
				.hoverable(interaction)
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.button == PointerButton.Secondary) {
						menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
						menuOpen = true
						onSelect()
						event.changes.firstOrNull()?.consume()
					}
				}
				.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
				.padding(start = 6.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactCheckbox(sim.enabled, { viewModel.putSimulation(sim.copy(enabled = it)) }, enabled = !state.canvasEditBusy)
			Text(
				sim.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
				color = when {
					!sim.enabled -> colors.textMuted
					selected -> colors.accent
					else -> colors.textPrimary
				},
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
			)
			// Baked is the usual state: only a dot. What needs attention is named.
			when {
				live -> StatusDot(colors.warning, tr("sim.previewReference"))
				bakeState == BakeState.BAKED -> Dot(bakeState.color())
				else -> StatusDot(bakeState.color(), bakeState.label())
			}
			RowBadge(tr("sim.kind.${sim.kind.jsonName}"))
		}
		TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
			SimulationMenuItems(viewModel, state, sim) { menuOpen = false }
		}
	}
}

@Composable
private fun SimulationMenuItems(viewModel: PSD2LiveViewModel, state: PSD2LiveState, sim: RigSimEdit, dismiss: () -> Unit) {
	val baking by viewModel.simulationBaking.collectAsState()
	// Bakes queue in the background; only clearing the bake being made would be overtaken by it.
	CompactMenuItem(tr(if (sim.bake == null) "sim.bakeAction" else "sim.rebake"), { dismiss(); viewModel.bakeSimulation(sim.id) },
		enabled = !state.canvasEditBusy && sim.enabled)
	CompactMenuItem(tr("sim.clearBake"), { dismiss(); viewModel.clearSimulationBake(sim.id) },
		enabled = !state.canvasEditBusy && baking?.id != sim.id && sim.bake != null)
	CompactMenuDivider()
	CompactMenuItem(tr("sim.delete"), { dismiss(); viewModel.deleteSimulation(sim.id) }, enabled = !state.canvasEditBusy, danger = true)
}

@Composable
internal fun StatusLine(status: PSD2LiveViewModel.SimulationStatus) {
	val colors = LocalToolColors.current
	val style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
	when (status) {
		PSD2LiveViewModel.SimulationStatus.Idle -> {}
		PSD2LiveViewModel.SimulationStatus.Preparing -> Text(tr("sim.preparing"), style = style, color = colors.textMuted, modifier = modifier)
		is PSD2LiveViewModel.SimulationStatus.Running -> if (status.notes.isNotEmpty()) Column(modifier) { status.notes.forEach { Hint(it) } }
		is PSD2LiveViewModel.SimulationStatus.Failed -> Text(status.message, style = style, color = colors.error, modifier = modifier)
	}
}

/**
 * A simulation, as its card in the modifier stack shows it: its kind, then folds for the bake (its state, checks and
 * settings), the material, the inputs, the outputs, the glue, the painted groups and the comparison with the reference
 * simulation. Edits commit one history node each, baked again in the same node while auto-bake is on; sliders commit
 * on release.
 */
@Composable
internal fun SimulationSettings(viewModel: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, sim: RigSimEdit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	fun commit(next: RigSimEdit) { if (next != sim) viewModel.putSimulation(next) }
	var open by remember(sim.id) { mutableStateOf(setOf("bake", "material")) }
	val overlay = state.rigEdits
	val bakeState = remember(puppet, sim, overlay) { bakeState(puppet, overlay, sim) }
	fun section(key: String) = key in open
	fun toggle(key: String) { open = if (key in open) open - key else open + key }

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.kindLabel"))
		CompactDropdown(SimKind.entries, sim.kind, { commit(sim.copy(kind = it, material = SimMaterial.preset(it))) },
			Modifier.weight(1f), itemLabel = { tr("sim.kind.${it.jsonName}") }, height = 22.dp)
	}

	CardSubsection(tr("sim.bake"), section("bake"), { toggle("bake") }) {
		BakeStatus(viewModel, state, puppet, sim, bakeState)
		BakeSettings(state, puppet, sim, ::commit)
	}

	val preset = SimMaterial.preset(sim.kind)
	val resetMaterial: @Composable RowScope.() -> Unit = {
		CompactIconButton(onClick = { commit(sim.copy(material = preset)) }, tooltip = tr("sim.materialReset"), size = 18.dp) {
			IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
		}
	}
	CardSubsection(tr("sim.material"), section("material"), { toggle("material") }, trailing = resetMaterial.takeIf { sim.material != preset }) {
		MaterialEditor(sim.kind, sim.material) { commit(sim.copy(material = it)) }
	}

	// The inputs are listed as they are: a new body starts with the defaults written in, and none is none.
	val parameterNames = remember(puppet) { puppet.parameters.associate { it.id.raw to it.name } }
	val defaultInputs = remember(parameterNames, sim.kind) { RigSimEdit.defaultInputs(parameterNames.keys, sim.kind) }
	val resetInputs: @Composable RowScope.() -> Unit = {
		CompactIconButton(onClick = { commit(sim.withInputs(defaultInputs)) }, tooltip = tr("sim.inputsReset"), size = 18.dp) {
			IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
		}
	}
	CardSubsection(tr("sim.inputs"), section("inputs"), { toggle("inputs") }, count = sim.inputs.size,
		trailing = resetInputs.takeIf { sim.inputs != defaultInputs && defaultInputs.isNotEmpty() }) {
		if (sim.inputs.isEmpty()) Text(tr("sim.inputsNone"), style = caption, color = colors.warning)
		for (input in sim.inputs) {
			val name = parameterNames[input.parameter]
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(name ?: input.parameter, style = caption, color = if (name == null) colors.warning else colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				Text(if (name == null) tr("sim.inputMissing") else input.parameter, style = caption, color = colors.textMuted,
					maxLines = 1, overflow = TextOverflow.Ellipsis)
				RemoveButton { commit(sim.withInputs(sim.inputs - input)) }
			}
			// What the last bake read of it off the rig: how far it moves the body, or that it was left out for moving it too little.
			val read = sim.bake?.inputs?.firstOrNull { it.parameter == input.parameter }
			val kept = input.parameter in sim.forcedInputs
			if (read != null || kept) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				if (read != null) Text(tr(if (read.dropped) "sim.inputDropped" else "sim.inputMoves", String.format(Locale.US, "%.1f", read.motionPx)),
					style = caption, color = if (read.dropped) colors.warning else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f))
				else Spacer(Modifier.weight(1f))
				if (kept || read?.dropped == true) CompactCheckbox(kept, { keep ->
					commit(sim.copy(forcedInputs = if (keep) sim.forcedInputs + input.parameter else sim.forcedInputs - input.parameter))
				}, label = tr("sim.inputKeep"))
			}
			puppet.parameters.firstOrNull { it.id.raw == input.parameter }?.let { parameter ->
				TrainingRangeRow(parameter, sim.inputRanges[input.parameter]) { range ->
					commit(sim.copy(inputRanges = if (range == null) sim.inputRanges - input.parameter else sim.inputRanges + (input.parameter to range)))
				}
			}
		}
		val available = puppet.parameters.map { it.id.raw }.filter { id -> sim.inputs.none { it.parameter == id } }
		if (available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
			if (id != null) commit(sim.copy(inputs = sim.inputs + PhysicsInput(id, type = PhysicsSourceType.ANGLE)))
		}, Modifier.fillMaxWidth(), itemLabel = { id -> id?.let { "${parameterNames[it] ?: it}  ·  $it" } ?: tr("sim.addInput") }, height = 22.dp)
	}

	val bake = sim.bake
	CardSubsection(tr("sim.outputs"), section("outputs"), { toggle("outputs") }, count = bake?.let { it.parameters.size + it.pendulums.size } ?: 0) {
		if (bake == null) Text(tr("sim.outputsNone"), style = caption, color = colors.textMuted)
		else OutputsEditor(viewModel, sim, bake)
	}

	val glues = puppet.glues.filter { it.meshA.raw in sim.targets || it.meshB.raw in sim.targets }
	CardSubsection(tr("sim.glue"), section("glue"), { toggle("glue") }, count = glues.size) {
		if (glues.isEmpty()) Text(tr("sim.noGlue"), style = caption, color = colors.textMuted)
		for (glue in glues) {
			val key = glueKey(glue)
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(key.replace("|", " ↔ "), style = caption, color = colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				CompactDropdown(GlueRole.entries, sim.glueRoles[key] ?: GlueRole.IGNORE, { role ->
					commit(sim.copy(glueRoles = if (role == GlueRole.IGNORE) sim.glueRoles - key else sim.glueRoles + (key to role)))
				}, Modifier.width(96.dp), itemLabel = { tr("sim.glueRole.${it.jsonName}") }, height = 22.dp)
			}
		}
	}

	CardSubsection(tr("sim.groups"), section("groups"), { toggle("groups") }) {
		GroupsEditor(viewModel, puppet, sim)
	}

	// On plays the reference simulation, off the export; the label names the one playing.
	val live = state.simulationPreviewId == sim.id
	val restart: @Composable RowScope.() -> Unit = {
		CompactIconButton(onClick = viewModel::restartSimulationPreview, tooltip = tr("sim.restart"), size = 18.dp) {
			IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
		}
	}
	CardSubsection(tr("sim.compare"), section("compare"), { toggle("compare") }, trailing = restart.takeIf { live }) {
		CompactCheckbox(live, { viewModel.setSimulationPreview(if (it) sim.id else null) }, label = tr(if (live) "sim.previewReference" else "sim.previewExport"))
		Text(tr(if (live) "sim.previewReferenceTip" else "sim.previewExportTip"), style = caption, color = colors.textMuted)
		CompareEditor(viewModel, state, sim)
	}
}

/**
 * The span an input trains over: its low and high end, each held to its side of the default. Matching the
 * parameter's own range again clears it.
 */
@Composable
private fun TrainingRangeRow(parameter: Parameter, range: SimInputRange?, onCommit: (SimInputRange?) -> Unit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val low = range?.min?.coerceIn(parameter.min, parameter.default) ?: parameter.min
	val high = range?.max?.coerceIn(parameter.default, parameter.max) ?: parameter.max
	fun commit(min: Float, max: Float) {
		if (min >= max) return
		onCommit(if (min <= parameter.min && max >= parameter.max) null else SimInputRange(min, max))
	}
	val step = ((parameter.max - parameter.min) / 20f).toDouble()
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 10.dp)) {
		FieldLabel(tr("sim.trainingRange"), tooltip = tr("sim.trainingRangeTip"))
		DraftNumber(low, parameter.min, parameter.default, step, Modifier.weight(1f)) { commit(it, high) }
		Text("…", style = caption, color = colors.textMuted)
		DraftNumber(high, parameter.default, parameter.max, step, Modifier.weight(1f)) { commit(low, it) }
		if (range != null) CompactIconButton(onClick = { onCommit(null) }, tooltip = tr("sim.trainingRangeReset"), size = 18.dp) {
			IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
		}
	}
}

/** A number typed or stepped into a local draft and committed once, when the edit ends. */
@Composable
private fun DraftNumber(value: Float, min: Float, max: Float, step: Double, modifier: Modifier = Modifier, decimals: Int = 1, unit: String = "",
                        onCommit: (Float) -> Unit) {
	var draft by remember(value) { mutableStateOf(value) }
	CompactNumberSpinner(draft.toDouble(), { draft = it.toFloat() }, modifier, min = min.toDouble(), max = max.toDouble(), step = step,
		decimals = decimals, unit = unit, height = 20.dp, onEditEnd = { if (draft != value) onCommit(draft) })
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) =
	CompactIconButton(onClick = onRemove, tooltip = tr("sim.remove"), size = 18.dp) {
		IconClose(modifier = Modifier.size(9.dp), tint = LocalToolColors.current.textMuted)
	}

/**
 * What the bake writes: each mode parameter and pendulum by name and ID. Clicking a name renames it on the
 * simulation, so the next rebuild and bake keep it; clearing it goes back to the name after the body.
 */
@Composable
private fun OutputsEditor(viewModel: PSD2LiveViewModel, sim: RigSimEdit, bake: io.github.psd2live.core.sim.SimBakeResult) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val vertical = SimGenerator.verticalParameterId(sim)
	val sideways = bake.parameters.count { it != vertical }
	Text(tr("sim.outputParameters"), style = caption, color = colors.textMuted)
	bake.parameters.forEachIndexed { k, id ->
		val fallback = SimGenerator.defaultParameterName(sim, id, k, sideways)
		OutputRow(sim.outputId(id), sim.outputNames[id] ?: fallback) { viewModel.renameSimulationOutput(sim.id, id, it, fallback) }
		OutputSettings(sim, id) { viewModel.setSimulationOutput(sim.id, id, it) }
	}
	Text(tr("sim.outputPendulums"), style = caption, color = colors.textMuted)
	for (pendulum in bake.pendulums) {
		OutputRow(pendulum.id, sim.outputNames[pendulum.id] ?: pendulum.name) {
			viewModel.renameSimulationOutput(sim.id, pendulum.id, it, pendulum.name)
		}
	}
}

/**
 * How mode [baked] is written: its parameter ID, its ±range and its gain over the simulated swing. All
 * apply without baking again; the reset clears them back to the generated ID, ±30 and the exaggeration.
 */
@Composable
private fun OutputSettings(sim: RigSimEdit, baked: String, onCommit: (SimOutput) -> Unit) {
	val colors = LocalToolColors.current
	val output = sim.outputs[baked] ?: SimOutput()
	var draftId by remember(baked, output.id) { mutableStateOf(sim.outputId(baked)) }
	fun commitId() {
		val id = draftId.trim()
		val next = output.copy(id = id.takeIf { it.isNotEmpty() && it != baked })
		if (next != output) onCommit(next) else draftId = sim.outputId(baked)
	}
	Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("sim.outputId"), tooltip = tr("sim.outputIdTip"))
			CompactTextField(draftId, { draftId = it }, Modifier.weight(1f), isMono = true, height = 20.dp, selectAllOnFocus = true,
				onCommit = ::commitId, onFocusLost = ::commitId)
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("sim.outputRange"), tooltip = tr("sim.outputRangeTip"))
			DraftNumber(sim.outputRange(baked), RigSimEdit.OUTPUT_RANGES.start, RigSimEdit.OUTPUT_RANGES.endInclusive, 1.0, Modifier.weight(1f), unit = "±") {
				onCommit(output.copy(range = it.takeIf { r -> r != SimGenerator.MODE_RANGE }))
			}
			FieldLabel(tr("sim.outputGain"), tooltip = tr("sim.outputGainTip"))
			DraftNumber(sim.outputGain(baked), RigSimEdit.OUTPUT_GAINS.start, RigSimEdit.OUTPUT_GAINS.endInclusive, 0.1, Modifier.weight(1f), decimals = 2, unit = "×") {
				onCommit(output.copy(gain = it.takeIf { g -> g != sim.exaggeration }))
			}
			if (!output.isDefault) CompactIconButton(onClick = { onCommit(SimOutput()) }, tooltip = tr("sim.outputReset"), size = 18.dp) {
				IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
			}
		}
	}
}

@Composable
private fun OutputRow(id: String, name: String, onRename: (String) -> Unit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	var renaming by remember(id) { mutableStateOf(false) }
	var draft by remember(id, name) { mutableStateOf(name) }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		if (renaming) {
			CompactTextField(draft, { draft = it }, modifier = Modifier.weight(1f), height = 20.dp, selectAllOnFocus = true,
				onCommit = { onRename(draft); renaming = false },
				onFocusLost = { if (renaming) onRename(draft); renaming = false })
		} else {
			Text(name, style = caption, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f).clickable { renaming = true }
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR))))
		}
		Text(id, style = caption, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = LocalToolColors.current.textPrimary,
			maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		RemoveButton(onRemove)
	}
}

/**
 * The bake: how well it matches the simulation, then Bake / Clear and the modes it wrote. Only a baked
 * simulation exports; what it bakes with is set in its card in the modifier stack.
 */
@Composable
private fun BakeStatus(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	sim: RigSimEdit,
	bakeState: BakeState,
) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val baking by viewModel.simulationBaking.collectAsState()
	val bake = sim.bake
	val running = baking?.takeIf { it.id == sim.id }

	when {
		running != null -> Unit
		bakeState == BakeState.DISABLED -> Text(tr("sim.disabledHint"), style = caption, color = colors.textMuted)
		bake == null -> Text(tr("sim.notBaked"), style = caption, color = colors.textMuted)
		else -> {
			if (bakeState == BakeState.STALE) Text(tr("sim.bakeStale"), style = caption, color = colors.warning)
			// What a viewer sees on motion the fit never saw comes first; R² follows for comparison.
			for (check in bake.visual) VisualCheckLine(tr(if (check.motion == io.github.psd2live.core.sim.SimBaker.HELD_OUT_VERTICAL) "sim.visualHeldOutVertical"
				else "sim.visualHeldOut"), check.check)
			Text(tr("sim.bakedQuality", String.format(Locale.US, "%.2f", bake.fit), String.format(Locale.US, "%.1f", bake.maxErrorPx)),
				style = caption, color = if (bake.fit < 0.8f && bake.visual.isEmpty()) colors.warning else colors.textMuted)
			if (bake.clipped > 0f || bake.jerk > 1.5f) Text(tr("sim.bakedMotion", (bake.peak * 100f).toInt(),
				String.format(Locale.US, "%.1f", bake.clipped * 100f), String.format(Locale.US, "%.2f", bake.jerk)), style = caption, color = colors.warning)
			remember(puppet, sim) { SimGenerator.issues(puppet, sim) }.forEach { Hint(it) }
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactButton(tr(if (bake == null) "sim.bakeAction" else "sim.rebake"), { viewModel.bakeSimulation(sim.id) }, Modifier.weight(1f),
			enabled = running == null && sim.enabled && !state.canvasEditBusy,
			isPrimary = bakeState == BakeState.UNBAKED || bakeState == BakeState.STALE, height = 22.dp)
		CompactButton(tr("sim.clearBake"), { viewModel.clearSimulationBake(sim.id) }, Modifier.weight(1f),
			enabled = bake != null && running == null && !state.canvasEditBusy, height = 22.dp)
	}
	if (bake != null) {
		for (mode in bake.modes) Text(tr("sim.bakedMode", mode.axis.parameter, String.format(Locale.US, "%.1f", mode.amplitude)), style = caption, color = colors.textMuted)
		if (bake.statics.isNotEmpty()) Text(tr("sim.bakedStatics", bake.statics.joinToString { it.parameter }), style = caption, color = colors.textMuted)
	}
}

/** What the bake is made with: the settings used most (modes, keys, exaggeration), the rest folded under Advanced. */
@Composable
private fun BakeSettings(
	state: PSD2LiveState,
	puppet: PuppetModel,
	sim: RigSimEdit,
	commit: (RigSimEdit) -> Unit,
) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	var advanced by remember { mutableStateOf(false) }
	val bake = sim.bake

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.modes"), tooltip = tr("sim.modesTip"))
		CompactDropdown((1..RigSimEdit.MAX_MODES).toList(), sim.modes, { commit(sim.copy(modes = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.vertical"), tooltip = tr("sim.verticalTip"))
		CompactDropdown(listOf(null, true, false), sim.vertical, { commit(sim.copy(vertical = it)) }, Modifier.weight(1f),
			itemLabel = { tr(when (it) { null -> "sim.verticalAuto"; true -> "sim.verticalOn"; false -> "sim.verticalOff" }) }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.keys"), tooltip = tr("sim.keysTip"))
		CompactDropdown(RigSimEdit.KEY_COUNTS.filter { it % 2 == 1 }, sim.keys, { commit(sim.copy(keys = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.exaggeration"), tooltip = tr("sim.exaggerationTip"))
		CompactDropdown((listOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f) + sim.exaggeration).distinct().sorted(), sim.exaggeration,
			{ commit(sim.copy(exaggeration = it)) }, Modifier.weight(1f), itemLabel = { "×" + String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }, height = 22.dp)
	}

	PanelSectionRow(tr("sim.advanced"), advanced, { advanced = !advanced }, icon = null)
	if (!advanced) return
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.blendShapes"), tooltip = tr("sim.blendShapesTip"))
		CompactDropdown(listOf(null, true, false), sim.blendShapes, { commit(sim.copy(blendShapes = it)) }, Modifier.weight(1f),
			itemLabel = { tr(when (it) { null -> "sim.blendShapesAuto"; true -> "sim.blendShapesOn"; false -> "sim.blendShapesOff" }) }, height = 22.dp)
	}
	if (sim.blendShapes == true && !puppet.runtimeTarget.supports(RuntimeFeature.MeshWarpBlendShapes))
		Text(tr("sim.blendShapesUnavailable"), style = caption, color = colors.textMuted)
	FieldLabel(tr("sim.trainingClips"), tooltip = tr("sim.trainingClipsTip"))
	val motions = motionChoices(state)
	for ((motion, weight) in sim.trainingClips) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(motions[motion] ?: motion, style = caption, color = if (motion in motions) colors.textPrimary else colors.warning,
			maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		CompactDropdown((listOf(0.25f, 0.5f, 1f) + weight).distinct().sorted(), weight, { commit(sim.copy(trainingClips = sim.trainingClips + (motion to it))) },
			Modifier.width(64.dp), itemLabel = { "×" + String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }, height = 22.dp)
		RemoveButton { commit(sim.copy(trainingClips = sim.trainingClips - motion)) }
	}
	val trainable = motions.keys.filter { it !in sim.trainingClips }
	if (trainable.isNotEmpty()) CompactDropdown(listOf<String?>(null) + trainable, null, { motion ->
		if (motion != null) commit(sim.copy(trainingClips = sim.trainingClips + (motion to 0.5f)))
	}, Modifier.fillMaxWidth(), itemLabel = { it?.let { motion -> motions[motion] ?: motion } ?: tr("sim.addTrainingClip") }, height = 22.dp)
	FieldLabel(tr("sim.staticInputs"), tooltip = tr("sim.staticInputsTip"))
	val statics = sim.staticInputs.orEmpty()
	for (parameter in statics) RemovableRow(parameter) { commit(sim.copy(staticInputs = (statics - parameter).ifEmpty { null })) }
	val available = puppet.parameters.map { it.id.raw }.filter { it !in statics && bake?.parameters?.contains(it) != true }
	if (statics.size < RigSimEdit.MAX_STATIC_INPUTS && available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
		if (id != null) commit(sim.copy(staticInputs = statics + id))
	}, Modifier.fillMaxWidth(), itemLabel = { it ?: tr("sim.addStaticInput") }, height = 22.dp)
}

/**
 * The model's motions that move a body, by name - the head's built-in ones, the skeleton's when it has one, then the
 * user's own clips by ID - with the label each shows under.
 */
private fun motionChoices(state: PSD2LiveState): Map<String, String> =
	(listOf("Idle", "Nod", "Shake") + if (state.rigEdits.skeleton != null) io.github.psd2live.core.SkeletonMotions.presets.map { it.name } else emptyList())
		.associateWith { it } + state.rigEdits.motionClips.filter { it.enabled && it.builtin == null }.associate { it.id to it.name }

/**
 * One motion's check: how far the baked body swings against the simulated one, how late, how it settles and how
 * much jerkier it is; warned when a viewer would see the difference.
 */
@Composable
private fun VisualCheckLine(label: String, check: io.github.psd2live.core.sim.SimVisualCheck) {
	val colors = LocalToolColors.current
	fun ratio(value: Float) = if (value.isNaN()) "–" else String.format(Locale.US, "%.2f", value)
	val off = check.amplitude !in 0.75f..1.3f || kotlin.math.abs(check.lagMs) > 80f || (!check.settle.isNaN() && check.settle !in 0.6f..1.6f) ||
		check.jitter > 1.5f || !check.sameSign
	Text(tr("sim.visualCheck", label, ratio(check.amplitude), check.lagMs.toInt(), ratio(check.settle), ratio(check.jitter)),
		style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = if (off) colors.warning else colors.textPrimary)
}

/**
 * The bake against the reference simulation: how the preview canvas shows the running reference (in place of the
 * export, beside it, or over it), a motion of the model's to play through both from rest, and the check of the
 * bake on that motion.
 */
@Composable
private fun CompareEditor(viewModel: PSD2LiveViewModel, state: PSD2LiveState, sim: RigSimEdit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val live = state.simulationPreviewId == sim.id
	val workspace = state.activeWorkspace
	val view = workspace.canvases.firstOrNull { it.mode == CanvasMode.PREVIEW && it.id !in workspace.hiddenModules }
		?.session(CanvasMode.PREVIEW)?.view?.simulationView ?: SimulationView.REFERENCE
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.compareView"), tooltip = tr("sim.compareViewTip"))
		CompactDropdown(SimulationView.entries, view, { viewModel.setSimulationView(it) }, Modifier.weight(1f),
			itemLabel = { tr("sim.view.${it.jsonName}") }, enabled = live, height = 22.dp)
		CompactButton(tr("sim.sideBySide"), { viewModel.showSimulationSideBySide(sim.id) }, enabled = sim.bake != null, height = 22.dp)
	}
	if (!live) Text(tr("sim.compareNeedsReference"), style = caption, color = colors.textMuted)
	val motions = motionChoices(state)
	var motion by remember(sim.id) { mutableStateOf<String?>(null) }
	val checks by viewModel.simulationChecks.collectAsState()
	val check = checks[sim.id]
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactDropdown(listOf<String?>(null) + motions.keys, motion, { motion = it }, Modifier.weight(1f),
			itemLabel = { it?.let { name -> motions[name] ?: name } ?: tr("sim.motionPick") }, height = 22.dp)
		CompactButton(tr("sim.playMotion"), { motion?.let(viewModel::playSimulationMotion) }, enabled = live && motion != null, height = 22.dp)
		CompactButton(tr("sim.checkMotion"), { motion?.let { viewModel.checkSimulation(sim.id, listOf(it)) } },
			enabled = sim.bake != null && motion != null && check?.running != true, height = 22.dp)
	}
	when {
		check == null -> Unit
		check.running -> Text(tr("sim.checking"), style = caption, color = colors.textMuted)
		check.error != null -> Hint(check.error)
		else -> for (result in check.results) VisualCheckLine(motions[result.motion] ?: result.motion, result.check)
	}
}

@Composable
private fun MaterialEditor(kind: SimKind, material: SimMaterial, onCommit: (SimMaterial) -> Unit) {
	// Sliders move a local draft and commit once on release, so a drag is one history node.
	var draft by remember(material) { mutableStateOf(material) }
	val matching = SimMaterialPreset.matching(draft)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.materialPreset"), tooltip = tr("sim.materialPresetTip"))
		val presets = SimMaterialPreset.of(kind).let { if (matching != null && matching !in it) it + matching else it }
		CompactDropdown(listOf<SimMaterialPreset?>(null) + presets, matching, { if (it != null) onCommit(it.material) },
			Modifier.weight(1f), itemLabel = { tr(if (it == null) "sim.preset.custom" else "sim.preset.${it.jsonName}") },
			itemEnabled = { it != null }, height = 22.dp)
	}
	DraftSlider(tr("sim.stretch"), tr("sim.stretchTip"), draft.stretch, 0f..1f, { draft = draft.copy(stretch = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.bend"), tr("sim.bendTip"), draft.bend, 0f..1f, { draft = draft.copy(bend = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.area"), tr("sim.areaTip"), draft.area, 0f..1f, { draft = draft.copy(area = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.anisotropy"), tr("sim.anisotropyTip"), draft.anisotropy, 0f..1f, { draft = draft.copy(anisotropy = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.goal"), tr("sim.goalTip"), draft.goal, 0f..1f, { draft = draft.copy(goal = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.damping"), tr("sim.dampingTip"), draft.damping, 0f..8f, { draft = draft.copy(damping = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.mass"), tr("sim.massTip"), draft.mass, 0.2f..4f, { draft = draft.copy(mass = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.slack"), tr("sim.slackTip"), draft.slack, 0f..0.3f, { draft = draft.copy(slack = it) }) { onCommit(draft) }
}

@Composable
private fun DraftSlider(title: String, tooltip: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(title, tooltip = tooltip)
		CompactSlider(value, { onChange((it * 100f).toInt() / 100f) }, onValueChangeFinished = onFinished, modifier = Modifier.weight(1f), valueRange = range)
		Text(String.format(Locale.US, "%.2f", value), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.width(34.dp))
	}
}

/** The kinds a simulation reads per vertex, in the order they are usually painted. */
private val SIM_GROUP_KINDS = listOf(VertexGroupKind.PIN, VertexGroupKind.STIFFNESS, VertexGroupKind.GOAL,
	VertexGroupKind.MASS, VertexGroupKind.DAMPING)

/** Whether the targets have each kind's group, and Paint to open the weight brush on it. */
@Composable
private fun GroupsEditor(viewModel: PSD2LiveViewModel, puppet: PuppetModel, sim: RigSimEdit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	for (kind in SIM_GROUP_KINDS) {
		val painted = puppet.vertexGroups.any { it.drawableId.raw in sim.targets && it.kind == kind }
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			VertexGroupKindIcon(kind, vertexGroupKindColor(kind), size = 12.dp)
			FieldLabel(tr("sim.group.${kind.jsonName}"), width = 52, tooltip = tr("sim.groupTip.${kind.jsonName}"))
			Text(tr(if (painted) "sim.painted" else "sim.noGroup"), style = typography.caption.copy(fontSize = 9.5.sp),
				color = if (painted) colors.textPrimary else colors.textMuted, modifier = Modifier.weight(1f))
			CompactButton(tr("sim.paint"), { viewModel.beginVertexGroupPaint(sim.targets.first(), kind) }, height = 22.dp)
		}
	}
}
