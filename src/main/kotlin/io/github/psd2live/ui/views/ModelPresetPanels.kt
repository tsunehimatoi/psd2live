package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconSelectedOnly
import io.github.psd2live.ui.state.HairMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor

/**
 * A group of the model presets, drawn like a parameter folder: chevron, folder icon, name, and on the
 * right a muted one-line [summary] of what it holds. Disabled, it neither opens nor shows its contents.
 */
@Composable
internal fun PresetFolderRow(title: String, summary: String, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(24.dp)
			.background(if (hovered && enabled) colors.controlHover.copy(alpha = 0.55f) else colors.panelElevated.copy(alpha = 0.55f))
			.hoverable(interaction)
			.handCursor(enabled)
			.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onToggle)
			.padding(start = 6.dp, end = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = expanded && enabled, tint = if (enabled) colors.textMuted else colors.textDisabled, modifier = Modifier.size(10.dp))
		Spacer(Modifier.width(4.dp))
		IconFolder(tint = if (enabled) colors.accent else colors.textDisabled, modifier = Modifier.size(12.dp))
		Spacer(Modifier.width(4.dp))
		Text(
			text = title,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = if (enabled) colors.textPrimary else colors.textDisabled,
			maxLines = 1,
			softWrap = false,
		)
		Text(
			text = summary,
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
			textAlign = TextAlign.End,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).padding(start = 8.dp),
		)
	}
	Divider(color = colors.divider.copy(alpha = 0.6f), thickness = 0.5.dp)
}

/** Shows [text] on hover over [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	TooltipArea(
		tooltip = {
			Surface(color = colors.panelElevated, shape = RoundedCornerShape(3.dp), border = BorderStroke(1.dp, colors.border), elevation = 4.dp) {
				Text(
					text = text,
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textPrimary,
					modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).width(220.dp),
				)
			}
		},
		modifier = modifier,
		delayMillis = Motion.TooltipDelay,
	) { content() }
}


/** Where the garment list starts: past the checkbox, under the clothing name. */
private val PART_NAME_INSET = 20.dp

/** Width of a part's name before its dropdown, so the hair rows line up. */
private val HAIR_LABEL_WIDTH = 40.dp

