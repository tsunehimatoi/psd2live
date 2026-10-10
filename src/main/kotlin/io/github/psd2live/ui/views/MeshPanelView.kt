package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MeshEdgeMode
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.MeshFillParameters
import io.github.psd2live.core.MeshResolution
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshUnits
import io.github.psd2live.i18n.tr
import io.github.psd2live.core.ComponentPalette
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.MeshFillParameterControls
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * Mesh settings dock tab: global defaults when nothing is selected; per-ArtMesh override
 * (live preview + OK / Cancel / Reset) when a drawable is selected.
 */
@Composable
internal fun MeshPanelView(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
) {
	val isBusy = state.isAnalyzing || state.isGenerating
	val puppet = state.previewModel?.rig?.puppet
	val selectedLayerId = state.selectedLayerId
	val layerDrawableId = state.previewModel?.rig?.layerIdByDrawableId?.entries
		?.firstOrNull { it.value == selectedLayerId }?.key
		?: selectedLayerId
	val selectedDrawable = puppet?.drawables?.firstOrNull {
		it.id.raw == layerDrawableId || it.id.raw == selectedLayerId
	}
	val layerId = selectedLayerId?.takeIf { selectedDrawable != null }
	var showHints by remember { mutableStateOf(false) }

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(LocalToolColors.current.panelBackground),
	) {
		if (layerId == null || selectedDrawable == null) {
			GlobalMeshSettingsEditor(
				state = state,
				viewModel = viewModel,
				isBusy = isBusy,
				showHints = showHints,
				onToggleHints = { showHints = !showHints },
			)
		} else {
			SelectedMeshSettingsEditor(
				layerId = layerId,
				drawableName = selectedDrawable.name,
				isOverridden = state.meshOverrides.containsKey(layerId),
				defaultSettings = state.getDefaultMeshSettings(layerId),
				baselineSettings = state.getEffectiveMeshSettings(layerId),
				isBusy = isBusy,
				showHints = showHints,
				onToggleHints = { showHints = !showHints },
				viewModel = viewModel,
				reloadBaseline = { state.getEffectiveMeshSettings(layerId) },
				previewModel = state.previewModel,
			)
		}
	}
}

@Composable
private fun MeshPanelInfoToggle(
	showHints: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalToolColors.current
	PanelToolButton(
		label = "",
		showLabel = false,
		onClick = onToggle,
		enabled = true,
		active = showHints,
		tooltip = tr(if (showHints) "mesh.panel.hideHints" else "mesh.panel.showHints"),
	) {
		IconInfo(
			modifier = Modifier.size(11.dp),
			tint = if (showHints) colors.accent else colors.textMuted,
		)
	}
}

