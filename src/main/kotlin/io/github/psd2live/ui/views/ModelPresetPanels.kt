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
import io.github.psd2live.core.recipe.ModuleCost
import io.github.psd2live.core.recipe.ModuleTier
import io.github.psd2live.core.recipe.RecipeKnob
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModule
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
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
 * An area card's rig values ([RigTuning]) that move something under the current recipe
 * ([RigModules.tuningInEffect]): how far the rig moves the area's parts at the parameters' full values. They fold
 * under one row with how many differ from their defaults; inside, the common ones by their group, then the rest in an
 * Advanced fold. Each has a slider and a number in the unit the MCP `settings` tool and the project file use; the reset
 * puts the shown ones back.
 */
@Composable
private fun AreaTuning(area: String, fields: List<RigTuning.Field>, state: PSD2LiveState, viewModel: PSD2LiveViewModel, isBusy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val tuning = state.rigTuning
	fun changedIn(list: List<RigTuning.Field>) = list.count { it.get(tuning) != it.default }
	val open = "$area/tuning" in state.rigAreasExpanded
	val changed = changedIn(fields)
	SubFoldRow(tr("settings.rig.values"), if (changed == 0) tr("settings.rigTuning.default") else tr("settings.rigTuning.changed", changed),
		open, !isBusy, start = 18.dp) { viewModel.toggleRigArea("$area/tuning") }
	if (!open) return
	val (advanced, common) = fields.partition { it.advanced }
	val groups = fields.map { it.group }.distinct().size > 1

	@Composable
	fun GroupHeader(group: RigTuning.Group) {
		Text(
			text = tr("settings.rigTuning.group." + group.name.lowercase()),
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textMuted,
			modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp),
		)
	}

	@Composable
	fun Rows(list: List<RigTuning.Field>) {
		for ((group, inGroup) in list.groupBy { it.group }) {
			if (groups) GroupHeader(group)
			for (field in inGroup) TuningRow(field, tuning, viewModel, isBusy)
		}
	}

	Rows(common)
	if (advanced.isNotEmpty()) {
		val advancedOpen = "$area/tuning/advanced" in state.rigAreasExpanded
		val advancedChanged = changedIn(advanced)
		SubFoldRow(tr("settings.rigTuning.advanced"), if (advancedChanged == 0) tr("settings.rigTuning.default") else tr("settings.rigTuning.changed", advancedChanged),
			advancedOpen, !isBusy, start = 24.dp) { viewModel.toggleRigArea("$area/tuning/advanced") }
		if (advancedOpen) Rows(advanced)
	}
	Row(Modifier.fillMaxWidth().padding(start = 18.dp), horizontalArrangement = Arrangement.End) {
		PanelResetButton(onClick = { viewModel.resetRigTuning(fields.map { it.id }) }, enabled = !isBusy && changed > 0, tooltip = tr("settings.rigTuning.reset"))
	}
}

/** A folding row inside an area card: its name, what it holds and a chevron. */
@Composable
private fun SubFoldRow(title: String, summary: String, open: Boolean, enabled: Boolean, start: androidx.compose.ui.unit.Dp, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = start, top = 2.dp)
			.height(20.dp)
			.background(if (hovered && enabled) colors.controlHover.copy(alpha = 0.55f) else colors.panelElevated.copy(alpha = 0.35f))
			.hoverable(interaction)
			.handCursor(enabled)
			.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onToggle)
			.padding(start = 4.dp, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = open, tint = colors.textMuted, modifier = Modifier.size(9.dp))
		Spacer(Modifier.width(4.dp))
		Text(title, style = typography.body.copy(fontSize = 10.5.sp), color = colors.textPrimary, modifier = Modifier.weight(1f))
		Text(summary, style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, maxLines = 1)
	}
}

