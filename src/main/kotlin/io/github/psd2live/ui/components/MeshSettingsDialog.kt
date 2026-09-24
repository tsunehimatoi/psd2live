package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.MeshEdgeMode
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.roundToInt

data class MeshSettingsDialogTarget(
	val layerId: String,
	val layerName: String,
	val currentSettings: MeshSettings,
	val defaultSettings: MeshSettings,
	val isOverridden: Boolean,
)

@Composable
fun MeshSettingsDialog(
	target: MeshSettingsDialogTarget,
	onPreview: (MeshSettings) -> Unit,
	onConfirm: (MeshSettings) -> Unit,
	onReset: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var outerMargin by remember(target) { mutableStateOf(target.currentSettings.outerMargin) }
	var edgeMode by remember(target) { mutableStateOf(target.currentSettings.edgeMode) }
	var edgeWidth by remember(target) { mutableStateOf(target.currentSettings.edgeWidth) }
	var maxEdgeDistance by remember(target) { mutableStateOf(target.currentSettings.maxEdgeDistance) }
	var interiorDensity by remember(target) { mutableStateOf(target.currentSettings.interiorDensity) }
	var fillAlgorithm by remember(target) { mutableStateOf(target.currentSettings.fillAlgorithm) }
	var suppressBoundaryDiagonals by remember(target) { mutableStateOf(target.currentSettings.suppressBoundaryDiagonals) }
	fun settings(
		outer: Float = outerMargin,
		mode: MeshEdgeMode = edgeMode,
		width: Float = edgeWidth,
		edgeDistance: Float = maxEdgeDistance,
		density: Float = interiorDensity,
		algorithm: MeshFillAlgorithm = fillAlgorithm,
		suppressDiagonals: Boolean = suppressBoundaryDiagonals,
	) = MeshSettings(outer, mode, width, edgeDistance, density, algorithm, suppressDiagonals)

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x88000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(360.dp)
				.heightIn(max = 560.dp)
				.background(colors.panelBackground, RoundedCornerShape(6.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
				.clickable(enabled = false) {}
				.verticalScroll(rememberScrollState())
				.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			// Title Bar
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					val awtColor = ComponentPalette.strong(target.layerId)
					Box(
						modifier = Modifier
							.size(8.dp)
							.background(Color(awtColor.red, awtColor.green, awtColor.blue), RoundedCornerShape(2.dp))
					)
					Text(
						text = tr("mesh.settings.title"),
						style = typography.title.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
				}
				CompactIconButton(onClick = onDismiss, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}

			// Target Layer Name
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = target.layerName,
					style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)
				Text(
					text = if (target.isOverridden) tr("mesh.settings.overridden") else tr("mesh.settings.default"),
					style = typography.caption.copy(fontSize = 9.5.sp),
					color = if (target.isOverridden) colors.accent else colors.textMuted,
				)
			}

			Text(tr("mesh.settings.shapeGroup"), style = typography.caption.copy(fontSize = 10.sp,
				fontWeight = FontWeight.Bold), color = colors.textMuted)
			CompactDropdown(
				items = MeshEdgeMode.entries,
				selectedItem = edgeMode,
				onItemSelected = { edgeMode = it; onPreview(settings(mode = it)) },
				itemLabel = { tr("mesh.settings.edgeMode.${it.name}") },
				modifier = Modifier.fillMaxWidth(),
			)
			Text(tr("mesh.settings.edgeModeHint.${edgeMode.name}"), style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted)
			// 1. Outer Margin
			if (edgeMode == MeshEdgeMode.SINGLE) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("mesh.settings.outerMargin"),
						style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)
					Text(
						text = "${"%.1f".format(outerMargin)} px",
						style = typography.monoSmall.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactSlider(
						value = outerMargin,
						onValueChange = { outerMargin = it; onPreview(settings(outer = it)) },
						valueRange = 0f..20f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = outerMargin.toDouble(),
						onValueChange = { outerMargin = it.toFloat(); onPreview(settings(outer = it.toFloat())) },
						min = 0.0,
						max = 32.0,
						step = 0.5,
						decimals = 1,
						unit = "px",
						modifier = Modifier.width(62.dp),
						height = 22.dp,
					)
				}
			}

			if (edgeMode != MeshEdgeMode.SINGLE) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(tr("mesh.settings.edgeWidth"), style = typography.body.copy(fontSize = 10.5.sp,
						fontWeight = FontWeight.Medium), color = colors.textPrimary)
					Text("${"%.1f".format(edgeWidth)} px", style = typography.monoSmall.copy(fontSize = 9.5.sp),
						color = colors.textMuted)
				}
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						CompactSlider(
							value = edgeWidth,
							onValueChange = { edgeWidth = it; onPreview(settings(width = it)) },
							valueRange = 0.5f..20f,
							modifier = Modifier.weight(1f),
						)
						CompactNumberSpinner(
							value = edgeWidth.toDouble(),
							onValueChange = { edgeWidth = it.toFloat(); onPreview(settings(width = it.toFloat())) },
							min = 0.5,
							max = 32.0,
							step = 0.5,
							decimals = 1,
							unit = "px",
							modifier = Modifier.width(62.dp),
							height = 22.dp,
						)
					}
			}

			Text(tr("mesh.settings.samplingGroup"), style = typography.caption.copy(fontSize = 10.sp,
				fontWeight = FontWeight.Bold), color = colors.textMuted)
			// 3. Max Edge Distance
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("mesh.settings.maxEdgeDistance"),
						style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)
					Text(
						text = "${maxEdgeDistance.roundToInt()} px",
						style = typography.monoSmall.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactSlider(
						value = maxEdgeDistance,
						onValueChange = { maxEdgeDistance = it; onPreview(settings(edgeDistance = it)) },
						valueRange = 6f..128f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = maxEdgeDistance.toDouble(),
						onValueChange = { maxEdgeDistance = it.toFloat(); onPreview(settings(edgeDistance = it.toFloat())) },
						min = 6.0,
						max = 128.0,
						step = 2.0,
						decimals = 0,
						unit = "px",
						modifier = Modifier.width(62.dp),
						height = 22.dp,
					)
				}
			}

			// 4. Interior Density (内部网格密度)
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("mesh.settings.interiorDensity"),
						style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)
					Text(
						text = "${interiorDensity.roundToInt()} px",
						style = typography.monoSmall.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactSlider(
						value = interiorDensity,
						onValueChange = { interiorDensity = it; onPreview(settings(density = it)) },
						valueRange = 6f..128f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = interiorDensity.toDouble(),
						onValueChange = { interiorDensity = it.toFloat(); onPreview(settings(density = it.toFloat())) },
						min = 6.0,
						max = 128.0,
						step = 2.0,
						decimals = 0,
						unit = "px",
						modifier = Modifier.width(62.dp),
						height = 22.dp,
					)
				}
			}

			Text(tr("mesh.settings.fillAlgorithm"), style = typography.caption.copy(fontSize = 10.sp,
				fontWeight = FontWeight.Bold), color = colors.textMuted)
			CompactDropdown(
				items = MeshFillAlgorithm.entries,
				selectedItem = fillAlgorithm,
				onItemSelected = { fillAlgorithm = it; onPreview(settings(algorithm = it)) },
				itemLabel = { tr("mesh.settings.fill.${it.name}") },
				modifier = Modifier.fillMaxWidth(),
			)
			Text(tr("mesh.settings.fillHint.${fillAlgorithm.name}"), style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted)
			// Topology
			Text(tr("mesh.settings.topologyGroup"), style = typography.caption.copy(fontSize = 10.sp,
				fontWeight = FontWeight.Bold), color = colors.textMuted)
			CompactCheckbox(
				checked = suppressBoundaryDiagonals,
				onCheckedChange = { suppressBoundaryDiagonals = it; onPreview(settings(suppressDiagonals = it)) },
				label = tr("mesh.settings.suppressBoundaryDiagonals"),
			)
			Text(tr("mesh.settings.topologyHint"), style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted)
			// Actions
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				if (target.isOverridden) {
					CompactButton(
						text = tr("canvas.hierarchy.resetItem"),
						onClick = onReset,
						height = 24.dp,
					)
				} else {
					Spacer(Modifier.width(1.dp))
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					CompactButton(
						text = tr("action.cancel"),
						onClick = onDismiss,
						height = 24.dp,
					)
					CompactButton(
						text = tr("action.ok"),
						onClick = {
							onConfirm(settings())
						},
						isPrimary = true,
						height = 24.dp,
					)
				}
			}
		}
	}
}

