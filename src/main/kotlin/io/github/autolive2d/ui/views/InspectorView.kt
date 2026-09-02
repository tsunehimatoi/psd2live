package io.github.autolive2d.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.autolive2d.core.ClassifiedLayer
import io.github.autolive2d.core.LayerClassificationOverride
import io.github.autolive2d.core.SemanticTag
import io.github.autolive2d.core.Side
import io.github.autolive2d.i18n.tr
import io.github.autolive2d.ui.components.CompactButton
import io.github.autolive2d.ui.components.CompactCheckbox
import io.github.autolive2d.ui.components.CompactDropdown
import io.github.autolive2d.ui.components.CompactNumberSpinner
import io.github.autolive2d.ui.components.CompactSectionHeader
import io.github.autolive2d.ui.components.CompactSlider
import io.github.autolive2d.ui.components.CompactTabBar
import io.github.autolive2d.ui.components.CompactTextField
import io.github.autolive2d.ui.components.IconChevron
import io.github.autolive2d.ui.components.IconEye
import io.github.autolive2d.ui.components.IconLock
import io.github.autolive2d.ui.components.IconMouse
import io.github.autolive2d.ui.components.IconPause
import io.github.autolive2d.ui.components.IconPlay
import io.github.autolive2d.ui.components.IconReset
import io.github.autolive2d.ui.components.IconSearch
import io.github.autolive2d.ui.localizedName
import io.github.autolive2d.ui.state.AutoLive2DState
import io.github.autolive2d.ui.state.AutoLive2DViewModel
import io.github.autolive2d.ui.state.InspectorTab
import io.github.autolive2d.ui.theme.LocalToolColors
import io.github.autolive2d.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.roundToInt

@Composable
fun InspectorView(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
	onGenerate: () -> Unit = { viewModel.generateRig() },
	onChooseOutput: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	var modelSettingsExpanded by remember { mutableStateOf(true) }

	Column(
		modifier = modifier
			.fillMaxHeight()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		// 1. Output Directory & Generate/Export Row
		ExportActionSection(
			state = state,
			viewModel = viewModel,
			onGenerate = onGenerate,
			onChooseOutput = onChooseOutput,
		)

		Divider(color = colors.divider, thickness = 1.dp)

		// 2. Collapsible Model Settings Section (旧名自动绑定设置)
		ModelSettingsSection(
			state = state,
			viewModel = viewModel,
			isExpanded = modelSettingsExpanded,
			onToggleExpand = { modelSettingsExpanded = !modelSettingsExpanded },
		)

		Divider(color = colors.divider, thickness = 1.dp)

		// 3. Tabs Section: Layers & Parameters
		val inspectorTabs = listOf(
			tr("tab.layers"),
			tr("tab.parameters"),
		)
		val selectedIndex = if (state.activeInspectorTab == InspectorTab.LAYERS) 0 else 1

		CompactTabBar(
			tabs = inspectorTabs,
			selectedIndex = selectedIndex,
			onTabSelected = { index ->
				viewModel.setInspectorTab(if (index == 0) InspectorTab.LAYERS else InspectorTab.PARAMETERS)
			},
			height = 26.dp,
		)

		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			when (state.activeInspectorTab) {
				InspectorTab.LAYERS -> LayersTableView(state, viewModel)
				InspectorTab.PARAMETERS -> ParametersListView(state, viewModel)
			}
		}
	}
}