/** One rig value: its name (hover for what it does), a slider and a number in its unit. */
@Composable
private fun TuningRow(field: RigTuning.Field, tuning: RigTuning, viewModel: PSD2LiveViewModel, isBusy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val value = field.get(tuning)
	val token = "setRigTuning." + field.id
	Row(modifier = Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
		Hint(tr("settings.rigTuning.${field.id}.hint", "%.1f".format(field.default)), modifier = Modifier.width(66.dp)) {
			Text(
				text = tr("settings.rigTuning.${field.id}"),
				style = typography.body.copy(fontSize = 10.5.sp),
				color = if (value != field.default) colors.accent else colors.textPrimary,
				textAlign = TextAlign.Right,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		Spacer(Modifier.width(5.dp))
		CompactSlider(
			value = value,
			onValueChange = { viewModel.setRigTuning(field.id, it) },
			onValueChangeStarted = viewModel::beginEditorGesture,
			onValueChangeFinished = viewModel::endEditorGesture,
			valueRange = field.range,
			enabled = !isBusy,
			height = 14.dp,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		CompactNumberSpinner(
			onEditStart = { viewModel.beginEditorField(token) },
			onEditEnd = { viewModel.endEditorField(token) },
			value = value.toDouble(),
			onValueChange = { viewModel.setRigTuning(field.id, it.toFloat()) },
			min = field.range.start.toDouble(),
			max = field.range.endInclusive.toDouble(),
			step = field.step.toDouble(),
			decimals = if (field.step < 1f) 1 else 0,
			unit = when (field.unit) {
				RigTuning.Unit.DEGREES -> "°"
				RigTuning.Unit.PERCENT -> "%"
				RigTuning.Unit.TORSO_LENGTHS -> tr("settings.unit.x")
			},
			enabled = !isBusy,
			modifier = Modifier.width(60.dp),
			height = 20.dp,
		)
	}
}

/** The model presets' rig module presets: everything on, the standard set (core and major) or the minimal one (core). */
private enum class RigPreset(val key: String, val tiers: Set<ModuleTier>) {
	FULL("settings.rig.preset.full", ModuleTier.entries.toSet()),
	STANDARD("settings.rig.preset.standard", setOf(ModuleTier.CORE, ModuleTier.MAJOR)),
	MINIMAL("settings.rig.preset.minimal", setOf(ModuleTier.CORE)),
}

/** A module's cost in a few words: what it adds, `≈` when estimated because it is off. */
private fun costText(cost: ModuleCost?): String {
	if (cost == null) return tr("settings.rig.notApplicable")
	val parts = listOfNotNull(
		tr("settings.rig.cost.parameters", cost.parameters.size).takeIf { cost.parameters.isNotEmpty() },
		tr("settings.rig.cost.keyforms", cost.keyforms).takeIf { cost.keyforms > 0 },
		tr("settings.rig.cost.blendShapes", cost.blendShapes).takeIf { cost.blendShapes > 0 },
		tr("settings.rig.cost.physics", cost.physicsGroups).takeIf { cost.physicsGroups > 0 },
	)
	val text = parts.joinToString(" · ").ifEmpty { tr("settings.rig.cost.none") }
	return if (cost.estimated && parts.isNotEmpty()) "≈ $text" else text
}

/**
 * The model presets' rig modules: one card per area of the rig (eyes, brows, mouth...) holding the base the area builds
 * with and the modules it adds, each switched on or off on its own with what it costs - the parameters, keyforms and
 * physics groups it adds - and its few values. The presets on top turn modules off by how much they matter; the
 * parameter budget turns off the least important until the rig adds no more parameters than it says. A project saved
 * before modules offers its upgrade; until then it generates as it did and only the modules older settings switch
 * show as on.
 */
@Composable
internal fun RigModulePresets(state: PSD2LiveState, viewModel: PSD2LiveViewModel, isBusy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val recipe = state.recipe
	val config = state.rawConfig()
	val model = state.previewModel
	val subject = RecipeSubject.of(model?.analysis)
	val physics = viewModel.physicsGroups(state).mapTo(HashSet()) { it.id }
	fun on(module: RigModule) = recipe.switchedOn(module) { RigModules.setting(config, it) }
	fun cost(module: RigModule): ModuleCost? = when {
		!module.applies(subject) -> null
		on(module) && model != null -> ModuleCost.measure(module, subject, model.rig.puppet, physics)
		else -> module.estimate(subject)
	}
	val applying = RigModules.modules.filter { it.applies(subject) }
	val costs = applying.associateWith { cost(it) }
	val active = applying.filter { on(it) }
	val parameters = active.sumOf { costs[it]?.parameters?.size ?: 0 }
	PresetFolderRow(
		title = tr("settings.group.rig"),
		summary = when {
			state.meshOnly -> tr("export.disabled")
			!recipe.modern -> tr("settings.rig.legacy")
			else -> tr("settings.rig.summary", active.size, applying.size, parameters)
		},
		expanded = state.rigModulesExpanded,
		enabled = !isBusy && !state.meshOnly,
	) { viewModel.setRigModulesExpanded(!state.rigModulesExpanded) }
	if (!state.rigModulesExpanded || state.meshOnly) return

	val small = typography.caption.copy(fontSize = 10.sp)
	Column(
		modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 3.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		if (!recipe.modern) {
			Row(
				modifier = Modifier.fillMaxWidth().background(colors.accent.copy(alpha = 0.08f), RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(tr("settings.rig.legacyHint"), style = small, color = colors.textPrimary, modifier = Modifier.weight(1f))
				Spacer(Modifier.width(6.dp))
				CompactButton(tr("settings.rig.upgrade"), onClick = viewModel::upgradeRig, enabled = !isBusy, isPrimary = true, height = 20.dp)
			}
		}

		// Presets and budget: which modules stay on, by importance or by how many parameters the rig may add.
		Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
			val modules = if (recipe.modern) applying else applying.filter { it.setting != null }
			val preset = RigPreset.entries.firstOrNull { preset -> modules.all { on(it) == RigModules.presetOn(it, preset.tiers) } }
			Hint(tr("settings.rig.preset.hint"), modifier = Modifier.width(76.dp)) {
				Text(tr("settings.rig.preset"), style = typography.body.copy(fontSize = 11.sp), color = colors.textPrimary, textAlign = TextAlign.Right,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
			}
			Spacer(Modifier.width(5.dp))
			CompactDropdown(
				items = RigPreset.entries.toList() + listOf(null),
				selectedItem = preset,
				onItemSelected = { next -> if (next != null && next != preset) viewModel.setRigModules(modules.filter { RigModules.presetOn(it, next.tiers) }.mapTo(HashSet()) { it.id } +
					RigModules.modules.filter { it !in modules && on(it) }.map { it.id }) },
				itemLabel = { tr(it?.key ?: "settings.rig.preset.custom") },
				itemEnabled = { it != null },
				enabled = !isBusy && modules.isNotEmpty(),
				height = 20.dp,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.width(4.dp))
			Hint(tr("settings.rig.budget.hint")) {
				CompactNumberSpinner(
					onEditStart = { viewModel.beginEditorField("rigBudget") },
					onEditEnd = { viewModel.endEditorField("rigBudget") },
					value = parameters.toDouble(),
					onValueChange = { budget ->
						val off = RigModules.withinBudget(active.filter { it in modules }, budget.toInt()) { costs[it]?.parameters?.size ?: 0 }
						viewModel.setRigModules(RigModules.modules.filter { on(it) && it.id !in off }.mapTo(HashSet()) { it.id })
					},
					min = 0.0,
					max = applying.sumOf { costs[it]?.parameters?.size ?: 0 }.toDouble(),
					step = 1.0,
					decimals = 0,
					unit = tr("settings.rig.budget.unit"),
					enabled = !isBusy && modules.isNotEmpty(),
					modifier = Modifier.width(84.dp),
					height = 20.dp,
				)
			}
			Spacer(Modifier.width(4.dp))
			PanelResetButton(onClick = viewModel::resetRig, enabled = !isBusy && recipe.modern && recipe.normalized() != RigRecipe.Modern,
				tooltip = tr("settings.rig.reset"))
		}

		for (area in RigModules.areas) {
			val modules = area.modules.filter { it.applies(subject) }
			val tuning = RigTuning.fields.filter { it.group.area == area.id && RigModules.tuningInEffect(it.id, config, subject) }
			if (modules.isEmpty() && area.bases.size < 2 && tuning.isEmpty()) continue
			val open = area.id in state.rigAreasExpanded
			val areaOn = modules.count { on(it) }
			val areaParameters = modules.filter { on(it) }.sumOf { costs[it]?.parameters?.size ?: 0 }
			val interaction = remember(area.id) { MutableInteractionSource() }
			val hovered by interaction.collectIsHoveredAsState()
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 2.dp)
					.height(20.dp)
					.background(if (hovered && !isBusy) colors.controlHover.copy(alpha = 0.55f) else colors.panelElevated.copy(alpha = 0.35f))
					.hoverable(interaction)
					.handCursor(!isBusy)
					.clickable(enabled = !isBusy, interactionSource = interaction, indication = null) { viewModel.toggleRigArea(area.id) }
					.padding(start = 4.dp, end = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(expanded = open, tint = colors.textMuted, modifier = Modifier.size(9.dp))
				Spacer(Modifier.width(4.dp))
				Text(tr("settings.rig.area.${area.id}"), style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary, modifier = Modifier.weight(1f))
				Text(tr("settings.rig.areaSummary", areaOn, modules.size, areaParameters), style = small, color = colors.textMuted, maxLines = 1)
			}
			if (!open) continue
			Column(
				modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
				verticalArrangement = Arrangement.spacedBy(1.dp),
			) {
				if (area.bases.size > 1) {
					val base = recipe.base(area.id)
					Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(tr("settings.rig.base"), style = typography.body.copy(fontSize = 10.5.sp), color = colors.textPrimary,
							modifier = Modifier.width(66.dp))
						CompactDropdown(
							items = area.bases,
							selectedItem = base,
							onItemSelected = { next -> if (next !== base) viewModel.setRigBase(area.id, next.id) },
							itemLabel = { tr("settings.rig.base.${it.id.replace('/', '.')}") },
							enabled = !isBusy && recipe.modern,
							height = 20.dp,
							modifier = Modifier.weight(1f),
						)
					}
					for (knob in base.knobs.filter { !it.advanced }) KnobRow(
						label = tr("settings.rig.knob.${base.id.replace('/', '.')}.${knob.id}"),
						hint = tr("settings.rig.knob.${base.id.replace('/', '.')}.${knob.id}.hint", "%.0f".format(knob.default)),
						knob = knob, value = recipe.baseValue(area.id, knob.id), token = "rigBase.${area.id}.${knob.id}", enabled = !isBusy && recipe.modern,
						viewModel = viewModel,
					) { viewModel.setRigBaseValue(area.id, knob.id, it) }
				}
				for (module in modules) {
					val moduleOn = on(module)
					val usable = !isBusy && (recipe.modern || module.setting != null)
					Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
						Hint(tr("settings.rig.module.${module.id}.hint")) {
							CompactCheckbox(checked = moduleOn, onCheckedChange = { viewModel.setRigModule(module.id, it) }, enabled = usable,
								label = tr("settings.rig.module.${module.id}"))
						}
						RowDetail(costText(costs[module]), moduleOn && recipe.modern || module.setting != null && moduleOn)
					}
					if (moduleOn) for (knob in module.knobs.filter { !it.advanced }) KnobRow(
						label = tr("settings.rig.knob.${module.id}.${knob.id}"),
						hint = tr("settings.rig.knob.${module.id}.${knob.id}.hint", "%.0f".format(knob.default)),
						knob = knob, value = recipe.value(module.id, knob.id), token = "rigModule.${module.id}.${knob.id}", enabled = usable,
						viewModel = viewModel,
					) { viewModel.setRigModuleValue(module.id, knob.id, it) }
				}
				if (tuning.isNotEmpty()) AreaTuning(area.id, tuning, state, viewModel, isBusy)
			}
		}
	}
}

/** One value of a rig base or module: its name (hover for what it does), a slider and a number in its unit. */
@Composable
private fun KnobRow(label: String, hint: String, knob: RecipeKnob, value: Float, token: String, enabled: Boolean,
                    viewModel: PSD2LiveViewModel, onChange: (Float) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier = Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
		Hint(hint, modifier = Modifier.width(66.dp)) {
			Text(
				text = label,
				style = typography.body.copy(fontSize = 10.5.sp),
				color = if (value != knob.default) colors.accent else colors.textPrimary,
				textAlign = TextAlign.Right,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		Spacer(Modifier.width(5.dp))
		CompactSlider(
			value = value,
			onValueChange = onChange,
			onValueChangeStarted = viewModel::beginEditorGesture,
			onValueChangeFinished = viewModel::endEditorGesture,
			valueRange = knob.range,
			enabled = enabled,
			height = 14.dp,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		CompactNumberSpinner(
			onEditStart = { viewModel.beginEditorField(token) },
			onEditEnd = { viewModel.endEditorField(token) },
			value = value.toDouble(),
			onValueChange = { onChange(it.toFloat()) },
			min = knob.range.start.toDouble(),
			max = knob.range.endInclusive.toDouble(),
			step = 1.0,
			decimals = 0,
			unit = when (knob.unit) {
				RigTuning.Unit.DEGREES -> "°"
				RigTuning.Unit.PERCENT -> "%"
				RigTuning.Unit.TORSO_LENGTHS -> tr("settings.unit.x")
			},
			enabled = enabled,
			modifier = Modifier.width(60.dp),
			height = 20.dp,
		)
	}
}
