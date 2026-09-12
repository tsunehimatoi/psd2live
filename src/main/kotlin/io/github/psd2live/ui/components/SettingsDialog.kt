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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
	uiScale: Float,
	fontScale: Float,
	clickToSelectLayer: Boolean = true,
	onUiScaleChange: (Float) -> Unit,
	onFontScaleChange: (Float) -> Unit,
	onClickToSelectLayerChange: (Boolean) -> Unit = {},
	onResetDefaults: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val displayMetrics = remember { AppSettings.detectDisplayMetrics() }

	val uiScalePresets = listOf(1.0f, 1.15f, 1.25f, 1.35f, 1.50f, 1.75f, 2.00f, 2.50f)
	val fontScalePresets = listOf(
		0.90f to tr("settings.font.compact"),
		1.00f to tr("settings.font.standard"),
		1.15f to tr("settings.font.large"),
		1.30f to tr("settings.font.extraLarge"),
	)

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x99000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(560.dp)
				.background(colors.panelBackground, RoundedCornerShape(6.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
				.clickable(enabled = false) {}
				.padding(20.dp),
		) {
			// Title Bar
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = tr("dialog.settings.title"),
						style = typography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
					Text(
						text = "v0.7.0",
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
				CompactIconButton(
					onClick = onDismiss,
					size = 22.dp,
				) {
					IconClose(tint = colors.textMuted)
				}
			}

			Spacer(Modifier.height(14.dp))
			Divider(color = colors.divider, thickness = 1.dp)
			Spacer(Modifier.height(14.dp))

			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(18.dp),
			) {
				// Section 1: UI Scaling (界面缩放)
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("dialog.settings.uiScale"),
							style = typography.header.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
							color = colors.textPrimary,
						)
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Text(
								text = "${(uiScale * 100).roundToInt()}%",
								style = typography.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
								color = colors.accent,
							)
							CompactButton(
								text = tr("dialog.settings.autoScale"),
								onClick = { onUiScaleChange(displayMetrics.recommendedScale) },
								height = 20.dp,
							)
						}
					}

					// Slider
					CompactSlider(
						value = uiScale,
						onValueChange = { onUiScaleChange((it * 100).roundToInt() / 100f) },
						valueRange = 0.75f..2.50f,
						modifier = Modifier.fillMaxWidth().height(22.dp),
					)

					// Preset Buttons
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						for (preset in uiScalePresets) {
							val isSelected = kotlin.math.abs(uiScale - preset) < 0.03f
							CompactToggleChip(
								text = "${(preset * 100).toInt()}%",
								selected = isSelected,
								onToggle = { onUiScaleChange(preset) },
								modifier = Modifier.weight(1f),
							)
						}
					}
				}

				// Section 2: Font Scaling (字体缩放)
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = tr("dialog.settings.fontScale"),
							style = typography.header.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
							color = colors.textPrimary,
						)
						Text(
							text = "${(fontScale * 100).roundToInt()}%",
							style = typography.mono.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
							color = colors.accent,
						)
					}

					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						for ((scale, label) in fontScalePresets) {
							val isSelected = kotlin.math.abs(fontScale - scale) < 0.04f
							CompactToggleChip(
								text = label,
								selected = isSelected,
								onToggle = { onFontScaleChange(scale) },
								modifier = Modifier.weight(1f),
							)
						}
					}
				}

				// Live Preview Sample Box
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.panelElevated, RoundedCornerShape(4.dp))
						.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
						.padding(10.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Text(
						text = tr("dialog.settings.previewSample"),
						style = typography.caption.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						Text(
							text = "Head_FrontHair_01",
							style = typography.body.copy(fontWeight = FontWeight.Medium),
							color = colors.textPrimary,
						)
						CompactButton(
							text = tr("action.analyze"),
							onClick = {},
							isPrimary = true,
							height = 22.dp,
						)
						Text(
							text = "Z: 500  ·  4096×4096",
							style = typography.monoSmall,
							color = colors.textMuted,
						)
					}
				}

				// Section 3: Canvas Interaction
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.panelElevated, RoundedCornerShape(4.dp))
						.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
						.padding(10.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Text(
						text = tr("settings.canvas.title"),
						style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
							.clickable { onClickToSelectLayerChange(!clickToSelectLayer) }
							.padding(vertical = 2.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
							text = if (clickToSelectLayer) "✓" else " ",
							style = typography.body.copy(fontWeight = FontWeight.Bold),
							color = if (clickToSelectLayer) colors.accent else Color.Transparent,
							modifier = Modifier.width(16.dp),
						)
						Text(
							text = tr("settings.canvas.clickToSelectLayer"),
							style = typography.body.copy(fontSize = 11.5.sp),
							color = colors.textPrimary,
						)
					}
				}

				// Section 4: Display & Environment Info
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.inputBackground, RoundedCornerShape(4.dp))
						.border(BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)), RoundedCornerShape(4.dp))
						.padding(10.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Text(
						text = tr("dialog.settings.highDpiInfo"),
						style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)
					Text(
						text = tr(
							"dialog.settings.displayMetrics",
							displayMetrics.physicalWidth,
							displayMetrics.physicalHeight,
							displayMetrics.systemScalePercent,
							"${(displayMetrics.recommendedScale * 100).toInt()}%",
						),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
				}

				// Section 4: Shortcuts Guide
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "Ctrl + / Ctrl =: ${tr("menu.view.zoomIn")}  ·  Ctrl -: ${tr("menu.view.zoomOut")}  ·  Ctrl 0: ${tr("menu.view.zoomReset")}",
						style = typography.monoSmall.copy(fontSize = 10.sp),
						color = colors.textMuted,
					)
				}
			}

			Spacer(Modifier.height(14.dp))
			Divider(color = colors.divider, thickness = 1.dp)
			Spacer(Modifier.height(14.dp))

			// Bottom Actions
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(
					text = tr("dialog.settings.resetDefaults"),
					onClick = onResetDefaults,
					height = 26.dp,
				)
				CompactButton(
					text = tr("dialog.ok"),
					onClick = onDismiss,
					isPrimary = true,
					height = 26.dp,
				)
			}
		}
	}
}