@Composable
private fun ExportActionSection(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
	onGenerate: () -> Unit,
	onChooseOutput: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		// Output Directory Row
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = tr("project.output"),
				style = typography.body.copy(fontSize = 11.sp),
				color = colors.textPrimary,
				modifier = Modifier.width(65.dp),
				textAlign = TextAlign.Right,
			)
			Spacer(Modifier.width(6.dp))
			CompactTextField(
				value = state.outputPath,
				onValueChange = { viewModel.setOutputPath(it) },
				placeholder = tr("dialog.chooseOutput"),
				modifier = Modifier.weight(1f),
				height = 22.dp,
			)
			Spacer(Modifier.width(4.dp))
			CompactButton(
				text = tr("action.choose"),
				onClick = onChooseOutput,
				enabled = !isBusy,
				height = 22.dp,
			)
		}

		// Collapsible or Compact Export Options Container
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated, RoundedCornerShape(3.dp))
				.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
				.padding(6.dp),
		) {
			// Row 1: Mesh Only
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactCheckbox(
					checked = state.meshOnly,
					onCheckedChange = { viewModel.setMeshOnly(it) },
					label = tr("export.meshOnly"),
					enabled = !isBusy,
				)
			}

			// Row 2: Motions (Collapsible, no master checkbox, disabled/grayed when meshOnly)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy && !state.meshOnly) {
						viewModel.setMotionSubExpanded(!state.motionSubExpanded)
					}
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.motionSubExpanded && !state.meshOnly,
					modifier = Modifier.size(9.dp),
					tint = if (!state.meshOnly) colors.textMuted else colors.textDisabled,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("export.motions"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = if (!state.meshOnly) colors.textPrimary else colors.textDisabled,
				)
				if (!state.motionSubExpanded || state.meshOnly) {
					Spacer(Modifier.width(6.dp))
					val activeMotions = buildList {
						if (state.motionIdle) add(tr("export.motion.idle"))
						if (state.motionBlink) add(tr("export.motion.blink"))
						if (state.motionNod) add(tr("export.motion.nod"))
						if (state.motionShake) add(tr("export.motion.shake"))
					}
					Text(
						text = if (state.meshOnly) "(${tr("export.disabled")})" else "(${activeMotions.joinToString(", ").ifEmpty { tr("export.none") }})",
						style = typography.body.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
			}

			// Motion Sub-items (expanded)
			if (state.motionSubExpanded && !state.meshOnly) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 14.dp, top = 1.dp, bottom = 2.dp),
					verticalArrangement = Arrangement.spacedBy(3.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						CompactCheckbox(
							checked = state.motionIdle,
							onCheckedChange = { viewModel.setMotionIdle(it) },
							label = tr("export.motion.idle"),
							enabled = !isBusy,
						)
						CompactCheckbox(
							checked = state.motionBlink,
							onCheckedChange = { viewModel.setMotionBlink(it) },
							label = tr("export.motion.blink"),
							enabled = !isBusy,
						)
					}
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						CompactCheckbox(
							checked = state.motionNod,
							onCheckedChange = { viewModel.setMotionNod(it) },
							label = tr("export.motion.nod"),
							enabled = !isBusy,
						)
						CompactCheckbox(
							checked = state.motionShake,
							onCheckedChange = { viewModel.setMotionShake(it) },
							label = tr("export.motion.shake"),
							enabled = !isBusy,
						)
					}
				}
			}

			// Row 3: Physics (Collapsible, no master checkbox, disabled/grayed when meshOnly)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy && !state.meshOnly) {
						viewModel.setPhysicsSubExpanded(!state.physicsSubExpanded)
					}
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.physicsSubExpanded && !state.meshOnly,
					modifier = Modifier.size(9.dp),
					tint = if (!state.meshOnly) colors.textMuted else colors.textDisabled,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("export.physics"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = if (!state.meshOnly) colors.textPrimary else colors.textDisabled,
				)
				if (!state.physicsSubExpanded || state.meshOnly) {
					Spacer(Modifier.width(6.dp))
					val activePhysics = buildList {
						if (state.physicsFrontHair) add(tr("export.physics.frontHair"))
						if (state.physicsBackHair) add(tr("export.physics.backHair"))
						if (state.physicsEyeJelly) add(tr("export.physics.eyeJelly"))
					}
					Text(
						text = if (state.meshOnly) "(${tr("export.disabled")})" else "(${activePhysics.joinToString(", ").ifEmpty { tr("export.none") }})",
						style = typography.body.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
			}

			// Physics Sub-items (expanded)
			if (state.physicsSubExpanded && !state.meshOnly) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 14.dp, top = 1.dp, bottom = 2.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					CompactCheckbox(
						checked = state.physicsFrontHair,
						onCheckedChange = { viewModel.setPhysicsFrontHair(it) },
						label = tr("export.physics.frontHair"),
						enabled = !isBusy,
					)
					CompactCheckbox(
						checked = state.physicsBackHair,
						onCheckedChange = { viewModel.setPhysicsBackHair(it) },
						label = tr("export.physics.backHair"),
						enabled = !isBusy,
					)
					CompactCheckbox(
						checked = state.physicsEyeJelly,
						onCheckedChange = { viewModel.setPhysicsEyeJelly(it) },
						label = tr("export.physics.eyeJelly"),
						enabled = !isBusy,
					)
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.5f), thickness = 0.8.dp)

			// Row 4: Generated Project Files Header (Collapsible)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy) {
						viewModel.setProjectOutputsExpanded(!state.projectOutputsExpanded)
					}
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.projectOutputsExpanded,
					modifier = Modifier.size(9.dp),
					tint = colors.textMuted,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("export.projectOutputs"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = colors.textPrimary,
				)
				if (!state.projectOutputsExpanded) {
					Spacer(Modifier.width(6.dp))
					val activeOutputs = buildList {
						if (state.exportMoc3) add("moc3")
						if (state.exportCmo3) add("cmo3")
						if (state.exportJson) add("json")
					}
					Text(
						text = "(${activeOutputs.joinToString(", ").ifEmpty { "none" }})",
						style = typography.body.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
			}

			// Project Files Sub-items (expanded)
			if (state.projectOutputsExpanded) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 14.dp, top = 1.dp, bottom = 2.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					CompactCheckbox(
						checked = state.exportMoc3,
						onCheckedChange = { viewModel.setExportMoc3(it) },
						label = tr("export.moc3"),
						enabled = !isBusy,
					)
					CompactCheckbox(
						checked = state.exportCmo3,
						onCheckedChange = { viewModel.setExportCmo3(it) },
						label = tr("export.cmo3"),
						enabled = !isBusy,
					)
					CompactCheckbox(
						checked = state.exportJson,
						onCheckedChange = { viewModel.setExportJson(it) },
						label = tr("export.json"),
						enabled = !isBusy,
					)
				}
			}
		}

		// Large Generate & Export Action Button
		CompactButton(
			text = tr("action.generate"),
			onClick = onGenerate,
			enabled = state.inputPath.isNotBlank() &&
				(state.exportCmo3 || state.exportMoc3 || state.exportJson) && !isBusy,
			isPrimary = true,
			height = 26.dp,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun ModelSettingsSection(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
	isExpanded: Boolean,
	onToggleExpand: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 6.dp, vertical = 4.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		// Header Row: Expand/Collapse Chevron + Title ("模型设置") + Reset Button
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.clickable(onClick = onToggleExpand)
				.padding(vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				IconChevron(expanded = isExpanded, modifier = Modifier.size(9.dp), tint = colors.textPrimary)
				Text(
					text = tr("settings.title"),
					style = typography.header.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)
			}
			if (isExpanded) {
				CompactButton(
					text = tr("settings.reset"),
					onClick = { viewModel.resetSettingsToDefault() },
					enabled = !isBusy,
					leadingIcon = { IconReset(tint = colors.textPrimary) },
					height = 20.dp,
				)
			}
		}

		if (isExpanded) {
			// Form Row 1: Texture Size (Atlas)
			val atlasOptions = listOf(1024, 2048, 4096, 8192, 16384)
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = tr("settings.atlasSize"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textPrimary,
					modifier = Modifier.width(85.dp),
					textAlign = TextAlign.Right,
				)
				Spacer(Modifier.width(6.dp))
				CompactDropdown(
					items = atlasOptions,
					selectedItem = state.atlasSize.takeIf { it in atlasOptions } ?: atlasOptions[2],
					onItemSelected = { viewModel.setAtlasSize(it) },
					itemLabel = { "${it} × ${it}" },
					modifier = Modifier.weight(1f),
					enabled = !isBusy,
					height = 22.dp,
				)
				Spacer(Modifier.width(4.dp))
				CompactNumberSpinner(
					value = state.atlasSize.toDouble(),
					onValueChange = { viewModel.setAtlasSize(it.toInt()) },
					min = 256.0,
					max = 16384.0,
					step = 256.0,
					decimals = 0,
					enabled = !isBusy,
					modifier = Modifier.width(65.dp),
					height = 22.dp,
				)
			}

			// Form Row 2: Mesh Spacing
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = tr("settings.meshSpacing"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textPrimary,
					modifier = Modifier.width(85.dp),
					textAlign = TextAlign.Right,
				)
				Spacer(Modifier.width(6.dp))
				CompactSlider(
					value = state.meshSpacing.toFloat(),
					onValueChange = { viewModel.setMeshSpacing(it.roundToInt()) },
					valueRange = 16f..128f,
					enabled = !isBusy,
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(4.dp))
				CompactNumberSpinner(
					value = state.meshSpacing.toDouble(),
					onValueChange = { viewModel.setMeshSpacing(it.toInt()) },
					min = 16.0,
					max = 128.0,
					step = 8.0,
					decimals = 0,
					unit = tr("settings.unit.px"),
					enabled = !isBusy,
					modifier = Modifier.width(60.dp),
					height = 22.dp,
				)
				Spacer(Modifier.width(3.dp))
				// Quick chips
				CompactButton(text = "32", onClick = { viewModel.setMeshSpacing(32) }, enabled = !isBusy, height = 20.dp)
				Spacer(Modifier.width(2.dp))
				CompactButton(text = "64", onClick = { viewModel.setMeshSpacing(64) }, enabled = !isBusy, height = 20.dp)
				Spacer(Modifier.width(2.dp))
				CompactButton(text = "96", onClick = { viewModel.setMeshSpacing(96) }, enabled = !isBusy, height = 20.dp)
			}

			// Form Row 3: Head Strength
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = tr("settings.headStrength"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textPrimary,
					modifier = Modifier.width(85.dp),
					textAlign = TextAlign.Right,
				)
				Spacer(Modifier.width(6.dp))
				CompactSlider(
					value = state.headStrength,
					onValueChange = { viewModel.setHeadStrength(it) },
					valueRange = 0.0f..4.0f,
					enabled = !isBusy,
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(4.dp))
				CompactNumberSpinner(
					value = state.headStrength.toDouble(),
					onValueChange = { viewModel.setHeadStrength(it.toFloat()) },
					min = 0.0,
					max = 4.0,
					step = 0.05,
					decimals = 2,
					unit = tr("settings.unit.x"),
					enabled = !isBusy,
					modifier = Modifier.width(60.dp),
					height = 22.dp,
				)
			}

			// Form Row 4: Body Strength
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = tr("settings.bodyStrength"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textPrimary,
					modifier = Modifier.width(85.dp),
					textAlign = TextAlign.Right,
				)
				Spacer(Modifier.width(6.dp))
				CompactSlider(
					value = state.bodyStrength,
					onValueChange = { viewModel.setBodyStrength(it) },
					valueRange = 0.0f..4.0f,
					enabled = !isBusy,
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(4.dp))
				CompactNumberSpinner(
					value = state.bodyStrength.toDouble(),
					onValueChange = { viewModel.setBodyStrength(it.toFloat()) },
					min = 0.0,
					max = 4.0,
					step = 0.05,
					decimals = 2,
					unit = tr("settings.unit.x"),
					enabled = !isBusy,
					modifier = Modifier.width(60.dp),
					height = 22.dp,
				)
			}

			// Advanced Toggle
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { viewModel.setAdvancedExpanded(!state.advancedExpanded) }
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(expanded = state.advancedExpanded, modifier = Modifier.size(9.dp), tint = colors.accent)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("settings.advanced"),
					style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.accent,
				)
			}

			if (state.advancedExpanded) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.panelElevated, RoundedCornerShape(2.dp))
						.padding(4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Text(text = tr("settings.texturePadding"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
					CompactNumberSpinner(
						value = state.texturePadding.toDouble(),
						onValueChange = { viewModel.setTexturePadding(it.toInt()) },
						min = 0.0,
						max = 32.0,
						step = 1.0,
						decimals = 0,
						unit = tr("settings.unit.px"),
						enabled = !isBusy,
						modifier = Modifier.width(55.dp),
						height = 20.dp,
					)

					Spacer(Modifier.weight(1f))

					Text(text = tr("settings.alphaThreshold"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
					CompactNumberSpinner(
						value = state.alphaThreshold.toDouble(),
						onValueChange = { viewModel.setAlphaThreshold(it.toInt()) },
						min = 0.0,
						max = 255.0,
						step = 1.0,
						decimals = 0,
						unit = tr("settings.unit.byte"),
						enabled = !isBusy,
						modifier = Modifier.width(60.dp),
						height = 20.dp,
					)
				}
			}
		}
	}
}

