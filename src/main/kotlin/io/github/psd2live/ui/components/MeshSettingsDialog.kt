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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
	onConfirm: (MeshSettings) -> Unit,
	onReset: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var outerMargin by remember(target) { mutableStateOf(target.currentSettings.outerMargin) }
	var innerMarginEnabled by remember(target) { mutableStateOf(target.currentSettings.innerMarginEnabled) }
	var innerMargin by remember(target) { mutableStateOf(target.currentSettings.innerMargin) }
	var maxEdgeDistance by remember(target) { mutableStateOf(target.currentSettings.maxEdgeDistance) }
	var interiorDensity by remember(target) { mutableStateOf(target.currentSettings.interiorDensity) }

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
				.background(colors.panelBackground, RoundedCornerShape(6.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
				.clickable(enabled = false) {}
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

			// 1. Outer Margin (外边缘距离边缘距离)
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
						onValueChange = { outerMargin = it },
						valueRange = 0f..20f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = outerMargin.toDouble(),
						onValueChange = { outerMargin = it.toFloat() },
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

			// 2. Inner Margin (内边缘开关与距离边缘距离)
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					CompactCheckbox(
						checked = innerMarginEnabled,
						onCheckedChange = { innerMarginEnabled = it },
						label = tr("mesh.settings.innerMarginEnabled"),
					)
					if (innerMarginEnabled) {
						Text(
							text = "${"%.1f".format(innerMargin)} px",
							style = typography.monoSmall.copy(fontSize = 9.5.sp),
							color = colors.textMuted,
						)
					}
				}
				if (innerMarginEnabled) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						CompactSlider(
							value = innerMargin,
							onValueChange = { innerMargin = it },
							valueRange = 0.5f..20f,
							modifier = Modifier.weight(1f),
						)
						CompactNumberSpinner(
							value = innerMargin.toDouble(),
							onValueChange = { innerMargin = it.toFloat() },
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
			}

			// 3. Max Edge Distance (最大边缘点距离)
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
						onValueChange = { maxEdgeDistance = it },
						valueRange = 6f..128f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = maxEdgeDistance.toDouble(),
						onValueChange = { maxEdgeDistance = it.toFloat() },
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
						onValueChange = { interiorDensity = it },
						valueRange = 6f..128f,
						modifier = Modifier.weight(1f),
					)
					CompactNumberSpinner(
						value = interiorDensity.toDouble(),
						onValueChange = { interiorDensity = it.toFloat() },
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
							onConfirm(
								MeshSettings(
									outerMargin = outerMargin,
									innerMarginEnabled = innerMarginEnabled,
									innerMargin = innerMargin,
									maxEdgeDistance = maxEdgeDistance,
									interiorDensity = interiorDensity,
								)
							)
						},
						isPrimary = true,
						height = 24.dp,
					)
				}
			}
		}
	}
}

