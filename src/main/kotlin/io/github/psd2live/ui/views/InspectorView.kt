package io.github.psd2live.ui.views

import io.github.psd2live.ui.components.AppMenuItem
import io.github.psd2live.ui.components.AppDropdownMenu
import io.github.psd2live.project.displayedRect
import io.github.psd2live.project.canvasRect
import io.github.psd2live.project.transform
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isAltPressed
import io.github.psd2live.ui.state.isPrimaryPressed
import io.github.psd2live.ui.state.ShortcutAction
import androidx.compose.ui.input.pointer.isShiftPressed
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
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.localizedName
import io.github.psd2live.ui.components.IconMeshWireframe
import io.github.psd2live.ui.state.referenceSelectionConversion
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import kotlin.math.roundToInt
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconPaintColorSwap
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.handCursor

@Composable
internal fun ModelPresetsSection(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating || state.workspaceEditBusy || state.editorDraftBusy

	fun expandAll(expanded: Boolean) {
		viewModel.setStrengthSubExpanded(expanded)
		viewModel.setRigTuningExpanded(expanded)
		viewModel.setRigTuningAdvancedExpanded(expanded)
		viewModel.setDynamicsSubExpanded(expanded)
		viewModel.setSimulationPresetsExpanded(expanded)
	}

	Column(modifier = Modifier.fillMaxWidth()) {
		// Open or close every group, and reset every preset.
		PanelToolbar {
			Spacer(Modifier.weight(1f))
			PanelExpandCollapseButtons(onExpandAll = { expandAll(true) }, onCollapseAll = { expandAll(false) }, enabled = !state.meshOnly)
			PanelResetButton(onClick = { viewModel.resetModelPresetsToDefault() }, enabled = !isBusy, tooltip = tr("settings.resetHint"))
		}



		// Which generated motions the model has, by group; the animation panel lists, tunes and switches each one.
		val motionGroups = listOfNotNull(
			tr("settings.motion.basic").takeIf { state.motionBasic },
			tr("settings.motion.skeleton").takeIf { state.motionSkeleton },
		)
		PresetFolderRow(
			title = tr("settings.group.motions"),
			summary = when {
				state.meshOnly -> tr("export.disabled")
				motionGroups.isEmpty() -> tr("settings.motion.none")
				else -> motionGroups.joinToString(" · ")
			},
			expanded = state.dynamicsSubExpanded,
			enabled = !isBusy && !state.meshOnly,
		) { viewModel.setDynamicsSubExpanded(!state.dynamicsSubExpanded) }

		if (state.dynamicsSubExpanded && !state.meshOnly) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 3.dp),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					CompactCheckbox(
						checked = state.motionBasic,
						onCheckedChange = viewModel::setMotionBasic,
						label = tr("settings.motion.basic"),
						enabled = !isBusy,
						modifier = Modifier.weight(1f),
					)
					CompactCheckbox(
						checked = state.motionSkeleton,
						onCheckedChange = viewModel::setMotionSkeleton,
						label = tr("settings.motion.skeleton"),
						enabled = !isBusy,
						modifier = Modifier.weight(1f),
					)
				}
				Text(
					text = tr("settings.motion.hint"),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
			}
		}

		SimulationPresetsGroup(state, viewModel)
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
	onEditStart: () -> Unit = {},
	onEditEnd: () -> Unit = {},
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var expanded by remember { mutableStateOf(false) }

	Box(modifier = modifier) {
		CompactTextField(
			value = value,
			onValueChange = onValueChange,
			placeholder = placeholder,
			onEditStart = onEditStart,
			onEditEnd = onEditEnd,
			height = height,
			enabled = enabled,
			trailingIcon = {
				Box(
					modifier = Modifier
						.size(16.dp)
						.handCursor(enabled)
						.clickable(enabled = enabled) { expanded = !expanded },
					contentAlignment = Alignment.Center,
				) {
					IconChevron(expanded = expanded, modifier = Modifier.size(8.dp), tint = colors.textMuted)
				}
			},
		)

		AppDropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			if (existingParams.isEmpty()) {
				AppMenuItem(text = tr("layers.switch.noExisting"), enabled = false, onClick = {})
			} else {
				existingParams.forEach { param ->
					AppMenuItem(text = param, isChecked = param == value, onClick = {
						onValueChange(param)
						expanded = false
					})
				}
			}
		}
	}
}

/**
 * One line on a layer's pixels: its original raster, the texture tile it renders from (and what share of the original
 * that is), where the canvas shows it, how many vertices its meshes have, and whether it is an imported image or was
 * moved as a whole. The atlas is derived from these; nothing here is hidden.
 */