@Composable
private fun LayersTableView(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val analysis = state.analysis

	val layers = analysis?.layers.orEmpty()
	val recognized = layers.count { (state.layerOverrides[it.source.id.raw]?.tag ?: it.semantic.tag) != SemanticTag.UNKNOWN }
	val unknown = layers.size - recognized
	val visibleCount = state.effectiveVisibleLayerIds.size

	Column(modifier = Modifier.fillMaxSize()) {
		// Quick Actions Bar
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(26.dp)
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = if (analysis != null) tr("layers.summary", visibleCount, layers.size, recognized, unknown) else tr("layers.title"),
				style = typography.caption.copy(fontSize = 10.5.sp),
				color = colors.textMuted,
				modifier = Modifier.weight(1f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			CompactButton(
				text = tr("layers.popup.showAll"),
				onClick = { viewModel.setAllLayersVisibility(true) },
				enabled = layers.isNotEmpty(),
				height = 20.dp,
			)
			CompactButton(
				text = tr("layers.popup.hideAll"),
				onClick = { viewModel.setAllLayersVisibility(false) },
				enabled = layers.isNotEmpty(),
				height = 20.dp,
			)
			CompactButton(
				text = tr("layers.popup.invertVisibility"),
				onClick = { viewModel.invertLayerVisibility() },
				enabled = layers.isNotEmpty(),
				height = 20.dp,
			)
		}

		// Table Header Row
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(24.dp)
				.background(colors.windowBackground)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
				IconEye(visible = true, modifier = Modifier.size(13.dp), tint = colors.textMuted)
			}
			Text(text = tr("layers.header.number"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(24.dp))
			Text(text = tr("layers.header.name"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.2f))
			Text(text = tr("layers.header.semantic"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.0f))
			Text(text = tr("layers.header.side"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(0.7f))
		}

		if (layers.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = tr("canvas.preview.empty"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
				)
			}
		} else {
			LazyColumn(modifier = Modifier.fillMaxSize()) {
				itemsIndexed(layers) { index, layer ->
					val layerId = layer.source.id.raw
					val isSelected = state.selectedLayerId == layerId
					val isVisible = state.isLayerVisible(layerId, layer.source.visible)
					val override = state.layerOverrides[layerId]
					val currentTag = override?.tag ?: layer.semantic.tag
					val currentSide = override?.side ?: layer.semantic.side

					val rowBg = when {
						isSelected -> colors.selection
						index % 2 == 1 -> colors.panelElevated.copy(alpha = 0.5f)
						else -> Color.Transparent
					}

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(24.dp)
							.background(rowBg)
							.padding(horizontal = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						// Visibility Eye icon
						Box(
							modifier = Modifier
								.width(26.dp)
								.fillMaxHeight()
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.clickable { viewModel.toggleLayerVisibility(layerId) },
							contentAlignment = Alignment.Center,
						) {
							IconEye(
								visible = isVisible,
								modifier = Modifier.size(14.dp),
								tint = if (isVisible) colors.textPrimary else colors.textDisabled,
							)
						}

						// Index and Name area (clicking selects the layer)
						Row(
							modifier = Modifier
								.weight(1.2f)
								.fillMaxHeight()
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.clickable { viewModel.selectLayer(layerId) },
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "${index + 1}",
								style = typography.monoSmall.copy(fontSize = 10.sp),
								color = colors.textMuted,
								modifier = Modifier.width(24.dp),
							)
							Text(
								text = layer.source.name,
								style = typography.body.copy(fontSize = 11.sp),
								color = if (isVisible) (if (isSelected) colors.selectionText else colors.textPrimary) else colors.textDisabled,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}

						// Semantic Dropdown
						CompactDropdown(
							items = SemanticTag.entries,
							selectedItem = currentTag,
							onItemSelected = { nextTag ->
								viewModel.setLayerClassification(layerId, LayerClassificationOverride(nextTag, currentSide))
							},
							itemLabel = { it.localizedName() },
							modifier = Modifier.weight(1.0f).padding(horizontal = 2.dp),
							height = 20.dp,
						)

						// Side Dropdown
						CompactDropdown(
							items = Side.entries,
							selectedItem = currentSide,
							onItemSelected = { nextSide ->
								viewModel.setLayerClassification(layerId, LayerClassificationOverride(currentTag, nextSide))
							},
							itemLabel = { it.localizedName() },
							modifier = Modifier.weight(0.7f).padding(horizontal = 2.dp),
							height = 20.dp,
						)
					}
					Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
				}
			}
		}
	}
}

