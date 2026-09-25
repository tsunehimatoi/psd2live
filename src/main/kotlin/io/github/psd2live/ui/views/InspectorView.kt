package io.github.psd2live.ui.views

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.MouthLipLayer
import io.github.psd2live.core.MouthLipLayers
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.ColorPickerSwatch
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTabBar
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.ExportActionSection
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.localizedName
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.InspectorTab
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import kotlin.math.roundToInt

@Composable
fun InspectorView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	onGenerate: () -> Unit = { viewModel.generateRig() },
	onChooseOutput: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val modelSettingsExpanded = state.modelSettingsExpanded

	Column(
		modifier = modifier
			.fillMaxHeight()
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, colors.divider)),
	) {
		// 1. Collapsible Model Settings Section (模型与绑定设置)
		ModelSettingsSection(
			state = state,
			viewModel = viewModel,
			isExpanded = modelSettingsExpanded,
			onToggleExpand = { viewModel.setModelSettingsExpanded(!modelSettingsExpanded) },
		)

		Divider(color = colors.divider, thickness = 1.dp)

		// 2. Output Directory & Generate/Export Action (导出与交付)
		ExportActionSection(
			state = state,
			viewModel = viewModel,
			onGenerate = onGenerate,
			onChooseOutput = onChooseOutput,
		)

		Divider(color = colors.divider, thickness = 1.dp)

		// 3. Tabs Section: Layers, Parameters, Tool Details, Mesh, Inspector, Animation & Physics
		val inspectorTabs = listOf(
			tr("tab.layers"),
			tr("tab.parameters"),
			tr("tab.toolDetails"),
			tr("tab.mesh"),
			tr("tab.inspector"),
			tr("tab.animation"),
			tr("tab.physics"),
		)
		val selectedIndex = when (state.activeInspectorTab) {
			InspectorTab.LAYERS -> 0
			InspectorTab.PARAMETERS -> 1
			InspectorTab.TOOL_DETAILS -> 2
			InspectorTab.MESH -> 3
			InspectorTab.INSPECTOR -> 4
			InspectorTab.ANIMATION -> 5
			InspectorTab.PHYSICS -> 6
		}

		CompactTabBar(
			tabs = inspectorTabs,
			selectedIndex = selectedIndex,
			onTabSelected = { index ->
				viewModel.setInspectorTab(
					when (index) {
						0 -> InspectorTab.LAYERS
						1 -> InspectorTab.PARAMETERS
						2 -> InspectorTab.TOOL_DETAILS
						3 -> InspectorTab.MESH
						4 -> InspectorTab.INSPECTOR
						5 -> InspectorTab.ANIMATION
						6 -> InspectorTab.PHYSICS
						else -> InspectorTab.LAYERS
					}
				)
			},
			height = 26.dp,
		)

		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			when (state.activeInspectorTab) {
				InspectorTab.LAYERS -> LayersTableView(state, viewModel)
				InspectorTab.PARAMETERS -> ParametersListView(state, viewModel)
				InspectorTab.TOOL_DETAILS -> ToolDetailsView(viewModel.canvasEditor, viewModel, state)
				InspectorTab.MESH -> MeshPanelView(state, viewModel)
				InspectorTab.INSPECTOR -> InspectorPanelView(viewModel.canvasEditor, viewModel, state)
				InspectorTab.ANIMATION -> AnimationPanelView(viewModel, state)
				InspectorTab.PHYSICS -> PhysicsPanelView(viewModel, state)
			}
		}
	}
}

@Composable
private fun MotionItemWithPlay(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	label: String,
	onPlay: () -> Unit,
	enabled: Boolean,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
	) {
		CompactCheckbox(
			checked = checked,
			onCheckedChange = onCheckedChange,
			label = label,
			enabled = enabled,
			modifier = Modifier.weight(1f, fill = false),
		)
		Spacer(Modifier.width(3.dp))
		Box(
			modifier = Modifier
				.size(15.dp)
				.background(colors.panelElevated, RoundedCornerShape(2.dp))
				.border(BorderStroke(0.5.dp, colors.divider), RoundedCornerShape(2.dp))
				.clickable(enabled = enabled) { onPlay() },
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = "▶",
				fontSize = 8.sp,
				color = if (enabled) colors.accent else colors.textDisabled,
			)
		}
	}
}

