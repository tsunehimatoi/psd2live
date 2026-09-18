package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.CubismPhysicsSimulator
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.StandardParameters
import org.umamo.runtime.model.ParameterId
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * UI representation of an editable physics group in psd2live.
 */
internal data class PhysicsGroupItem(
	val id: String,
	val name: String,
	val isBuiltIn: Boolean,
	val isOverridden: Boolean,
	val isEnabled: Boolean,
	val inputParameter: String,
	val outputParameter: String,
	val length: Float,
	val mobility: Float,
	val delay: Float,
	val acceleration: Float,
	val outputScale: Float,
)

/**
 * Dedicated Physics Simulation Inspector Panel adapted for narrow sidebar (300-360dp).
 * All parameters are truly editable and bound to project state and CMO3/MOC3 export.
 */
@Composable
internal fun PhysicsPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val scrollState = rememberScrollState()

	// 1. Resolve real physics groups (Built-in + Custom overrides/additions)
	val groups = remember(state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly, state.rigEdits.physicsEdits) {
		buildList {
			// Group 0: Back Hair (后发摆动)
			val backOverride = state.rigEdits.physicsEdits.find { it.id == "PhysicsHairBack" || it.outputParameter == StandardParameters.HAIR_BACK.raw }
			add(
				PhysicsGroupItem(
					id = "PhysicsHairBack",
					name = tr("physics.group.backHair"),
					isBuiltIn = true,
					isOverridden = backOverride != null,
					isEnabled = state.physicsBackHair,
					inputParameter = backOverride?.inputParameter ?: StandardParameters.ANGLE_X.raw,
					outputParameter = StandardParameters.HAIR_BACK.raw,
					length = backOverride?.length ?: 15.0f,
					mobility = backOverride?.mobility ?: 0.95f,
					delay = backOverride?.delay ?: 0.80f,
					acceleration = backOverride?.acceleration ?: 1.50f,
					outputScale = backOverride?.outputScale ?: 2.061f,
				),
			)

			// Group 1: Front Hair (前发摆动)
			val frontOverride = state.rigEdits.physicsEdits.find { it.id == "PhysicsHairFront" || it.outputParameter == StandardParameters.HAIR_FRONT.raw }
			add(
				PhysicsGroupItem(
					id = "PhysicsHairFront",
					name = tr("physics.group.frontHair"),
					isBuiltIn = true,
					isOverridden = frontOverride != null,
					isEnabled = state.physicsFrontHair,
					inputParameter = frontOverride?.inputParameter ?: StandardParameters.ANGLE_X.raw,
					outputParameter = StandardParameters.HAIR_FRONT.raw,
					length = frontOverride?.length ?: 7.9f,
					mobility = frontOverride?.mobility ?: 0.77f,
					delay = frontOverride?.delay ?: 1.45f,
					acceleration = frontOverride?.acceleration ?: 0.80f,
					outputScale = frontOverride?.outputScale ?: 1.522f,
				),
			)

			// Group 2: Eye Jelly (眼球果冻)
			val jellyOverride = state.rigEdits.physicsEdits.find { it.id == "PhysicsEyeJelly" || it.outputParameter == StandardParameters.EYE_BALL_FORM.raw }
			add(
				PhysicsGroupItem(
					id = "PhysicsEyeJelly",
					name = tr("physics.group.eyeJelly"),
					isBuiltIn = true,
					isOverridden = jellyOverride != null,
					isEnabled = state.physicsEyeJelly,
					inputParameter = jellyOverride?.inputParameter ?: StandardParameters.EYE_L_OPEN.raw,
					outputParameter = StandardParameters.EYE_BALL_FORM.raw,
					length = jellyOverride?.length ?: 2.0f,
					mobility = jellyOverride?.mobility ?: 0.80f,
					delay = jellyOverride?.delay ?: 0.32f,
					acceleration = jellyOverride?.acceleration ?: 2.20f,
					outputScale = jellyOverride?.outputScale ?: 0.32f,
				),
			)

			// Custom Groups
			state.rigEdits.physicsEdits.filterNot {
				it.id in listOf("PhysicsHairBack", "PhysicsHairFront", "PhysicsEyeJelly") ||
					it.outputParameter in listOf(StandardParameters.HAIR_BACK.raw, StandardParameters.HAIR_FRONT.raw, StandardParameters.EYE_BALL_FORM.raw)
			}.forEach { custom ->
				add(
					PhysicsGroupItem(
						id = custom.id,
						name = custom.name,
						isBuiltIn = false,
						isOverridden = false,
						isEnabled = true,
						inputParameter = custom.inputParameter,
						outputParameter = custom.outputParameter,
						length = custom.length,
						mobility = custom.mobility,
						delay = custom.delay,
						acceleration = custom.acceleration,
						outputScale = custom.outputScale,
					),
				)
			}
		}
	}

	var selectedGroupId by remember { mutableStateOf("PhysicsHairBack") }
	val currentGroup = groups.firstOrNull { it.id == selectedGroupId } ?: groups.first()

	// Available model parameter IDs for dropdown selection
	val allModelParamIds = remember(state.previewModel) {
		val params = state.previewModel?.rig?.puppet?.parameters?.map { it.id.raw } ?: emptyList()
		if (params.isNotEmpty()) params else listOf(
			StandardParameters.ANGLE_X.raw,
			StandardParameters.ANGLE_Y.raw,
			StandardParameters.ANGLE_Z.raw,
			StandardParameters.BODY_X.raw,
			StandardParameters.BODY_Y.raw,
			StandardParameters.BODY_Z.raw,
			StandardParameters.HAIR_FRONT.raw,
			StandardParameters.HAIR_BACK.raw,
			StandardParameters.EYE_BALL_FORM.raw,
			StandardParameters.EYE_L_OPEN.raw,
			StandardParameters.EYE_R_OPEN.raw,
		)
	}

	val driverParamCandidates = remember(allModelParamIds) {
		val candidates = listOf(
			StandardParameters.ANGLE_X.raw,
			StandardParameters.ANGLE_Y.raw,
			StandardParameters.ANGLE_Z.raw,
			StandardParameters.BODY_X.raw,
			StandardParameters.BODY_Y.raw,
			StandardParameters.BODY_Z.raw,
			StandardParameters.EYE_L_OPEN.raw,
			StandardParameters.EYE_R_OPEN.raw,
		)
		(candidates + allModelParamIds).distinct()
	}

	var selectedFps by remember { mutableStateOf("120 FPS") }

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.panelBackground)
			.verticalScroll(scrollState)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// 1. Global & Test Control Card
		PhysicsGlobalControlCard(
			viewModel = viewModel,
			state = state,
			selectedFps = selectedFps,
			onFpsChange = { selectedFps = it },
		)

		// 2. Physics Group Management Card
		PhysicsGroupManagementCard(
			groups = groups,
			currentGroup = currentGroup,
			onSelectGroup = { selectedGroupId = it.id },
			onToggleGroupEnabled = { checked ->
				when (currentGroup.id) {
					"PhysicsHairBack" -> viewModel.setPhysicsBackHair(checked)
					"PhysicsHairFront" -> viewModel.setPhysicsFrontHair(checked)
					"PhysicsEyeJelly" -> viewModel.setPhysicsEyeJelly(checked)
				}
			},
			onAddGroup = {
				val usedOutputs = groups.map { it.outputParameter }.toSet()
				val candidateOutput = allModelParamIds.firstOrNull { it !in usedOutputs } ?: "ParamCustomPhysics${groups.size}"
				val newId = "PhysicsCustom_${System.currentTimeMillis() % 100000}"
				val newName = "${tr("physics.group")} ${groups.size - 2}"
				val newEdit = RigPhysicsEdit(
					id = newId,
					name = newName,
					inputParameter = StandardParameters.ANGLE_X.raw,
					outputParameter = candidateOutput,
					length = 10.0f,
					mobility = 0.80f,
					delay = 0.80f,
					acceleration = 1.00f,
					outputScale = 1.0f,
				)
				viewModel.upsertPhysicsEdit(newEdit)
				selectedGroupId = newId
			},
			onDeleteGroup = {
				viewModel.removePhysicsEdit(currentGroup.id)
				selectedGroupId = "PhysicsHairBack"
			},
			onResetDefaults = {
				viewModel.removePhysicsEdit(currentGroup.id)
			},
		)

		// 3. Input & Output Mapping Card
		PhysicsMappingCard(
			group = currentGroup,
			allOutputParams = allModelParamIds,
			driverParams = driverParamCandidates,
			onUpdateEdit = { updatedEdit ->
				viewModel.upsertPhysicsEdit(updatedEdit)
			},
		)

		// 4. Pendulum Dynamic Parameters Card
		PhysicsPendulumCard(
			group = currentGroup,
			onUpdateEdit = { updatedEdit ->
				viewModel.upsertPhysicsEdit(updatedEdit)
			},
			onResetDefaults = {
				viewModel.removePhysicsEdit(currentGroup.id)
			},
		)

		// 5. Interactive Real-time Pendulum Scope Card
		PhysicsVisualizerCard(
			viewModel = viewModel,
			state = state,
			group = currentGroup,
		)

		Spacer(Modifier.height(8.dp))
	}
}