/** A muted note right of a row's control, in the warning colour when something needs attention; whole on hover. */
@Composable
private fun RowScope.RowDetail(text: String, enabled: Boolean, warning: Boolean = false) {
	val colors = LocalToolColors.current
	Box(Modifier.weight(1f).padding(start = 8.dp)) {
		if (text.isEmpty()) return@Box
		Hint(text) {
			Text(
				text = text,
				style = LocalToolTypography.current.caption.copy(fontSize = 10.sp),
				color = when {
					warning -> colors.warning
					enabled -> colors.textMuted
					else -> colors.textDisabled
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** A preset switch on its own row: checked is on, unchecked is off, then a note of what it holds. */
@Composable
private fun PresetSwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, detail: String = "", warning: Boolean = false) {
	Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
		CompactCheckbox(checked = checked, onCheckedChange = onCheckedChange, label = label, enabled = enabled)
		RowDetail(detail, enabled, warning)
	}
}

/**
 * Physics and simulation presets, one row each: how each hair moves (simulated, the legacy sway, or still),
 * clothing simulated where the art shows it hanging loose with its garments listed under it, and the eye
 * jelly pendulum. The toolbar narrows what a switch turns on to the selected layers and recomputes the
 * pin weights. Bake progress and its Cancel are in the status bar.
 */
@Composable
internal fun SimulationPresetsGroup(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val baking by viewModel.simulationBaking.collectAsState()
	val status by viewModel.simulationStatus.collectAsState()
	val report by viewModel.modelPresetReport.collectAsState()
	var selectedOnly by remember { mutableStateOf(false) }
	val busy = state.isAnalyzing || state.isGenerating || state.canvasEditBusy
	val ready = state.previewModel != null && !state.meshOnly && !busy
	val selectedCount = state.selectedLayerIds.ifEmpty { setOfNotNull(state.selectedLayerId) }.size
	// Turning a preset on reaches the selection when narrowed to it; turning one off always takes it all.
	val canApply = ready && (!selectedOnly || selectedCount > 0)
	val present = PhysicsGenerator.Presets.present(state.analysis)
	val hasClothing = state.analysis?.layers.orEmpty().any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }
	val sims = state.rigEdits.simEdits.associateBy { it.id }
	val clothingSims = ModelPresets.CLOTHING_SIMS.filterValues { it in sims }
	val garments = report?.get("garments") as? JsonObject

	/** Why simulations [ids] are not yet in effect: deleted by hand, or waiting for a bake; null when they are. */
	fun trouble(ids: Collection<String>): String? = when {
		ids.any { it !in sims } -> tr("presets.status.missing")
		baking == null && ids.any { sims.getValue(it).bake == null } -> tr("presets.status.unbaked")
		else -> null
	}

	val summary = listOfNotNull(
		tr(if (state.hairSimulationFront) "presets.summary.frontSim" else "presets.summary.frontClassic").takeIf { present.frontHair },
		tr(if (state.hairSimulationBack) "presets.summary.backSim" else "presets.summary.backClassic").takeIf { present.backHair },
		tr("presets.summary.clothing").takeIf { clothingSims.isNotEmpty() },
	).joinToString(" · ").ifEmpty { tr("presets.summary.none") }
	PresetFolderRow(tr("settings.group.simulation"), if (state.meshOnly) tr("export.disabled") else summary,
		state.simulationPresetsExpanded, !state.meshOnly) {
		viewModel.setSimulationPresetsExpanded(!state.simulationPresetsExpanded)
	}
	if (!state.simulationPresetsExpanded || state.meshOnly) return

	val clothingProblem = trouble(clothingSims.values).takeIf { clothingSims.isNotEmpty() }

	Column(
		modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 3.dp),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		// Toolbar, as in the parameter panel: the scope of what a switch turns on, and the weight tool.
		Row(
			modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			PanelToolButton(
				label = tr("presets.selectedOnly", selectedCount),
				showLabel = true,
				onClick = { selectedOnly = !selectedOnly },
				enabled = !busy && (selectedOnly || selectedCount > 0),
				active = selectedOnly,
				tooltip = tr("presets.selectedOnlyHint"),
			) {
				IconSelectedOnly(tint = if (selectedOnly) colors.accent else colors.textMuted, modifier = Modifier.size(12.dp))
			}
			Spacer(Modifier.weight(1f))
			PanelToolButton(
				label = tr("presets.autoWeights"),
				showLabel = true,
				onClick = { viewModel.applyModelPreset(ModelPresets.Preset.AUTO_WEIGHTS, selectedOnly) },
				enabled = canApply && (selectedOnly || state.rigEdits.simEdits.isNotEmpty()),
				tooltip = tr("presets.weightsHint"),
			) {
				IconPhysics(active = false, tint = colors.textPrimary, modifier = Modifier.size(12.dp))
			}
		}

		for (front in listOf(true, false)) {
			val simulated = if (front) state.hairSimulationFront else state.hairSimulationBack
			val exists = if (front) present.frontHair else present.backHair
			val sway = if (front) state.physicsFrontHair else state.physicsBackHair
			val mode = when {
				simulated -> HairMode.SIMULATION
				sway -> HairMode.CLASSIC
				else -> HairMode.OFF
			}
			val problem = if (simulated) trouble(listOf(if (front) ModelPresets.FRONT_HAIR_SIM else ModelPresets.BACK_HAIR_SIM)) else null
			Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = tr(if (front) "presets.frontHair" else "presets.backHair"),
					style = typography.body.copy(fontSize = 11.sp),
					color = if (exists) colors.textPrimary else colors.textDisabled,
					maxLines = 1,
					modifier = Modifier.width(HAIR_LABEL_WIDTH),
				)
				CompactDropdown(
					items = HairMode.entries,
					selectedItem = mode,
					onItemSelected = { next ->
						if (next == mode) return@CompactDropdown
						if (next == HairMode.SIMULATION) {
							viewModel.applyModelPreset(if (front) ModelPresets.Preset.FRONT_HAIR else ModelPresets.Preset.BACK_HAIR, selectedOnly)
						} else if (simulated) {
							viewModel.restoreClassicHair(front, sway = next == HairMode.CLASSIC)
						} else if (front) viewModel.setPhysicsFrontHair(next == HairMode.CLASSIC) else viewModel.setPhysicsBackHair(next == HairMode.CLASSIC)
					},
					itemLabel = { tr(it.key) },
					itemEnabled = { it != HairMode.SIMULATION || canApply },
					enabled = exists && ready,
					height = 20.dp,
					modifier = Modifier.width(96.dp),
				)
				RowDetail(if (!exists) tr("presets.status.absent") else problem.orEmpty(), exists, warning = problem != null)
			}
		}

		PresetSwitchRow(
			label = tr("presets.clothing"),
			checked = clothingSims.isNotEmpty(),
			enabled = hasClothing && if (clothingSims.isNotEmpty()) ready else canApply,
			onCheckedChange = { if (it) viewModel.applyModelPreset(ModelPresets.Preset.CLOTHING, selectedOnly) else viewModel.removeClothingPresets() },
			detail = when {
				!hasClothing -> tr("presets.status.noClothing")
				clothingProblem != null -> clothingProblem
				clothingSims.isEmpty() -> tr("presets.clothingHint")
				else -> clothingSims.keys.joinToString(tr("presets.listSeparator")) { tr("presets.garment.${it.jsonName}") }
			},
			warning = clothingProblem != null,
		)
		if (clothingSims.isNotEmpty() && garments != null) GarmentReport(state, garments)

		PresetSwitchRow(
			label = tr("export.physics.eyeJelly"),
			checked = state.physicsEyeJelly,
			enabled = !busy && present.eyeJelly,
			onCheckedChange = viewModel::setPhysicsEyeJelly,
			detail = if (present.eyeJelly) "" else tr("presets.status.absent"),
		)

		(status as? PSD2LiveViewModel.SimulationStatus.Failed)?.let {
			Text(
				text = it.message,
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.warning,
				modifier = Modifier.fillMaxWidth().padding(top = 2.dp).background(colors.warning.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
					.padding(horizontal = 6.dp, vertical = 3.dp),
			)
		}
	}
}

