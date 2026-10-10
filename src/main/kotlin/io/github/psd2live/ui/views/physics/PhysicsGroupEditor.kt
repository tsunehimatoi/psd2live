package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import io.github.psd2live.ui.views.PanelSectionRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsPresets
import io.github.psd2live.core.PhysicsResponse
import io.github.psd2live.core.PhysicsGroup
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Parameter
import java.awt.Cursor

/** The selected group: its pendulum, dynamics, inputs, outputs and the raw numbers. */
@Composable
internal fun PhysicsGroupEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	groups: List<PhysicsGroup>,
	parameters: List<Parameter>,
	onSelect: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val setting = group.setting
	// null edits every segment at once.
	var segment by remember(group.id) { mutableStateOf<Int?>(null) }
	val shownSegment = segment?.takeIf { it < setting.segments.size }
	// The output whose range fan the pendulum shows.
	var fanOutput by remember(group.id) { mutableStateOf<Int?>(null) }
	val shownOutput = fanOutput?.takeIf { it < setting.outputs.size }
	// Which sections are open survives switching groups.
	var pendulumOpen by remember { mutableStateOf(true) }
	var inputsOpen by remember { mutableStateOf(true) }
	var outputsOpen by remember { mutableStateOf(true) }
	var advancedOpen by remember { mutableStateOf(false) }
	val byId = remember(parameters) { parameters.associateBy { it.id.raw } }
	val ranges = remember(parameters) { PhysicsEngine.ranges(parameters) }
	// Lambdas remembered on what they read, not local function references: those are cached without their
	// captures, which left gestures editing a stale copy of the group.
	val label: (String) -> String = remember(byId) { { id -> byId[id]?.name?.takeIf { it.isNotBlank() && it != id } ?: id } }
	// Every edit applies to the group as it is now, so a gesture that outlives a recomposition sees earlier edits.
	val edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit = remember(viewModel, group.id) {
		{ change ->
			val current = viewModel.physicsGroups().firstOrNull { it.id == group.id }?.setting
			val next = current?.let { runCatching { change(it) }.getOrNull() }
			if (next != null && next != current) viewModel.putPhysicsGroup(next)
		}
	}
	val driven = remember(groups, group.id) { io.github.psd2live.core.PhysicsAuthoring.drivenParameters(groups, group.id) }
	// The live pendulum, shared by the canvas and the outputs' measured reach.
	val runtime = remember { PendulumRuntime() }

	Column(Modifier.fillMaxWidth()) {
		SectionBody {
			// A simulation's pendulum is renamed on the simulation, so the bake keeps driving it.
			val simulation = if (group.origin == PhysicsOrigin.SIMULATION)
				io.github.psd2live.core.sim.SimGenerator.simulationOf(group.id, state.rigEdits.simEdits) else null
			GroupTitle(viewModel, state, group, groups, onSelect) { name ->
				if (simulation != null) {
					val baked = simulation.bake?.pendulums?.firstOrNull { it.id == group.id }?.name ?: simulation.name
					viewModel.renameSimulationOutput(simulation.id, group.id, name, baked)
				} else edit { it.copy(name = name) }
			}

			PendulumEditor(
				viewModel = viewModel,
				state = state,
				runtime = runtime,
				setting = setting,
				ranges = ranges,
				selectedSegment = shownSegment,
				selectedOutput = shownOutput,
				label = label,
				onSelectSegment = { segment = it },
				onSelectOutput = { fanOutput = it },
				edit = edit,
			)
		}

		PhysicsSection(tr("physics.segmentTable"), pendulumOpen, { pendulumOpen = !pendulumOpen }) {
			PhysicsPresetBar(PhysicsPresets.Kind.PENDULUM, group.id, setting) { viewModel.applyPhysicsPreset(group.id, it); segment = null }
			SegmentBar(
				count = setting.segments.size,
				selected = shownSegment,
				onSelect = { segment = it },
				onAdd = {
					// A new pendulum goes below the selected one, or at the tip.
					val after = shownSegment ?: (setting.segments.size - 1)
					edit { it.withSegmentInserted(after) }
					segment = after + 1
				},
				onRemove = {
					val index = shownSegment ?: (setting.segments.size - 1)
					edit { it.withoutSegment(index) }
					segment = null
				},
				onMove = { by ->
					shownSegment?.let { from ->
						val to = (from + by).coerceIn(0, setting.segments.size - 1)
						edit { it.withSegmentMoved(from, to) }
						segment = to
					}
				},
			)
			DynamicsSliders(viewModel, setting, shownSegment, edit)
			ResponseGraph(setting, ranges, state.rigEdits.physicsFps, label)
		}

		PhysicsSection(tr("physics.inputs"), inputsOpen, { inputsOpen = !inputsOpen }, count = setting.inputs.size, trailing = {
			AddParameterButton(tr("physics.addInput"), parameters, exclude = setting.parameters.toSet(), driven = emptyMap(), label = label) { id ->
				edit { it.copy(inputs = it.inputs + PhysicsInput(id, 50f, if (id.endsWith("AngleZ")) PhysicsSourceType.ANGLE else PhysicsSourceType.X)) }
			}
		}) {
			PhysicsPresetBar(PhysicsPresets.Kind.INPUT, group.id, setting) { viewModel.applyPhysicsPreset(group.id, it) }
			if (setting.inputs.isEmpty()) Hint(tr("physics.inputs.empty"))
			setting.inputs.forEachIndexed { index, input ->
				InputRow(viewModel, input, parameters, setting, label,
					onChange = { next -> edit { it.copy(inputs = it.inputs.mapIndexed { i, x -> if (i == index) next else x }) } },
					onRemove = { edit { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) } })
			}
		}

		PhysicsSection(tr("physics.outputs"), outputsOpen, { outputsOpen = !outputsOpen }, count = setting.outputs.size, trailing = {
			AddParameterButton(tr("physics.addOutput"), parameters, exclude = setting.parameters.toSet(), driven = driven, label = label) { id ->
				edit { it.copy(outputs = it.outputs + PhysicsOutput(id, shownSegment?.plus(1) ?: it.segments.size, 1f)) }
			}
		}) {
			if (setting.outputs.isEmpty()) Hint(tr("physics.outputs.empty"))
			else OutputReachBar(
				onFit = {
					// The swing seen on the pendulum so far, as Cubism Editor fits to the playback's maximum;
					// before anything has moved, a standard head sway.
					val seen = runtime.peaks.filterValues { it > 0.01f }
					viewModel.fitPhysicsScales(group.id, seen.ifEmpty { PhysicsResponse.trace(setting, ranges, state.rigEdits.physicsFps).reach })
				},
				onReset = runtime::resetPeaks,
			)
			setting.outputs.forEachIndexed { index, output ->
				OutputRow(viewModel, index, output, index == shownOutput, { fanOutput = if (shownOutput == index) null else index },
					parameters, setting, driven, label, runtime.peaks[index],
					onChange = { next -> edit { it.copy(outputs = it.outputs.mapIndexed { i, x -> if (i == index) next else x }) } },
					onRemove = { edit { it.copy(outputs = it.outputs.filterIndexed { i, _ -> i != index }) } })
			}
		}

		PhysicsSection(tr("physics.advanced"), advancedOpen, { advancedOpen = !advancedOpen }) {
			AdvancedSection(viewModel, setting, edit)
		}
	}
}