/** Card 1: Global Simulation & Test Bar */
@Composable
private fun PhysicsGlobalControlCard(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	selectedFps: String,
	onFpsChange: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isPlaying = state.animationEnabled && !state.meshOnly

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.windowBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = tr("physics.globalTitle"),
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)

		// Global Toggles
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			CompactCheckbox(
				checked = state.generatePhysics,
				onCheckedChange = { viewModel.setGeneratePhysics(it) },
				label = tr("physics.enableSimulation"),
			)

			CompactCheckbox(
				checked = state.mouseTrackingEnabled,
				onCheckedChange = { viewModel.setMouseTrackingEnabled(it) },
				label = tr("animation.mouseTracking"),
			)
		}

		// Playback, Reset, and FPS Dropdown
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			// Play / Pause
			CompactButton(
				text = if (isPlaying) tr("common.off") else tr("common.on"),
				onClick = { viewModel.setAnimationEnabled(!state.animationEnabled) },
				leadingIcon = {
					if (isPlaying) IconPause(modifier = Modifier.size(10.dp), tint = colors.accent)
					else IconPlay(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
				},
				height = 24.dp,
				modifier = Modifier.weight(1f),
			)

			// Reset Pose
			CompactButton(
				text = tr("physics.reset"),
				onClick = { viewModel.resetAllParameters() },
				leadingIcon = { IconReset(modifier = Modifier.size(10.dp), tint = colors.textPrimary) },
				height = 24.dp,
				modifier = Modifier.weight(1f),
			)

			// FPS Dropdown
			CompactDropdown(
				items = listOf("120 FPS", "60 FPS", "30 FPS"),
				selectedItem = selectedFps,
				onItemSelected = onFpsChange,
				modifier = Modifier.width(88.dp),
				height = 24.dp,
			)
		}
	}
}