/**
 * What the last clothing preset read, under the clothing row: each simulated garment's kind, a bar of how
 * much of it hangs loose and its mesh, then one line naming the garments worn tight. Hovering a bottom
 * tells how it was told apart.
 */
@Composable
private fun GarmentReport(state: PSD2LiveState, garments: JsonObject) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val names = state.previewModel?.rig?.puppet?.drawables?.associate { it.id.raw to it.name }.orEmpty()
	val small = typography.caption.copy(fontSize = 9.5.sp)
	val tight = ArrayList<String>()
	Column(
		modifier = Modifier.fillMaxWidth().padding(start = PART_NAME_INSET, bottom = 2.dp),
		verticalArrangement = Arrangement.spacedBy(1.dp),
	) {
		for ((mesh, value) in garments) {
			val profile = value as? JsonObject ?: continue
			val garment = (profile["garment"] as? JsonPrimitive)?.content ?: continue
			if ((profile["simulated"] as? JsonPrimitive)?.booleanOrNull == false) { tight += names[mesh] ?: mesh; continue }
			val loose = ((profile["loose"] as? JsonPrimitive)?.floatOrNull ?: 0f).coerceIn(0f, 1f)
			val decidedBy = (profile["decided_by"] as? JsonPrimitive)?.content
			val line = @Composable {
				Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(15.dp)) {
					Text(tr("presets.garment.$garment"), style = small, color = colors.textPrimary, maxLines = 1,
						overflow = TextOverflow.Ellipsis, modifier = Modifier.width(52.dp))
					LoosenessBar(loose)
					Text("${kotlin.math.round(loose * 100f).toInt()}%", style = small, color = colors.textPrimary, maxLines = 1,
						modifier = Modifier.width(34.dp).padding(start = 5.dp))
					Text(names[mesh] ?: mesh, style = small, color = colors.textMuted, maxLines = 1,
						overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				}
			}
			if (decidedBy != null) Hint(tr("presets.decidedBy.$decidedBy")) { line() } else line()
		}
		if (tight.isNotEmpty()) {
			val text = tr("presets.fit.tightList", tight.joinToString(tr("presets.listSeparator")))
			Hint(text) {
				Text(text, style = small, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth().height(15.dp))
			}
		}
	}
}

/** A thin accent bar filled to [fraction] of how much of a garment hangs loose. */
@Composable
private fun LoosenessBar(fraction: Float) {
	val colors = LocalToolColors.current
	Box(Modifier.width(40.dp).height(4.dp).background(colors.inputBackground, RoundedCornerShape(2.dp))) {
		Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceAtLeast(0.04f)).background(colors.accent, RoundedCornerShape(2.dp)))
	}
}


/**
 * The rig values of the model presets: how far the rig moves each part at the parameters' full values
 * ([RigTuning]), grouped by part. The common values show under their group's name; the rest fold into an
 * Advanced folder with the same groups. Each value has a slider and a number field in the unit the MCP
 * `settings` tool and the project file use; the reset puts them all back.
 */