private fun layerPixelSummary(state: PSD2LiveState, layerId: String): String {
	val model = state.previewModel ?: return layerId
	val source = model.analysis.source.layers.firstOrNull { it.id.raw == layerId }
	val parts = ArrayList<String>()
	if (source != null) {
		parts += tr("layers.info.original", source.raster.width, source.raster.height)
		model.atlas.placementByLayerId[layerId]?.let { tile ->
			val share = if (source.raster.width > 0) 100f * tile.width / source.raster.width else 100f
			parts += tr("layers.info.texture", tile.width, tile.height, "%.0f".format(share))
		}
		val shown = source.displayedRect() ?: source.canvasRect()
		parts += tr("layers.info.canvas", "%.0f".format(shown.width), "%.0f".format(shown.height))
	}
	val vertices = model.rig.puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == layerId }.sumOf { it.mesh?.vertexCount ?: 0 }
	if (vertices > 0) parts += tr("layers.info.vertices", vertices)
	val created = model.config.rigEdits.authoringJournal.any {
		it["op"]?.jsonPrimitive?.contentOrNull == io.github.psd2live.core.RasterMeshCreation.OP && it["layer_id"]?.jsonPrimitive?.contentOrNull == layerId
	}
	if (created) parts += tr("layers.info.created")
	if (source != null && !source.transform.isIdentity) parts += tr("layers.info.moved")
	return parts.joinToString(" · ")
}

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun LayersTableView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val analysis = state.analysis

	// Back to front, as the source lists its layers and Cubism numbers its drawing order: the stack `layer_reorder` changes.
	val layers = analysis?.layers.orEmpty().sortedByDescending { it.source.order }
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
		// Visibility for every layer at once, then what the panel lists.
		val restoreLabel = tr("layers.restoreAll", state.deletedLayerIds.size)
        val conversion = state.referenceSelectionConversion()
        val conversionLabel = tr(conversion.labelKey, conversion.layerIds.size)
		val labels = listOf(tr("layers.popup.showAll"), tr("layers.popup.hideAll"), tr("layers.popup.invertVisibility"),
			tr("layers.reorder.forward"), tr("layers.reorder.backward"), conversionLabel) +
			if (state.deletedLayerIds.isNotEmpty()) listOf(restoreLabel) else emptyList()
		// Only layers the generator has not read move in the stack; a generated layer's drawing order is the ruler's.
		val movable = viewModel.reorderableSelection(state).isNotEmpty()
		fun withKey(label: String, action: ShortcutAction) = state.keymap.labelFor(action)?.let { "$label ($it)" } ?: label
		PanelToolbar(labels = labels, iconCount = labels.size, reservedWidth = 120.dp) { labelsShown ->
			PanelToolButton(labels[0], showLabel = labelsShown > 0, onClick = { viewModel.setAllLayersVisibility(true) },
				enabled = layers.isNotEmpty(), tooltip = labels[0]) {
				IconEye(visible = true, modifier = Modifier.size(12.dp), tint = colors.textPrimary)
			}
			PanelToolButton(labels[1], showLabel = labelsShown > 1, onClick = { viewModel.setAllLayersVisibility(false) },
				enabled = layers.isNotEmpty(), tooltip = labels[1]) {
				IconEye(visible = false, modifier = Modifier.size(12.dp), tint = colors.textPrimary)
			}
			PanelToolButton(labels[2], showLabel = labelsShown > 2, onClick = { viewModel.invertLayerVisibility() },
				enabled = layers.isNotEmpty(), tooltip = labels[2]) {
				IconPaintColorSwap(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
			}
			PanelToolbarSeparator()
			PanelToolButton(labels[3], showLabel = labelsShown > 3, onClick = { viewModel.reorderSelectedLayers("forward") },
				enabled = movable, tooltip = withKey(labels[3], ShortcutAction.LAYER_FORWARD)) {
				IconArrowVertical(up = true, tint = colors.textPrimary)
			}
			PanelToolButton(labels[4], showLabel = labelsShown > 4, onClick = { viewModel.reorderSelectedLayers("backward") },
				enabled = movable, tooltip = withKey(labels[4], ShortcutAction.LAYER_BACKWARD)) {
				IconArrowVertical(up = false, tint = colors.textPrimary)
			}
            PanelToolButton(labels[5], showLabel = labelsShown > 5, onClick = { viewModel.toggleSelectedLayersReference() },
                enabled = conversion.enabled, tooltip = withKey(labels[5], ShortcutAction.LAYER_TOGGLE_REFERENCE)) {
                if (conversion.toReference) IconReferenceImage(tint = colors.textPrimary, size = 12.dp)
                else IconMeshWireframe(tint = colors.textPrimary, modifier = Modifier.size(12.dp))
            }
			if (state.deletedLayerIds.isNotEmpty()) {
				PanelToolbarSeparator()
				PanelToolButton(restoreLabel, showLabel = labelsShown > 6, onClick = { viewModel.restoreAllDeletedLayers() },
					enabled = true, tooltip = restoreLabel) {
					IconUndo(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
				}
			}
			val canvasPrefix = if (state.activeWorkspace.canvases.size > 1) "${viewModel.canvasTitle(state.activeCanvas)} · " else ""
			PanelToolbarText(
				canvasPrefix + if (analysis != null) tr("layers.summary", visibleCount, layers.size, recognized, unknown) else tr("layers.title"),
				modifier = Modifier.weight(1f),
				textAlign = TextAlign.End,
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
			Text(text = tr("layers.header.number"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(26.dp).padding(start = 2.dp))
			Text(text = tr("layers.header.name"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.0f))
			Text(text = tr("layers.header.tags"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.weight(1.3f).padding(horizontal = 2.dp))
			Text(text = tr("layers.header.variant"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(64.dp).padding(horizontal = 2.dp))
			Text(text = tr("layers.header.paramId"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.width(78.dp).padding(horizontal = 2.dp))
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
					// A classification waiting for the next generation shows over the one the rig reads.
					val override = state.layout.generation.pendingClassifications[layerId] ?: state.layerOverrides[layerId]
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
								.handCursor()
								.onPointerEvent(PointerEventType.Press) { event ->
									if (event.button != PointerButton.Primary) return@onPointerEvent
									val ordered = layers.map { it.source.id.raw }
									when {
										event.keyboardModifiers.isAltPressed ->
											viewModel.selectLayer(layerId, subtractive = true)
										event.keyboardModifiers.isShiftPressed ->
											viewModel.selectLayerRange(ordered, layerId)
										event.keyboardModifiers.isPrimaryPressed ->
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
							// What the layer's pixels are and where they go: original, texture tile, canvas, mesh.
							androidx.compose.foundation.TooltipArea(
								tooltip = {
									androidx.compose.material.Surface(color = colors.panelElevated, shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
										border = BorderStroke(1.dp, colors.border), elevation = 4.dp) {
										Text(text = layerPixelSummary(state, layerId), style = typography.caption.copy(fontSize = 10.sp),
											color = colors.textPrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
									}
								},
								delayMillis = Motion.TooltipDelay,
							) {
								Text(
									text = layer.source.name,
									style = typography.body.copy(fontSize = 11.sp),
									color = if (isVisible) (if (isSelected) colors.selectionText else colors.textPrimary) else colors.textDisabled,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							// The badge describes the layer; conversions are explicit actions in the toolbar and context menu.
							if (layerId in state.layout.referenceLayerIds) {
								io.github.psd2live.ui.views.texture.BarTooltip(tr("layers.reference.tip")) {
									Box(Modifier.padding(start = 4.dp).size(16.dp), contentAlignment = Alignment.Center) {
										IconReferenceImage(tint = colors.accent, size = 12.dp)
									}
								}
							}
							// The material this layer shows, which painting it paints.
							state.layout.layerMaterials[layerId]?.let { materialId -> state.layout.materials.item(materialId) }?.let { material ->
								MaterialLinkChip(material.name, state.layout.layerMaterials.count { it.value == material.id },
									onOpen = { viewModel.openMaterialEditor(material.id) }, onUnlink = { viewModel.unlinkMaterials(listOf(layerId)) })
							}
						}

						// 1. Semantic tags: the part and side words the generators read and the user's own (`layer_tag`).
						val currentTags = io.github.psd2live.core.LayerTags.of(currentTag, currentSide) + state.layout.layerTags[layerId].orEmpty()
						LayerTagCell(
							tags = currentTags,
							pending = layerId in state.layout.generation.pendingClassifications,
							onSet = { next -> viewModel.tagLayers(listOf(layerId), set = next) },
							modifier = Modifier.weight(1.3f).padding(horizontal = 2.dp),
						)

						// 2. Variant (none / toggle / switch): not a semantic, kept beside the tags.
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
							modifier = Modifier.width(64.dp).padding(horizontal = 2.dp),
							height = 20.dp,
						)

						// 3. The variant's parameter, and a switch's choice.
						when (currentType) {
							LayerType.PRESET -> {
								Box(
									modifier = Modifier
										.width(78.dp)
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
									onEditStart = { viewModel.beginEditorField("classification.$layerId.parameter") },
									onEditEnd = { viewModel.endEditorField("classification.$layerId.parameter") },
									modifier = Modifier.width(78.dp).padding(horizontal = 2.dp),
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
									onEditStart = { viewModel.beginEditorField("classification.$layerId.parameter") },
									onEditEnd = { viewModel.endEditorField("classification.$layerId.parameter") },
									modifier = Modifier.width(50.dp).padding(horizontal = 2.dp),
									height = 20.dp,
								)
								CompactTextField(
									value = currentSwitchId.toString(),
									onEditStart = { viewModel.beginEditorField("classification.$layerId.switch") },
									onEditEnd = { viewModel.endEditorField("classification.$layerId.switch") },
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
									modifier = Modifier.width(28.dp).padding(horizontal = 1.dp),
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
								.handCursor()
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

/** The mouth's outline: on or off, its shape, line width and colour (sampled from the mouth, or set). */
@Composable
internal fun MouthOutlineControls(state: PSD2LiveState, viewModel: PSD2LiveViewModel, isBusy: Boolean) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
					onEditStart = { viewModel.beginEditorField("mouthColor") },
					onEditEnd = { viewModel.endEditorField("mouthColor") },
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