@Composable
internal fun ModelSettingsSection(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	isExpanded: Boolean,
	onToggleExpand: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 6.dp, vertical = 2.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		// Header Row: Expand/Collapse Chevron + Title ("模型设置") + Reset Button
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
				.clickable(onClick = onToggleExpand)
				.padding(vertical = 1.dp),
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
					height = 19.dp,
				)
			}
		}

		if (isExpanded) {
			// Submenu 1: 贴图图集 (Texture Atlas)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy) {
						viewModel.setTextureSubExpanded(!state.textureSubExpanded)
					}
					.padding(vertical = 1.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.textureSubExpanded,
					modifier = Modifier.size(9.dp),
					tint = colors.textMuted,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("settings.group.texture"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = colors.textPrimary,
				)
				if (!state.textureSubExpanded) {
					Spacer(Modifier.width(6.dp))
					Text(
						text = "(${state.atlasSize} · ${tr("settings.texturePadding")}: ${state.texturePadding}px · α: ${state.alphaThreshold})",
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
			}

			if (state.textureSubExpanded) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 12.dp, top = 1.dp, bottom = 1.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					// Row 0: Texture Upscale — open the app-level dialog (fillMaxSize scrim
					// must not be a Column child or it collapses siblings to solid black).
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("upscale.title"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(76.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(5.dp))
						CompactButton(
							text = if (state.textureUpscale.scale == 1) tr("upscale.off") else "${state.textureUpscale.scale}× (${state.textureUpscale.tileSize}px)",
							isPrimary = state.textureUpscale.scale > 1,
							enabled = !isBusy,
							onClick = { viewModel.openTextureUpscaleDialog() },
							modifier = Modifier.weight(1f),
							height = 20.dp,
						)
					}

					val atlasOptions = listOf(1024, 2048, 4096, 8192, 16384)
					val minRequiredAtlasSize = state.minRequiredAtlasSize()
					// Row 1: Atlas Size
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("settings.atlasSize"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(76.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(5.dp))
						CompactDropdown(
							items = atlasOptions,
							selectedItem = state.atlasSize.takeIf { it in atlasOptions } ?: atlasOptions[2],
							onItemSelected = { viewModel.setAtlasSize(it) },
							itemLabel = { size ->
								if (size < minRequiredAtlasSize) "${size} × ${size} (${tr("settings.atlasTooSmall")})"
								else "${size} × ${size}"
							},
							itemEnabled = { size -> size >= minRequiredAtlasSize },
							modifier = Modifier.weight(1f),
							enabled = !isBusy,
							height = 20.dp,
						)
						Spacer(Modifier.width(4.dp))
						CompactNumberSpinner(
							onEditStart = { viewModel.beginEditorField("setAtlasSize") },
							onEditEnd = { viewModel.endEditorField("setAtlasSize") },
							value = state.atlasSize.toDouble(),
							onValueChange = { viewModel.setAtlasSize(it.toInt()) },
							min = maxOf(256.0, minRequiredAtlasSize.toDouble()),
							max = 16384.0,
							step = 256.0,
							decimals = 0,
							enabled = !isBusy,
							modifier = Modifier.width(62.dp),
							height = 20.dp,
						)
					}

					// Row 2: Texture Padding & Alpha Threshold
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("settings.texturePadding"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(76.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(5.dp))
						CompactNumberSpinner(
							onEditStart = { viewModel.beginEditorField("setTexturePadding") },
							onEditEnd = { viewModel.endEditorField("setTexturePadding") },
							value = state.texturePadding.toDouble(),
							onValueChange = { viewModel.setTexturePadding(it.toInt()) },
							min = 0.0,
							max = 32.0,
							step = 1.0,
							decimals = 0,
							unit = tr("settings.unit.px"),
							enabled = !isBusy,
							modifier = Modifier.weight(1f),
							height = 20.dp,
						)
						Spacer(Modifier.width(6.dp))
						Text(
							text = tr("settings.alphaThreshold"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(60.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(4.dp))
						CompactNumberSpinner(
							onEditStart = { viewModel.beginEditorField("setAlphaThreshold") },
							onEditEnd = { viewModel.endEditorField("setAlphaThreshold") },
							value = state.alphaThreshold.toDouble(),
							onValueChange = { viewModel.setAlphaThreshold(it.toInt()) },
							min = 0.0,
							max = 255.0,
							step = 1.0,
							decimals = 0,
							unit = tr("settings.unit.byte"),
							enabled = !isBusy,
							modifier = Modifier.width(62.dp),
							height = 20.dp,
						)
					}
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)

			// Submenu: 形变与口型 (Rigging & Facial)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy && !state.meshOnly) {
						viewModel.setStrengthSubExpanded(!state.strengthSubExpanded)
					}
					.padding(vertical = 1.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.strengthSubExpanded && !state.meshOnly,
					modifier = Modifier.size(9.dp),
					tint = if (!state.meshOnly) colors.textMuted else colors.textDisabled,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("settings.group.rigging"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = if (!state.meshOnly) colors.textPrimary else colors.textDisabled,
				)
				if (!state.strengthSubExpanded || state.meshOnly) {
					Spacer(Modifier.width(6.dp))
					val mouthSummary = if (state.mouthOutlineEnabled) " · ${tr("mouth.outline")}" else ""
					Text(
						text = if (state.meshOnly) "(${tr("export.disabled")})" else "(头: ${"%.2f".format(state.headStrength)} · 身: ${"%.2f".format(state.bodyStrength)}$mouthSummary)",
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
			}

			if (state.strengthSubExpanded && !state.meshOnly) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 12.dp, top = 1.dp, bottom = 1.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					CompactCheckbox(
						checked = state.featureDisplacementEnabled,
						onCheckedChange = viewModel::setFeatureDisplacementEnabled,
						label = tr("model.deformer.featureDisplacement"),
						enabled = !isBusy && !state.meshOnly,
					)

					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("settings.headStrength"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(76.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(5.dp))
						CompactSlider(
							value = state.headStrength,
							onValueChange = { viewModel.setHeadStrength(it) },
							onValueChangeStarted = viewModel::beginEditorGesture,
							onValueChangeFinished = viewModel::endEditorGesture,
							valueRange = 0.0f..4.0f,
							enabled = !isBusy,
							height = 14.dp,
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(4.dp))
						CompactNumberSpinner(
							onEditStart = { viewModel.beginEditorField("setHeadStrength") },
							onEditEnd = { viewModel.endEditorField("setHeadStrength") },
							value = state.headStrength.toDouble(),
							onValueChange = { viewModel.setHeadStrength(it.toFloat()) },
							min = 0.0,
							max = 4.0,
							step = 0.05,
							decimals = 2,
							unit = tr("settings.unit.x"),
							enabled = !isBusy,
							modifier = Modifier.width(60.dp),
							height = 20.dp,
						)
					}

					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("settings.bodyStrength"),
							style = typography.body.copy(fontSize = 10.5.sp),
							color = colors.textPrimary,
							modifier = Modifier.width(76.dp),
							textAlign = TextAlign.Right,
						)
						Spacer(Modifier.width(5.dp))
						CompactSlider(
							value = state.bodyStrength,
							onValueChange = { viewModel.setBodyStrength(it) },
							onValueChangeStarted = viewModel::beginEditorGesture,
							onValueChangeFinished = viewModel::endEditorGesture,
							valueRange = 0.0f..4.0f,
							enabled = !isBusy,
							height = 14.dp,
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(4.dp))
						CompactNumberSpinner(
							onEditStart = { viewModel.beginEditorField("setBodyStrength") },
							onEditEnd = { viewModel.endEditorField("setBodyStrength") },
							value = state.bodyStrength.toDouble(),
							onValueChange = { viewModel.setBodyStrength(it.toFloat()) },
							min = 0.0,
							max = 4.0,
							step = 0.05,
							decimals = 2,
							unit = tr("settings.unit.x"),
							enabled = !isBusy,
							modifier = Modifier.width(60.dp),
							height = 20.dp,
						)
					}

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 2.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						CompactCheckbox(
							checked = state.mouthOutlineEnabled,
							onCheckedChange = { viewModel.setMouthOutlineEnabled(it) },
							label = tr("mouth.outline"),
							enabled = !isBusy && !state.meshOnly,
							modifier = Modifier.weight(1f),
						)
						Box(modifier = Modifier.weight(1f)) {
							io.github.psd2live.ui.components.MouthSettingsPopupButton(
								state = state,
								enabled = !isBusy && !state.meshOnly,
								onApply = viewModel::setMouthShapeCurve,
							)
						}
					}

					if (state.mouthOutlineEnabled) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = tr("mouth.thickness"),
								style = typography.body.copy(fontSize = 10.5.sp),
								color = colors.textPrimary,
								modifier = Modifier.width(76.dp),
								textAlign = TextAlign.Right,
							)
							Spacer(Modifier.width(5.dp))
							CompactSlider(
								value = state.mouthThickness,
								onValueChange = { viewModel.setMouthThickness(it) },
								onValueChangeStarted = viewModel::beginEditorGesture,
								onValueChangeFinished = viewModel::endEditorGesture,
								valueRange = 0.5f..8.0f,
								enabled = !isBusy && !state.meshOnly,
								height = 14.dp,
								modifier = Modifier.weight(1f),
							)
							Spacer(Modifier.width(4.dp))
							CompactNumberSpinner(
								onEditStart = { viewModel.beginEditorField("setMouthThickness") },
								onEditEnd = { viewModel.endEditorField("setMouthThickness") },
								value = state.mouthThickness.toDouble(),
								onValueChange = { viewModel.setMouthThickness(it.toFloat()) },
								min = 0.5,
								max = 8.0,
								step = 0.1,
								decimals = 1,
								unit = "px",
								enabled = !isBusy && !state.meshOnly,
								modifier = Modifier.width(60.dp),
								height = 20.dp,
							)
						}

						val sampledColor = remember(state.analysis, state.alphaThreshold) {
							state.analysis?.layers?.firstOrNull {
								it.source !is MouthLipLayer &&
								it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
								it.opaquePixels > 0
							}?.let { MouthLipLayers.perimeterColor(it.source.raster, state.alphaThreshold) } ?: 0x482C32
						}
						val autoColor = state.mouthColor == null
						val rgb = state.mouthColor ?: sampledColor

						Row(
							modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							CompactCheckbox(
								checked = autoColor,
								onCheckedChange = { auto ->
									viewModel.setMouthColor(if (auto) null else (state.mouthColor ?: sampledColor))
								},
								label = tr("mouth.autoColor"),
								enabled = !isBusy && !state.meshOnly,
								modifier = Modifier.weight(1f),
							)
							ColorPickerSwatch(
								color = rgb,
								enabled = !isBusy && !state.meshOnly,
								onColorChanged = { chosenRgb ->
									viewModel.setMouthColor(chosenRgb)
								},
								sampledColor = sampledColor,
							)
							var hexInput by remember(state.mouthColor, autoColor) {
								mutableStateOf("%06X".format(state.mouthColor ?: sampledColor))
							}
							CompactTextField(
								value = if (autoColor) "%06X".format(sampledColor) else hexInput,
								onValueChange = { newHex ->
									hexInput = newHex
									val cleaned = newHex.trim().removePrefix("#")
									if (cleaned.length == 6) {
										cleaned.toIntOrNull(16)?.let { c ->
											viewModel.setMouthColor(c)
										}
									}
								},
								enabled = !isBusy && !state.meshOnly && !autoColor,
								isMono = true,
								placeholder = "#RRGGBB",
								modifier = Modifier.width(68.dp),
								height = 20.dp,
							)
						}
					}
				}
			}

			Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)

			// Submenu 4: 动态与物理 (Dynamics & Physics)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = !isBusy && !state.meshOnly) {
						viewModel.setDynamicsSubExpanded(!state.dynamicsSubExpanded)
					}
					.padding(vertical = 1.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconChevron(
					expanded = state.dynamicsSubExpanded && !state.meshOnly,
					modifier = Modifier.size(9.dp),
					tint = if (!state.meshOnly) colors.textMuted else colors.textDisabled,
				)
				Spacer(Modifier.width(4.dp))
				Text(
					text = tr("settings.group.dynamics"),
					style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
					color = if (!state.meshOnly) colors.textPrimary else colors.textDisabled,
				)
				if (!state.dynamicsSubExpanded || state.meshOnly) {
					Spacer(Modifier.width(6.dp))
					val motionCount = listOf(state.motionIdle, state.motionBlink, state.motionNod, state.motionShake).count { it }
					val physicsCount = listOf(state.physicsFrontHair, state.physicsBackHair, state.physicsEyeJelly).count { it }
					Text(
						text = if (state.meshOnly) "(${tr("export.disabled")})" else "(${tr("export.motions")}: $motionCount · ${tr("export.physics")}: $physicsCount)",
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
			}

			if (state.dynamicsSubExpanded && !state.meshOnly) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
					verticalArrangement = Arrangement.spacedBy(3.dp),
				) {
					// Motions
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						MotionItemWithPlay(
							checked = state.motionIdle,
							onCheckedChange = { viewModel.setMotionIdle(it) },
							label = tr("export.motion.idle"),
							onPlay = { viewModel.triggerMotion("Idle") },
							enabled = !isBusy,
							modifier = Modifier.weight(1f),
						)
						MotionItemWithPlay(
							checked = state.motionBlink,
							onCheckedChange = { viewModel.setMotionBlink(it) },
							label = tr("export.motion.blink"),
							onPlay = { viewModel.triggerMotion("Blink") },
							enabled = !isBusy,
							modifier = Modifier.weight(1f),
						)
					}
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						MotionItemWithPlay(
							checked = state.motionNod,
							onCheckedChange = { viewModel.setMotionNod(it) },
							label = tr("export.motion.nod"),
							onPlay = { viewModel.triggerMotion("Nod") },
							enabled = !isBusy,
							modifier = Modifier.weight(1f),
						)
						MotionItemWithPlay(
							checked = state.motionShake,
							onCheckedChange = { viewModel.setMotionShake(it) },
							label = tr("export.motion.shake"),
							onPlay = { viewModel.triggerMotion("Shake") },
							enabled = !isBusy,
							modifier = Modifier.weight(1f),
						)
					}

					Divider(color = colors.divider.copy(alpha = 0.3f), thickness = 0.5.dp)

					// Physics
					Row(
						modifier = Modifier.fillMaxWidth(),
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
			}
		}
	}
}