@Composable
private fun ParametersListView(
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val model = state.previewModel

	val allParameters = model?.rig?.puppet?.parameters.orEmpty()
	val query = state.parameterSearchQuery.trim().lowercase()

	val filtered = if (query.isEmpty()) {
		allParameters
	} else {
		allParameters.filter {
			it.name.lowercase().contains(query) || it.id.raw.lowercase().contains(query)
		}
	}

	Column(modifier = Modifier.fillMaxSize()) {
		// Search & Control Toolbar
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.divider))
				.padding(horizontal = 6.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			// Row 1: Search Field + Stats
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactTextField(
					value = state.parameterSearchQuery,
					onValueChange = { viewModel.setParameterSearchQuery(it) },
					placeholder = tr("parameters.search"),
					leadingIcon = { IconSearch(tint = colors.textMuted) },
					modifier = Modifier.weight(1f),
					height = 22.dp,
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = tr("parameters.count", filtered.size, allParameters.size, state.lockedParameters.size),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
			}

			// Row 2: Animation Switch + Mouse Tracking Switch + Unlock All + Reset All
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				val isAnim = state.animationEnabled
				val isMouseTracking = state.mouseTrackingEnabled

				CompactButton(
					text = if (isAnim) tr("preview.animation.pause") else tr("preview.animation.play"),
					onClick = { viewModel.setAnimationEnabled(!isAnim) },
					leadingIcon = {
						if (isAnim) IconPause(tint = colors.textPrimary) else IconPlay(tint = colors.accent)
					},
					enabled = model != null,
					height = 22.dp,
				)

				CompactButton(
					text = if (isMouseTracking) tr("preview.mouseTracking.on") else tr("preview.mouseTracking.off"),
					onClick = { viewModel.setMouseTrackingEnabled(!isMouseTracking) },
					leadingIcon = {
						IconMouse(
							active = isMouseTracking,
							tint = if (isMouseTracking) colors.accent else colors.textDisabled,
						)
					},
					enabled = model != null,
					height = 22.dp,
				)

				Spacer(Modifier.weight(1f))

				if (state.lockedParameters.isNotEmpty()) {
					CompactButton(
						text = tr("parameters.unlockAll"),
						onClick = { viewModel.unlockAllParameters() },
						leadingIcon = { IconLock(locked = false, tint = colors.textPrimary) },
						height = 22.dp,
					)
				}

				CompactButton(
					text = tr("parameters.resetAll"),
					onClick = { viewModel.resetAllParameters() },
					enabled = allParameters.isNotEmpty(),
					leadingIcon = { IconReset(tint = colors.textPrimary) },
					height = 22.dp,
				)
			}
		}

		if (allParameters.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					text = tr("parameters.empty"),
					style = typography.caption.copy(fontSize = 11.sp),
					color = colors.textMuted,
					modifier = Modifier.padding(12.dp),
				)
			}
		} else {
			LazyColumn(modifier = Modifier.fillMaxSize()) {
				items(filtered) { param ->
					ParameterRowItem(param, state, viewModel)
					Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
				}
			}
		}
	}
}