/** Card 2: Physics Group Selection & Management */
@Composable
private fun PhysicsGroupManagementCard(
	groups: List<PhysicsGroupItem>,
	currentGroup: PhysicsGroupItem,
	onSelectGroup: (PhysicsGroupItem) -> Unit,
	onToggleGroupEnabled: (Boolean) -> Unit,
	onAddGroup: () -> Unit,
	onDeleteGroup: () -> Unit,
	onResetDefaults: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.windowBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.group"),
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)

			if (currentGroup.isOverridden) {
				Text(
					text = "(自定义修改)",
					style = typography.caption.copy(fontSize = 9.sp, color = colors.accent),
				)
			}
		}

		// Group Dropdown selector
		CompactDropdown(
			items = groups,
			selectedItem = currentGroup,
			onItemSelected = onSelectGroup,
			itemLabel = { "${it.name} (${it.outputParameter})" },
			modifier = Modifier.fillMaxWidth(),
			height = 26.dp,
		)

		// Operation Row
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			CompactCheckbox(
				checked = currentGroup.isEnabled,
				onCheckedChange = onToggleGroupEnabled,
				label = tr("physics.enabled"),
			)

			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				CompactButton(
					text = tr("physics.addGroup"),
					onClick = onAddGroup,
					height = 22.dp,
				)

				if (!currentGroup.isBuiltIn) {
					CompactButton(
						text = tr("physics.deleteGroup"),
						onClick = onDeleteGroup,
						height = 22.dp,
					)
				} else if (currentGroup.isOverridden) {
					CompactButton(
						text = tr("physics.resetDefaults"),
						onClick = onResetDefaults,
						height = 22.dp,
					)
				}
			}
		}
	}
}