@Composable
private fun GlobalMeshSettingsEditor(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	isBusy: Boolean,
	showHints: Boolean,
	onToggleHints: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val settings = MeshSettings(
		outerMargin = state.meshOuterMargin,
		edgeMode = state.meshEdgeMode,
		edgeWidth = state.meshEdgeWidth,
		maxEdgeDistance = state.meshMaxEdgeDistance,
		interiorDensity = state.meshInteriorDensity,
		fillAlgorithm = state.meshFillAlgorithm,
		suppressBoundaryDiagonals = state.meshSuppressBoundaryDiagonals,
		fillParameters = state.meshFillParameters,
		wrap = state.meshWrap,
	)

	Column(modifier = Modifier.fillMaxSize()) {
		PanelToolbar(
			secondary = if (showHints) {
				{
					Text(
						text = tr("mesh.panel.globalHint"),
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
						modifier = Modifier.padding(horizontal = 4.dp),
					)
				}
			} else null,
		) {
			PanelToolbarTitle(tr("mesh.panel.globalTitle"), modifier = Modifier.padding(start = 4.dp).weight(1f))
			MeshPanelInfoToggle(showHints = showHints, onToggle = onToggleHints)
		}
		Column(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 8.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			MeshSettingsFields(
				settings = settings,
				onChange = viewModel::setGlobalMeshSettings,
				enabled = !isBusy,
				showHints = showHints,
				onGestureStart = viewModel::beginEditorGesture,
				onGestureEnd = viewModel::endEditorGesture,
				onEditStart = { viewModel.beginEditorField("setGlobalMeshSettings.$it") },
				onEditEnd = { viewModel.endEditorField("setGlobalMeshSettings.$it") },
			)
			// Which pixels count as the layer's art for its outline and mesh; a generation setting, so it lives with the mesh.
			MeshFormRow(label = tr("settings.alphaThreshold")) {
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
					modifier = Modifier.weight(1f),
					height = 22.dp,
				)
			}
			if (showHints) Text(tr("mesh.settings.alphaThresholdHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
			MeshUnitsToggle(state = state, viewModel = viewModel, enabled = !isBusy, showHints = showHints)
		}
	}
}

/** Whether mesh lengths follow the document's resolution; shows how many source pixels one unit is. */
@Composable
private fun MeshUnitsToggle(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	enabled: Boolean,
	showHints: Boolean,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val source = state.analysis?.source
	Text(
		tr("mesh.settings.unitsGroup"),
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
		color = colors.textMuted,
	)
	CompactCheckbox(
		checked = state.meshUnits == MeshUnits.DOCUMENT,
		onCheckedChange = { viewModel.setMeshUnits(if (it) MeshUnits.DOCUMENT else MeshUnits.PIXELS) },
		label = tr("mesh.settings.unitsDocument"),
		enabled = enabled,
	)
	if (showHints || (source != null && state.meshUnits == MeshUnits.DOCUMENT)) {
		val scale = source?.let { MeshResolution.unitScale(state.meshUnits, it.widthPx, it.heightPx) } ?: 1f
		Text(
			if (showHints) tr("mesh.settings.unitsHint", MeshResolution.REFERENCE_SIDE.toString())
			else tr("mesh.settings.unitsScale", String.format(java.util.Locale.ROOT, "%.2f", scale)),
			style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}
	CompactCheckbox(
		checked = state.meshTrace == io.github.psd2live.core.MeshTrace.TEXTURE,
		onCheckedChange = { viewModel.setMeshTrace(if (it) io.github.psd2live.core.MeshTrace.TEXTURE else io.github.psd2live.core.MeshTrace.CANVAS) },
		label = tr("mesh.settings.traceTexture"),
		enabled = enabled,
	)
	if (showHints) Text(tr("mesh.settings.traceHint", MeshResolution.MAX_DETAIL.toInt().toString()),
		style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
}

@Composable
private fun SelectedMeshSettingsEditor(
	layerId: String,
	drawableName: String,
	isOverridden: Boolean,
	defaultSettings: MeshSettings,
	baselineSettings: MeshSettings,
	isBusy: Boolean,
	showHints: Boolean,
	onToggleHints: () -> Unit,
	viewModel: PSD2LiveViewModel,
	reloadBaseline: () -> MeshSettings,
	previewModel: io.github.psd2live.core.RigPreviewModel?,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var draft by remember(layerId, baselineSettings) { mutableStateOf(baselineSettings) }

	DisposableEffect(layerId) {
		onDispose { viewModel.cancelPartMeshSettingsPreview(layerId) }
	}

	Column(modifier = Modifier.fillMaxSize()) {
		PanelToolbar {
			val awtColor = ComponentPalette.strong(layerId)
			Box(
				modifier = Modifier
					.padding(start = 4.dp)
					.size(8.dp)
					.background(Color(awtColor.red, awtColor.green, awtColor.blue), RoundedCornerShape(2.dp)),
			)
			PanelToolbarTitle(drawableName, modifier = Modifier.weight(1f, fill = false))
			if (isOverridden) PanelToolbarText(tr("mesh.settings.overridden"), color = colors.accent)
			Spacer(Modifier.weight(1f))
			MeshPanelInfoToggle(showHints = showHints, onToggle = onToggleHints)
			if (isOverridden) {
				PanelResetButton(
					onClick = {
						viewModel.resetPartMeshSettings(layerId)
						draft = defaultSettings
					},
					enabled = !isBusy,
					tooltip = tr("canvas.hierarchy.resetItem"),
				)
			}
		}

		Column(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 8.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			MeshSettingsFields(
				settings = draft,
				onChange = { next ->
					draft = next
					viewModel.previewPartMeshSettings(layerId, next)
				},
				enabled = !isBusy,
				showHints = showHints,
			)
		}

		// What the layer's mesh is now: its vertices, and the edge spacing in canvas units its settings give.
		val meshes = previewModel?.rig?.let { rig -> rig.puppet.drawables.filter { rig.layerIdByDrawableId[it.id.raw] == layerId } }.orEmpty()
		val unitScale = previewModel?.let { MeshResolution.unitScale(it.config, it.analysis.source) } ?: 1f
		Text(tr("mesh.settings.current", meshes.sumOf { it.mesh?.vertexCount ?: 0 }.toString(), "%.1f".format(draft.maxEdgeDistance * unitScale)),
			style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.padding(horizontal = 8.dp))
		val uncovered = remember(previewModel, layerId) { previewModel?.let { io.github.psd2live.core.MeshCoverage.uncovered(it, layerId) } }
		if (uncovered != null && uncovered >= 0.005f) Text(tr("mesh.settings.uncovered", "%.0f".format(uncovered * 100)),
			style = typography.caption.copy(fontSize = 9.sp), color = colors.warning, modifier = Modifier.padding(horizontal = 8.dp))
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 8.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			CompactButton(
				text = tr("mesh.rebuild"),
				onClick = { viewModel.rebuildLayerMesh(layerId) },
				enabled = !isBusy,
				height = 24.dp,
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactButton(
					text = tr("action.cancel"),
					onClick = {
						viewModel.cancelPartMeshSettingsPreview(layerId)
						draft = reloadBaseline()
					},
					enabled = !isBusy,
					height = 24.dp,
				)
				CompactButton(
					text = tr("action.ok"),
					onClick = { viewModel.confirmPartMeshSettingsPreview(layerId, draft) },
					isPrimary = true,
					enabled = !isBusy,
					height = 24.dp,
				)
			}
		}
	}
}

@Composable
private fun MeshSettingsFields(
	settings: MeshSettings,
	onChange: (MeshSettings) -> Unit,
	enabled: Boolean,
	showHints: Boolean,
	onGestureStart: () -> Unit = {},
	onGestureEnd: () -> Unit = {},
	onEditStart: (String) -> Unit = {},
	onEditEnd: (String) -> Unit = {},
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	fun patch(
		outer: Float = settings.outerMargin,
		mode: MeshEdgeMode = settings.edgeMode,
		width: Float = settings.edgeWidth,
		edgeDistance: Float = settings.maxEdgeDistance,
		density: Float = settings.interiorDensity,
		algorithm: MeshFillAlgorithm = settings.fillAlgorithm,
		suppressDiagonals: Boolean = settings.suppressBoundaryDiagonals,
		fill: MeshFillParameters = settings.fillParameters,
		wrap: Float = settings.wrap,
	) = onChange(MeshSettings(outer, mode, width, edgeDistance, density, algorithm, suppressDiagonals, fill, wrap))

	Text(
		tr("mesh.settings.shapeGroup"),
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
		color = colors.textMuted,
	)
	CompactDropdown(
		items = MeshEdgeMode.entries,
		selectedItem = settings.edgeMode,
		onItemSelected = { patch(mode = it) },
		itemLabel = { tr("mesh.settings.edgeMode.${it.name}") },
		enabled = enabled,
		modifier = Modifier.fillMaxWidth(),
	)
	if (showHints) {
		Text(
			tr("mesh.settings.edgeModeHint.${settings.edgeMode.name}"),
			style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}
	if (settings.edgeMode == MeshEdgeMode.SINGLE) {
		MeshFormRow(label = tr("settings.meshOuterMargin")) {
			CompactSlider(
				value = settings.outerMargin,
				onValueChange = { patch(outer = it) },
				onValueChangeStarted = onGestureStart,
				onValueChangeFinished = onGestureEnd,
				valueRange = 0f..20f,
				enabled = enabled,
				height = 14.dp,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.width(4.dp))
			CompactNumberSpinner(
				onEditStart = { onEditStart("outerMargin") },
				onEditEnd = { onEditEnd("outerMargin") },
				value = settings.outerMargin.toDouble(),
				onValueChange = { patch(outer = it.toFloat()) },
				min = 0.0,
				max = 32.0,
				step = 0.5,
				decimals = 1,
				unit = tr("settings.unit.px"),
				enabled = enabled,
				modifier = Modifier.width(62.dp),
				height = 20.dp,
			)
		}
	}
	if (settings.edgeMode != MeshEdgeMode.SINGLE) {
		MeshFormRow(label = tr("mesh.settings.edgeWidth")) {
			CompactSlider(
				value = settings.edgeWidth,
				onValueChange = { patch(width = it) },
				onValueChangeStarted = onGestureStart,
				onValueChangeFinished = onGestureEnd,
				valueRange = 0.5f..20f,
				enabled = enabled,
				height = 14.dp,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.width(4.dp))
			CompactNumberSpinner(
				onEditStart = { onEditStart("edgeWidth") },
				onEditEnd = { onEditEnd("edgeWidth") },
				value = settings.edgeWidth.toDouble(),
				onValueChange = { patch(width = it.toFloat()) },
				min = 0.5,
				max = 32.0,
				step = 0.5,
				decimals = 1,
				unit = tr("settings.unit.px"),
				enabled = enabled,
				modifier = Modifier.width(62.dp),
				height = 20.dp,
			)
		}
	}

	// Wrap topology: gaps narrower than this join the outline, so fine protrusions share one envelope.
	MeshFormRow(label = tr("mesh.settings.wrap")) {
		CompactSlider(
			value = settings.wrap,
			onValueChange = { patch(wrap = it) },
			onValueChangeStarted = onGestureStart,
			onValueChangeFinished = onGestureEnd,
			valueRange = 0f..32f,
			enabled = enabled,
			height = 14.dp,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		CompactNumberSpinner(
			onEditStart = { onEditStart("wrap") },
			onEditEnd = { onEditEnd("wrap") },
			value = settings.wrap.toDouble(),
			onValueChange = { patch(wrap = it.toFloat()) },
			min = io.github.psd2live.core.MeshWrap.range.start.toDouble(),
			max = io.github.psd2live.core.MeshWrap.range.endInclusive.toDouble(),
			step = 1.0,
			decimals = 1,
			unit = tr("settings.unit.px"),
			enabled = enabled,
			modifier = Modifier.width(62.dp),
			height = 20.dp,
		)
	}
	if (showHints) {
		Text(
			tr("mesh.settings.wrapHint"),
			style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}

	Text(
		tr("mesh.settings.samplingGroup"),
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
		color = colors.textMuted,
	)
	MeshFormRow(label = tr("settings.meshMaxEdgeDistance")) {
		CompactSlider(
			value = settings.maxEdgeDistance,
			onValueChange = { patch(edgeDistance = it) },
			onValueChangeStarted = onGestureStart,
			onValueChangeFinished = onGestureEnd,
			valueRange = 6f..128f,
			enabled = enabled,
			height = 14.dp,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		CompactNumberSpinner(
			onEditStart = { onEditStart("maxEdgeDistance") },
			onEditEnd = { onEditEnd("maxEdgeDistance") },
			value = settings.maxEdgeDistance.toDouble(),
			onValueChange = { patch(edgeDistance = it.toFloat()) },
			min = 6.0,
			max = 128.0,
			step = 2.0,
			decimals = 0,
			unit = tr("settings.unit.px"),
			enabled = enabled,
			modifier = Modifier.width(62.dp),
			height = 20.dp,
		)
	}
	MeshFormRow(label = tr("settings.meshInteriorDensity")) {
		CompactSlider(
			value = settings.interiorDensity,
			onValueChange = { patch(density = it) },
			onValueChangeStarted = onGestureStart,
			onValueChangeFinished = onGestureEnd,
			valueRange = 6f..128f,
			enabled = enabled,
			height = 14.dp,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(4.dp))
		CompactNumberSpinner(
			onEditStart = { onEditStart("interiorDensity") },
			onEditEnd = { onEditEnd("interiorDensity") },
			value = settings.interiorDensity.toDouble(),
			onValueChange = { patch(density = it.toFloat()) },
			min = 6.0,
			max = 128.0,
			step = 2.0,
			decimals = 0,
			unit = tr("settings.unit.px"),
			enabled = enabled,
			modifier = Modifier.width(62.dp),
			height = 20.dp,
		)
	}

	Text(
		tr("mesh.settings.fillAlgorithm"),
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
		color = colors.textMuted,
	)
	CompactDropdown(
		items = MeshFillAlgorithm.entries,
		selectedItem = settings.fillAlgorithm,
		onItemSelected = { patch(algorithm = it) },
		itemLabel = { tr("mesh.settings.fill.${it.name}") },
		enabled = enabled,
		modifier = Modifier.fillMaxWidth(),
	)
	if (showHints) {
		Text(
			tr("mesh.settings.fillHint.${settings.fillAlgorithm.name}"),
			style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}
	MeshFillParameterControls(
		algorithm = settings.fillAlgorithm,
		parameters = settings.fillParameters,
		onChange = { patch(fill = it) },
		enabled = enabled,
		labelWidth = 76.dp,
		showHints = showHints,
		onGestureStart = onGestureStart,
		onGestureEnd = onGestureEnd,
		onEditStart = { onEditStart("fill.$it") },
		onEditEnd = { onEditEnd("fill.$it") },
	)

	Text(
		tr("mesh.settings.topologyGroup"),
		style = typography.caption.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
		color = colors.textMuted,
	)
	CompactCheckbox(
		checked = settings.suppressBoundaryDiagonals,
		onCheckedChange = { patch(suppressDiagonals = it) },
		label = tr("mesh.settings.suppressBoundaryDiagonals"),
		enabled = enabled,
	)
	if (showHints) {
		Text(
			tr("mesh.settings.topologyHint"),
			style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted,
		)
	}
}

@Composable
private fun MeshFormRow(
	label: String,
	content: @Composable RowScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = typography.body.copy(fontSize = 10.5.sp),
			color = colors.textPrimary,
			modifier = Modifier.width(76.dp),
			textAlign = TextAlign.Right,
		)
		Spacer(Modifier.width(5.dp))
		content()
	}
}