@Composable
private fun ParameterRowItem(
	param: Parameter,
	state: AutoLive2DState,
	viewModel: AutoLive2DViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	val isLocked = param.id in state.lockedParameters
	val currentValue = state.parameterValues[param.id] ?: param.default

	val valueText = if (kotlin.math.abs(currentValue) >= 10f) "%.1f".format(currentValue) else "%.2f".format(currentValue)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (isLocked) colors.selection.copy(alpha = 0.25f) else Color.Transparent)
			.padding(horizontal = 6.dp, vertical = 3.dp),
	) {
		// Row 1: Lock Toggle Icon, Name, ID, Value, Reset Button
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Dedicated Lock / Unlock Icon Button (Explicit control)
			Box(
				modifier = Modifier
					.size(18.dp)
					.background(
						if (isLocked) colors.accent.copy(alpha = 0.2f) else Color.Transparent,
						RoundedCornerShape(2.dp),
					)
					.border(
						BorderStroke(0.5.dp, if (isLocked) colors.accent else Color.Transparent),
						RoundedCornerShape(2.dp),
					)
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable { viewModel.toggleParameterLock(param.id, currentValue) },
				contentAlignment = Alignment.Center,
			) {
				IconLock(
					locked = isLocked,
					modifier = Modifier.size(11.dp),
					tint = if (isLocked) colors.accent else colors.textMuted,
				)
			}

			Spacer(Modifier.width(5.dp))

			Text(
				text = param.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = if (isLocked) FontWeight.SemiBold else FontWeight.Normal),
				color = if (isLocked) colors.selectionText else colors.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Spacer(Modifier.width(4.dp))
			Text(
				text = param.id.raw,
				style = typography.monoSmall.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = valueText,
				style = typography.mono.copy(
					fontSize = 10.5.sp,
					fontWeight = if (isLocked) FontWeight.Bold else FontWeight.Normal,
					color = if (isLocked) colors.accent else colors.textPrimary,
				),
			)
			if (isLocked || kotlin.math.abs(currentValue - param.default) > 0.001f) {
				Spacer(Modifier.width(4.dp))
				Box(
					modifier = Modifier
						.size(16.dp)
						.background(colors.controlBackground, RoundedCornerShape(2.dp))
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
						.clickable { viewModel.resetParameter(param.id) },
					contentAlignment = Alignment.Center,
				) {
					IconReset(modifier = Modifier.size(10.dp), tint = if (isLocked) colors.accent else colors.textMuted)
				}
			}
		}

		// Row 2: Min Label, Slider, Max Label
		Row(
			modifier = Modifier.fillMaxWidth().height(18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "%.0f".format(param.min),
				style = typography.monoSmall.copy(fontSize = 9.sp),
				color = colors.textMuted,
				modifier = Modifier.width(22.dp),
			)
			CompactSlider(
				value = currentValue.coerceIn(param.min, param.max),
				onValueChange = { viewModel.setParameterValue(param.id, it) },
				valueRange = param.min..param.max,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = "%.0f".format(param.max),
				style = typography.monoSmall.copy(fontSize = 9.sp),
				color = colors.textMuted,
				textAlign = TextAlign.Right,
				modifier = Modifier.width(22.dp),
			)
		}
	}
}