/** Card 3: Input & Output Parameter Mapping */
@Composable
private fun PhysicsMappingCard(
	group: PhysicsGroupItem,
	allOutputParams: List<String>,
	driverParams: List<String>,
	onUpdateEdit: (RigPhysicsEdit) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.windowBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = tr("physics.mappingTitle"),
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)

		// 1. Target Output Parameter
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.outputParam"),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				modifier = Modifier.width(96.dp),
			)

			if (group.isBuiltIn) {
				Box(
					modifier = Modifier
						.weight(1f)
						.height(24.dp)
						.background(colors.inputBackground, RoundedCornerShape(2.dp))
						.border(BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)), RoundedCornerShape(2.dp))
						.padding(horizontal = 8.dp),
					contentAlignment = Alignment.CenterStart,
				) {
					Text(
						text = group.outputParameter,
						style = typography.caption.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
						color = colors.accent,
					)
				}
			} else {
				CompactDropdown(
					items = allOutputParams,
					selectedItem = group.outputParameter,
					onItemSelected = { newOutput ->
						onUpdateEdit(
							RigPhysicsEdit(
								id = group.id,
								name = group.name,
								inputParameter = group.inputParameter,
								outputParameter = newOutput,
								length = group.length,
								mobility = group.mobility,
								delay = group.delay,
								acceleration = group.acceleration,
								outputScale = group.outputScale,
							),
						)
					},
					modifier = Modifier.weight(1f),
					height = 24.dp,
				)
			}
		}

		// 2. Driver Input Parameter
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.inputParam"),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				modifier = Modifier.width(96.dp),
			)

			CompactDropdown(
				items = driverParams,
				selectedItem = group.inputParameter,
				onItemSelected = { newInput ->
					onUpdateEdit(
						RigPhysicsEdit(
							id = group.id,
							name = group.name,
							inputParameter = newInput,
							outputParameter = group.outputParameter,
							length = group.length,
							mobility = group.mobility,
							delay = group.delay,
							acceleration = group.acceleration,
							outputScale = group.outputScale,
						),
					)
				},
				modifier = Modifier.weight(1f),
				height = 24.dp,
			)
		}

		// 3. Output Scale
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.outputScale"),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				modifier = Modifier.width(96.dp),
			)

			CompactNumberSpinner(
				value = group.outputScale.toDouble(),
				onValueChange = { newScale ->
					onUpdateEdit(
						RigPhysicsEdit(
							id = group.id,
							name = group.name,
							inputParameter = group.inputParameter,
							outputParameter = group.outputParameter,
							length = group.length,
							mobility = group.mobility,
							delay = group.delay,
							acceleration = group.acceleration,
							outputScale = newScale.toFloat(),
						),
					)
				},
				min = 0.01,
				max = 20.0,
				step = 0.1,
				decimals = 2,
				modifier = Modifier.weight(1f),
				height = 24.dp,
			)
		}
	}
}