@Composable
private fun CompactSwitchParamField(
	value: String,
	onValueChange: (String) -> Unit,
	existingParams: List<String>,
	modifier: Modifier = Modifier,
	placeholder: String = tr("layers.param.placeholder.switch"),
	height: Dp = 20.dp,
	enabled: Boolean = true,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var expanded by remember { mutableStateOf(false) }

	Box(modifier = modifier) {
		CompactTextField(
			value = value,
			onValueChange = onValueChange,
			placeholder = placeholder,
			height = height,
			enabled = enabled,
			trailingIcon = {
				Box(
					modifier = Modifier
						.size(16.dp)
						.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
						.clickable(enabled = enabled) { expanded = !expanded },
					contentAlignment = Alignment.Center,
				) {
					IconChevron(expanded = expanded, modifier = Modifier.size(8.dp), tint = colors.textMuted)
				}
			},
		)

		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			modifier = Modifier
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.border)),
		) {
			if (existingParams.isEmpty()) {
				DropdownMenuItem(enabled = false, onClick = {}) {
					Text(
						text = tr("layers.switch.noExisting"),
						style = typography.caption.copy(fontSize = 11.sp),
						color = colors.textMuted,
					)
				}
			} else {
				existingParams.forEach { param ->
					DropdownMenuItem(
						onClick = {
							onValueChange(param)
							expanded = false
						},
					) {
						Text(
							text = param,
							style = typography.body.copy(fontSize = 11.5.sp),
							color = if (param == value) colors.accent else colors.textPrimary,
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LayersTableView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val analysis = state.analysis

	val layers = analysis?.layers.orEmpty()
	val recognized = layers.count { layer ->
		val override = state.layerOverrides[layer.source.id.raw]
		val type = override?.type ?: layer.semantic.type
		if (type != LayerType.PRESET) true
		else (override?.tag ?: layer.semantic.tag) != SemanticTag.UNKNOWN
	}
	val unknown = layers.size - recognized
	val visibleCount = state.effectiveVisibleLayerIds.size

	val existingSwitchParams = remember(state.layerOverrides, layers) {
		val fromOverrides = state.layerOverrides.values
			.filter { it.type == LayerType.SWITCH && it.parameter.isNotBlank() }
			.map { it.parameter.trim() }
		val fromLayers = layers
			.filter { it.semantic.type == LayerType.SWITCH && it.semantic.parameter.isNotBlank() }
			.map { it.semantic.parameter.trim() }
		(fromOverrides + fromLayers).distinct().sorted()
	}

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
			val canvasPrefix = if (state.activeWorkspace.canvases.size > 1) "${viewModel.canvasTitle(state.activeCanvas)} · " else ""
			Text(
				text = canvasPrefix + if (analysis != null) tr("layers.summary", visibleCount, layers.size, recognized, unknown) else tr("layers.title"),
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
			if (state.deletedLayerIds.isNotEmpty()) {
				CompactButton(
					text = tr("layers.restoreAll", state.deletedLayerIds.size),
					onClick = { viewModel.restoreAllDeletedLayers() },
					height = 20.dp,
				)
			}
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
			Text(text = tr("layers.header.number"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(26.dp).padding(start = 2.dp))
			Text(text = tr("layers.header.name"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.0f))
			Text(text = tr("layers.header.type"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(76.dp).padding(horizontal = 2.dp))
			Text(text = tr("layers.header.binding"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.1f).padding(horizontal = 2.dp))
			Text(text = tr("layers.header.paramId"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(52.dp).padding(horizontal = 2.dp))
			Spacer(Modifier.width(22.dp))
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
			val layerListState = rememberLazyListState()
			val tutorialRowIndex = (layerListState.firstVisibleItemIndex +
				if (layerListState.firstVisibleItemScrollOffset > 0) 1 else 0).coerceAtMost(layers.lastIndex)
			LazyColumn(state = layerListState, modifier = Modifier.fillMaxSize()) {
				itemsIndexed(layers) { index, layer ->
					val layerId = layer.source.id.raw
					val isSelected = state.selectedLayerId == layerId || layerId in state.selectedLayerIds
					val isVisible = state.isLayerVisible(layerId, layer.source.visible)
					val override = state.layerOverrides[layerId]
					val currentType = override?.type ?: layer.semantic.type
					val currentTag = override?.tag ?: layer.semantic.tag
					val currentSide = override?.side ?: layer.semantic.side
					val currentParam = override?.parameter ?: layer.semantic.parameter
					val currentSwitchId = override?.switchId ?: layer.semantic.switchId

					val rowBg = when {
						isSelected -> colors.selection
						index % 2 == 1 -> colors.panelElevated.copy(alpha = 0.5f)
						else -> Color.Transparent
					}

					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(26.dp)
							.background(rowBg)
							.then(if (index == tutorialRowIndex) Modifier.tutorialTarget(TutorialTargetId.LAYER_ROW) else Modifier)
							.padding(horizontal = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						// Index and Name area (clicking selects the layer)
						Row(
							modifier = Modifier
								.weight(1.0f)
								.fillMaxHeight()
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.onPointerEvent(PointerEventType.Press) { event ->
									if (event.button != PointerButton.Primary) return@onPointerEvent
									val ordered = layers.map { it.source.id.raw }
									when {
										event.keyboardModifiers.isAltPressed ->
											viewModel.selectLayer(layerId, subtractive = true)
										event.keyboardModifiers.isShiftPressed ->
											viewModel.selectLayerRange(ordered, layerId)
										event.keyboardModifiers.isCtrlPressed ->
											viewModel.toggleLayerSelection(layerId)
										else -> viewModel.selectLayer(layerId)
									}
								},
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "${index + 1}",
								style = typography.monoSmall.copy(fontSize = 10.sp),
								color = colors.textMuted,
								modifier = Modifier.width(26.dp).padding(start = 2.dp),
							)
							Text(
								text = layer.source.name,
								style = typography.body.copy(fontSize = 11.sp),
								color = if (isVisible) (if (isSelected) colors.selectionText else colors.textPrimary) else colors.textDisabled,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}

						// 1. Type Dropdown (预设 / 开关差分 / 切换差分)
						CompactDropdown(
							items = LayerType.entries,
							selectedItem = currentType,
							onItemSelected = { nextType ->
								viewModel.setLayerClassification(
									layerId,
									LayerClassificationOverride(
										type = nextType,
										tag = currentTag,
										side = currentSide,
										parameter = currentParam,
										switchId = currentSwitchId,
									),
								)
							},
							itemLabel = { it.localizedName() },
							modifier = Modifier.width(76.dp).padding(horizontal = 2.dp),
							height = 20.dp,
						)

						// 2. Parameter Binding (参数关联 / 预设部件)
						when (currentType) {
							LayerType.PRESET -> {
								CompactDropdown(
									items = SemanticTag.entries,
									selectedItem = currentTag,
									onItemSelected = { nextTag ->
										viewModel.setLayerClassification(
											layerId,
											LayerClassificationOverride(
												type = LayerType.PRESET,
												tag = nextTag,
												side = currentSide,
												parameter = currentParam,
												switchId = currentSwitchId,
											),
										)
									},
									itemLabel = { it.localizedName() },
									modifier = Modifier.weight(1.1f).padding(horizontal = 2.dp),
									height = 20.dp,
								)
							}
							LayerType.TOGGLE -> {
								CompactTextField(
									value = currentParam,
									onValueChange = { nextParam ->
										viewModel.setLayerClassification(
											layerId,
											LayerClassificationOverride(
												type = LayerType.TOGGLE,
												tag = currentTag,
												side = currentSide,
												parameter = nextParam,
												switchId = currentSwitchId,
											),
										)
									},
									placeholder = tr("layers.param.placeholder.toggle"),
									modifier = Modifier.weight(1.1f).padding(horizontal = 2.dp),
									height = 20.dp,
								)
							}
							LayerType.SWITCH -> {
								CompactSwitchParamField(
									value = currentParam,
									onValueChange = { nextParam ->
										viewModel.setLayerClassification(
											layerId,
											LayerClassificationOverride(
												type = LayerType.SWITCH,
												tag = currentTag,
												side = currentSide,
												parameter = nextParam,
												switchId = currentSwitchId,
											),
										)
									},
									existingParams = existingSwitchParams,
									modifier = Modifier.weight(1.1f).padding(horizontal = 2.dp),
									height = 20.dp,
								)
							}
						}

						// 3. Associated ID / Side (关联 ID / 侧别)
						when (currentType) {
							LayerType.PRESET -> {
								CompactDropdown(
									items = Side.entries,
									selectedItem = currentSide,
									onItemSelected = { nextSide ->
										viewModel.setLayerClassification(
											layerId,
											LayerClassificationOverride(
												type = LayerType.PRESET,
												tag = currentTag,
												side = nextSide,
												parameter = currentParam,
												switchId = currentSwitchId,
											),
										)
									},
									itemLabel = { it.localizedName() },
									modifier = Modifier.width(52.dp).padding(horizontal = 2.dp),
									height = 20.dp,
								)
							}
							LayerType.TOGGLE -> {
								Box(
									modifier = Modifier
										.width(52.dp)
										.height(20.dp)
										.padding(horizontal = 2.dp),
									contentAlignment = Alignment.Center,
								) {
									Text(
										text = "-",
										style = typography.caption.copy(fontSize = 11.sp),
										color = colors.textDisabled,
									)
								}
							}
							LayerType.SWITCH -> {
								CompactTextField(
									value = currentSwitchId.toString(),
									onValueChange = { input ->
										val parsed = input.filter { it.isDigit() }.toIntOrNull() ?: 0
										viewModel.setLayerClassification(
											layerId,
											LayerClassificationOverride(
												type = LayerType.SWITCH,
												tag = currentTag,
												side = currentSide,
												parameter = currentParam,
												switchId = parsed,
											),
										)
									},
									modifier = Modifier.width(52.dp).padding(horizontal = 2.dp),
									height = 20.dp,
									isMono = true,
								)
							}
						}

						Spacer(Modifier.width(2.dp))
						// Delete layer button
						Box(
							modifier = Modifier
								.size(20.dp)
								.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
								.clickable { viewModel.deleteLayer(layerId) }
								.padding(2.dp),
							contentAlignment = Alignment.Center,
						) {
							IconTrash(
								modifier = Modifier.size(11.5.dp),
								tint = colors.textMuted,
							)
						}
					}
					Divider(color = colors.divider.copy(alpha = 0.4f), thickness = 0.5.dp)
				}
			}
		}
	}
}