/** A folder-style header over its padded body, shown while [open]; the simulation panel uses it too. */
@Composable
internal fun PhysicsSection(
	title: String,
	open: Boolean,
	onToggle: () -> Unit,
	count: Int? = null,
	icon: (@Composable () -> Unit)? = null,
	trailing: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	PanelSectionRow(title, open, onToggle, count = count, icon = icon, trailing = trailing?.let { t -> { Spacer(Modifier.width(6.dp)); t() } })
	PhysicsRowDivider()
	if (open) SectionBody(content)
}

@Composable
private fun SectionBody(content: @Composable ColumnScope.() -> Unit) {
	Column(
		Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
		content = content,
	)
	PhysicsRowDivider()
}

/** Name (renamable), where the group comes from, and why it does or does not export. */
@Composable
internal fun GroupTitle(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	groups: List<PhysicsGroup>,
	onSelect: (String) -> Unit,
	onRename: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var renaming by remember(group.id) { mutableStateOf(false) }
	var draft by remember(group.id, group.setting.name) { mutableStateOf(group.setting.name) }
	var menu by remember { mutableStateOf(false) }
	val swing = if (group.origin == PhysicsOrigin.SWING) PhysicsGenerator.swingOf(group.id, state.rigEdits.swingEdits) else null
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		if (renaming) {
			CompactTextField(draft, { draft = it }, modifier = Modifier.weight(1f), height = 22.dp, selectAllOnFocus = true,
				onCommit = { if (draft.isNotBlank()) onRename(draft.trim()); renaming = false },
				onFocusLost = { if (renaming && draft.isNotBlank()) onRename(draft.trim()); renaming = false })
		} else {
			Text(
				group.setting.name,
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f).clickable { renaming = true },
			)
		}
		OriginBadge(group.origin)
		Box {
			CompactIconButton(onClick = { menu = true }, size = 20.dp, tooltip = tr("physics.more")) {
				Text("⋯", style = typography.body.copy(fontSize = 12.sp), color = colors.textMuted)
			}
			TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
				PhysicsGroupMenuItems(viewModel, state, group, groups, onSelect, onRename = { renaming = true }) { menu = false }
			}
		}
	}
	val origin = when (group.origin) {
		PhysicsOrigin.PRESET -> tr("physics.originNote.preset")
		PhysicsOrigin.SKELETON -> tr("physics.originNote.skeleton")
		PhysicsOrigin.SWING -> tr("physics.originNote.swing", swing?.name ?: "")
		PhysicsOrigin.SIMULATION -> tr("physics.originNote.simulation",
			io.github.psd2live.core.sim.SimGenerator.simulationOf(group.id, state.rigEdits.simEdits)?.name ?: "")
		PhysicsOrigin.CUSTOM -> tr("physics.originNote.custom")
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(origin, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
		if (group.overridden) {
			Text(
				tr("physics.resetDefaults"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.accent,
				modifier = Modifier.clickable { viewModel.removePhysicsGroup(group.id) }.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
			)
		}
	}
	group.issue?.let { Text(issueText(it), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.warning) }
	// What keeps the swing from moving its Warps (one gone, one with no lattice), as the simulation panel shows its own.
	val puppet = state.previewModel?.rig?.puppet
	if (swing != null && puppet != null) remember(puppet, swing) { io.github.psd2live.core.SwingGenerator.issues(puppet, swing) }.forEach {
		Text(it, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.warning)
	}
	group.shadowedBy?.let { Text(tr("physics.replacedBy", it), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted) }
	if (!group.enabled) Text(tr("physics.disabledNote"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
}