/** Card 4: Pendulum Physical Dynamics Parameters */
@Composable
private fun PhysicsPendulumCard(
	group: PhysicsGroupItem,
	onUpdateEdit: (RigPhysicsEdit) -> Unit,
	onResetDefaults: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.windowBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.dynamicsTitle"),
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)

			if (group.isBuiltIn && group.isOverridden) {
				Text(
					text = tr("physics.resetDefaults"),
					style = typography.caption.copy(fontSize = 8.5.sp, color = colors.accent),
					modifier = Modifier.clickable { onResetDefaults() },
				)
			}
		}

		// 1. Pendulum Length
		PendulumParamRow(
			label = tr("physics.length"),
			value = group.length.toDouble(),
			onValueChange = { newVal ->
				onUpdateEdit(
					RigPhysicsEdit(
						id = group.id,
						name = group.name,
						inputParameter = group.inputParameter,
						outputParameter = group.outputParameter,
						length = newVal.toFloat(),
						mobility = group.mobility,
						delay = group.delay,
						acceleration = group.acceleration,
						outputScale = group.outputScale,
					),
				)
			},
			min = 0.5,
			max = 50.0,
			step = 0.5,
			decimals = 1,
			unit = "cm",
		)

		// 2. Pendulum Mobility (Shakiness)
		PendulumParamRow(
			label = tr("physics.shakiness"),
			value = group.mobility.toDouble(),
			onValueChange = { newVal ->
				onUpdateEdit(
					RigPhysicsEdit(
						id = group.id,
						name = group.name,
						inputParameter = group.inputParameter,
						outputParameter = group.outputParameter,
						length = group.length,
						mobility = newVal.toFloat(),
						delay = group.delay,
						acceleration = group.acceleration,
						outputScale = group.outputScale,
					),
				)
			},
			min = 0.0,
			max = 1.0,
			step = 0.05,
			decimals = 2,
		)

		// 3. Pendulum Reaction Delay
		PendulumParamRow(
			label = tr("physics.reactionSpeed"),
			value = group.delay.toDouble(),
			onValueChange = { newVal ->
				onUpdateEdit(
					RigPhysicsEdit(
						id = group.id,
						name = group.name,
						inputParameter = group.inputParameter,
						outputParameter = group.outputParameter,
						length = group.length,
						mobility = group.mobility,
						delay = newVal.toFloat(),
						acceleration = group.acceleration,
						outputScale = group.outputScale,
					),
				)
			},
			min = 0.05,
			max = 3.0,
			step = 0.05,
			decimals = 2,
			unit = "s",
		)

		// 4. Convergence Speed (Acceleration)
		PendulumParamRow(
			label = tr("physics.convergenceSpeed"),
			value = group.acceleration.toDouble(),
			onValueChange = { newVal ->
				onUpdateEdit(
					RigPhysicsEdit(
						id = group.id,
						name = group.name,
						inputParameter = group.inputParameter,
						outputParameter = group.outputParameter,
						length = group.length,
						mobility = group.mobility,
						delay = group.delay,
						acceleration = newVal.toFloat(),
						outputScale = group.outputScale,
					),
				)
			},
			min = 0.1,
			max = 5.0,
			step = 0.1,
			decimals = 2,
		)
	}
}

@Composable
private fun PendulumParamRow(
	label: String,
	value: Double,
	onValueChange: (Double) -> Unit,
	min: Double,
	max: Double,
	step: Double,
	decimals: Int,
	unit: String = "",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = label,
			style = typography.caption.copy(fontSize = 9.5.sp),
			color = colors.textMuted,
			modifier = Modifier.width(115.dp),
		)

		CompactNumberSpinner(
			value = value,
			onValueChange = onValueChange,
			min = min,
			max = max,
			step = step,
			decimals = decimals,
			unit = unit,
			modifier = Modifier.weight(1f),
			height = 22.dp,
		)
	}
}

private class VisualizerRuntimeState {
	val simulator = CubismPhysicsSimulator()
	var currentAngleDeg: Float = 0f
	var isDragging: Boolean = false
	var dragAngleDeg: Float = 0f
	var cachedScaleText: String = ""
	var cachedScaleLayout: TextLayoutResult? = null
	var cachedAngleText: String = ""
	var cachedAngleLayout: TextLayoutResult? = null
	val cachedIndexLabels = mutableMapOf<Int, TextLayoutResult>()
}

/** Card 5: Interactive Real-time Pendulum Scope Viewport (Clean, high-performance DCC Scope) */
@Composable
private fun PhysicsVisualizerCard(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroupItem,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val textMeasurer = rememberTextMeasurer()

	val simRuntime = remember { VisualizerRuntimeState() }
	var viewZoom by remember { mutableFloatStateOf(1.0f) }
	var animTick by remember { mutableLongStateOf(0L) }

	// Reconfigure simulator strand whenever physical parameters change
	LaunchedEffect(group.id, group.length, group.mobility, group.delay, group.acceleration, group.outputParameter) {
		val strandCount = if (group.outputParameter == StandardParameters.EYE_BALL_FORM.raw) 3 else 2
		simRuntime.simulator.reset(
			strandCount = strandCount,
			totalLength = group.length,
			mobility = group.mobility,
			delay = group.delay,
			acceleration = group.acceleration,
		)
		simRuntime.cachedIndexLabels.clear()
	}

	// Real-time Physics Simulation Loop (Decoupled from Composable tree recomposition)
	LaunchedEffect(state.animationEnabled, group.outputParameter, group.inputParameter) {
		var lastTime = System.nanoTime()
		while (true) {
			withFrameNanos { now ->
				val dt = ((now - lastTime) / 1_000_000_000.0).coerceIn(0.001, 0.05).toFloat()
				lastTime = now

				if (simRuntime.isDragging) {
					simRuntime.simulator.setParticleAngle(1, simRuntime.dragAngleDeg)
					simRuntime.currentAngleDeg = simRuntime.dragAngleDeg
				} else {
					var totalTransX = 0f
					var totalAngleDeg = 0f

					when (group.outputParameter) {
						StandardParameters.HAIR_BACK.raw, StandardParameters.HAIR_FRONT.raw -> {
							val angleX = (viewModel.currentLiveParameters[StandardParameters.ANGLE_X] ?: 0f) / 30f
							val angleZ = (viewModel.currentLiveParameters[StandardParameters.ANGLE_Z] ?: 0f)
							val bodyX = (viewModel.currentLiveParameters[StandardParameters.BODY_X] ?: 0f) / 10f
							val bodyZ = (viewModel.currentLiveParameters[StandardParameters.BODY_Z] ?: 0f)

							totalTransX = (angleX * 0.60f + bodyX * 0.40f) * 6f
							totalAngleDeg = (angleZ * 0.60f + bodyZ * 0.40f)
						}
						StandardParameters.EYE_BALL_FORM.raw -> {
							val eyeL = (viewModel.currentLiveParameters[StandardParameters.EYE_L_OPEN] ?: 1f) - 1f
							val eyeR = (viewModel.currentLiveParameters[StandardParameters.EYE_R_OPEN] ?: 1f) - 1f
							totalTransX = (eyeL * 0.5f + eyeR * 0.5f) * 4f
							totalAngleDeg = 0f
						}
						else -> {
							val pId = ParameterId(group.inputParameter)
							val inputVal = viewModel.currentLiveParameters[pId] ?: 0f
							totalTransX = inputVal * 4f
							totalAngleDeg = inputVal * 15f
						}
					}

					simRuntime.simulator.update(
						totalTranslationX = totalTransX,
						totalTranslationY = 0f,
						totalAngleDegrees = totalAngleDeg,
						dt = dt,
					)
					simRuntime.currentAngleDeg = simRuntime.simulator.getOutputAngleDegrees(1)
				}
				animTick = now
			}
		}
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(colors.windowBackground, RoundedCornerShape(4.dp))
			.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = tr("physics.visualizerTitle"),
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)

			Text(
				text = tr("physics.dragHint"),
				style = typography.caption.copy(fontSize = 8.sp),
				color = colors.textMuted,
			)
		}

		// Scope Viewport Box (Clean DCC Scope)
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(180.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(colors.inputBackground)
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
				.pointerInput(group.length, viewZoom) {
					detectDragGestures(
						onDragStart = { offset ->
							simRuntime.isDragging = true
							val pixelPerCm = (size.height * 0.58f / group.length.coerceAtLeast(5f)) * viewZoom
							val root = simRuntime.simulator.particles.firstOrNull()
							val rootX = size.width * 0.5f + (root?.x ?: 0f) * pixelPerCm
							val rootY = size.height * 0.16f - (root?.y ?: 0f) * pixelPerCm
							val dx = offset.x - rootX
							val dy = (offset.y - rootY).coerceAtLeast(8f)
							val deg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
							simRuntime.dragAngleDeg = deg.coerceIn(-85f, 85f)
						},
						onDrag = { change, _ ->
							change.consume()
							val pixelPerCm = (size.height * 0.58f / group.length.coerceAtLeast(5f)) * viewZoom
							val root = simRuntime.simulator.particles.firstOrNull()
							val rootX = size.width * 0.5f + (root?.x ?: 0f) * pixelPerCm
							val rootY = size.height * 0.16f - (root?.y ?: 0f) * pixelPerCm
							val dx = change.position.x - rootX
							val dy = (change.position.y - rootY).coerceAtLeast(8f)
							val deg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
							simRuntime.dragAngleDeg = deg.coerceIn(-85f, 85f)
						},
						onDragEnd = {
							simRuntime.isDragging = false
						},
						onDragCancel = {
							simRuntime.isDragging = false
						},
					)
				},
		) {
			// Canvas Rendering: Grid, Moving Root Pivot, Connected Rod, Particle Bobs, Minimal HUD
			Canvas(modifier = Modifier.fillMaxSize()) {
				val tick = animTick // Re-draw only Canvas on animation tick, no recomposition!
				val w = size.width
				val h = size.height

				// 1. Vertical Calibration Grid Lines (Symmetrical around w * 0.5f)
				val numIntervals = 8
				val spacing = w / numIntervals
				for (i in 1 until numIntervals) {
					val x = spacing * i
					val isCenter = i == numIntervals / 2
					drawLine(
						color = if (isCenter) colors.border.copy(alpha = 0.8f) else colors.border.copy(alpha = 0.25f),
						start = Offset(x, 0f),
						end = Offset(x, h),
						strokeWidth = if (isCenter) 1.2f else 0.8f,
					)
				}

				val pixelPerCm = (h * 0.58f / group.length.coerceAtLeast(5f)) * viewZoom
				val particles = simRuntime.simulator.particles

				if (particles.size >= 2) {
					val root = particles[0]
					// Root position moves with input translation (not fixed to center)
					val rootX = w * 0.5f + root.x * pixelPerCm
					val rootY = h * 0.16f - root.y * pixelPerCm

					// Draw connecting rods
					for (i in 1 until particles.size) {
						val prev = particles[i - 1]
						val curr = particles[i]

						val px0 = w * 0.5f + prev.x * pixelPerCm
						val py0 = h * 0.16f - prev.y * pixelPerCm
						val px1 = w * 0.5f + curr.x * pixelPerCm
						val py1 = h * 0.16f - curr.y * pixelPerCm

						// Rod (Clean line starting at previous particle/root)
						drawLine(
							color = colors.textMuted.copy(alpha = 0.8f),
							start = Offset(px0, py0),
							end = Offset(px1, py1),
							strokeWidth = 1.6f,
						)

						// Bob Circle (Live2D blue with crisp thin white outline)
						val bobCenter = Offset(px1, py1)
						drawCircle(color = Color(0xFF2B88D8), radius = 5.5f, center = bobCenter)
						drawCircle(color = Color.White, radius = 5.5f, center = bobCenter, style = Stroke(width = 1.0f))

						// Number label '1', '2'
						var labelLayout = simRuntime.cachedIndexLabels[i]
						if (labelLayout == null) {
							labelLayout = textMeasurer.measure(
								text = "$i",
								style = TextStyle(
									fontSize = 11.sp,
									fontWeight = FontWeight.SemiBold,
									fontFamily = FontFamily.SansSerif,
									color = Color(0xFF2B88D8),
								),
							)
							simRuntime.cachedIndexLabels[i] = labelLayout
						}
						drawText(
							textLayoutResult = labelLayout,
							topLeft = Offset(px1 + 8f, py1 - labelLayout.size.height * 0.5f),
						)
					}

					// Top Pivot Point: drawn at (rootX, rootY) so it moves with the root and rod!
					drawCircle(color = colors.textPrimary, radius = 4.0f, center = Offset(rootX, rootY))
				}

				// 2. HUD Readouts at Top-Left (Scale and Angle, cleanly drawn directly on Canvas)
				val scaleStr = String.format(Locale.US, "Scale: %.1f", group.outputScale)
				if (scaleStr != simRuntime.cachedScaleText) {
					simRuntime.cachedScaleText = scaleStr
					simRuntime.cachedScaleLayout = textMeasurer.measure(
						text = scaleStr,
						style = TextStyle(
							fontSize = 10.5.sp,
							fontFamily = FontFamily.Monospace,
							color = colors.textPrimary,
						),
					)
				}
				simRuntime.cachedScaleLayout?.let {
					drawText(it, topLeft = Offset(8f, 6f))
				}

				val angleStr = String.format(Locale.US, "Angle: %+.1f", -simRuntime.currentAngleDeg)
				if (angleStr != simRuntime.cachedAngleText) {
					simRuntime.cachedAngleText = angleStr
					simRuntime.cachedAngleLayout = textMeasurer.measure(
						text = angleStr,
						style = TextStyle(
							fontSize = 10.5.sp,
							fontFamily = FontFamily.Monospace,
							color = colors.textPrimary,
						),
					)
				}
				simRuntime.cachedAngleLayout?.let {
					drawText(it, topLeft = Offset(8f, 22f))
				}
			}

			// Clean minimal toolbar at bottom-left (No heavy glass card, clean DCC buttons)
			Row(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(horizontal = 8.dp, vertical = 6.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "1:1",
					style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.textMuted),
					modifier = Modifier.clickable { viewZoom = 1.0f },
				)
				Text(
					text = "⤢",
					style = TextStyle(fontSize = 12.sp, color = colors.textMuted),
					modifier = Modifier.clickable { viewZoom = 1.0f },
				)
				Text(
					text = "↶",
					style = TextStyle(fontSize = 13.sp, color = colors.textMuted),
					modifier = Modifier.clickable {
						simRuntime.simulator.setParticleAngle(1, 0f)
					},
				)
				Text(
					text = "↻",
					style = TextStyle(fontSize = 13.sp, color = colors.textMuted),
					modifier = Modifier.clickable {
						val strandCount = if (group.outputParameter == StandardParameters.EYE_BALL_FORM.raw) 3 else 2
						simRuntime.simulator.reset(
							strandCount = strandCount,
							totalLength = group.length,
							mobility = group.mobility,
							delay = group.delay,
							acceleration = group.acceleration,
						)
					},
				)
			}
		}
	}
}
