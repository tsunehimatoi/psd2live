package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.TextureUpscaleConfig
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** Consistent dialog matching PSD2Live compact dark IDE design system. */
@Composable
fun TextureUpscaleDialog(
	config: TextureUpscaleConfig,
	isBusy: Boolean,
	isUpscaling: Boolean = false,
	progress: Float = 0f,
	statusText: String = "",
	onDismiss: () -> Unit,
	onApply: (TextureUpscaleConfig) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var draft by remember(config) { mutableStateOf(config) }
	val localRuntime = remember { TextureUpscaleConfig.localRuntime() }
	val scrollState = rememberScrollState()
	val hasOnly2x = remember(draft.modelDirectory) {
		val p = runCatching { java.nio.file.Path.of(draft.modelDirectory) }.getOrNull()
		p != null && java.nio.file.Files.isRegularFile(p.resolve("scale2x.pth")) && !java.nio.file.Files.isRegularFile(p.resolve("scale4x.pth"))
	}

	val canApply = draft.scale == 1 || (
		draft.python.isNotBlank() &&
		draft.nunifDirectory.isNotBlank() &&
		draft.modelDirectory.isNotBlank()
	)

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x99000000))
			.clickable(enabled = !isUpscaling) { onDismiss() },
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(520.dp)
				.heightIn(max = 660.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			// Dialog Header
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
						text = tr("upscale.title"),
						style = typography.title.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
					if (isUpscaling) {
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(4.dp))
								.background(Color(0xFF1B4D3E))
								.border(BorderStroke(1.dp, Color(0xFF4EC9B0)), RoundedCornerShape(4.dp))
								.padding(horizontal = 6.dp, vertical = 2.dp),
						) {
							Text(
								text = "PROCESSING",
								style = typography.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
								color = Color(0xFF4EC9B0),
							)
						}
					}
				}
				CompactIconButton(onClick = onDismiss, enabled = !isUpscaling, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}

			// Real-time progress display when upscaling is running
			if (isUpscaling) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.background(colors.inputBackground, RoundedCornerShape(4.dp))
						.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(4.dp))
						.padding(10.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = statusText.ifBlank { tr("upscale.startingInference") },
							style = typography.caption.copy(fontSize = 11.sp),
							color = colors.accent,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = "%3d%%".format((progress * 100).toInt()),
							style = typography.monoSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
							color = colors.accent,
						)
					}
					LinearProgressIndicator(
						progress = progress,
						modifier = Modifier.fillMaxWidth().height(4.dp),
						color = colors.accent,
						backgroundColor = colors.controlBackground,
					)
				}
			}

			// Scrollable Form Body
			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.verticalScroll(scrollState),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = tr("upscale.description"),
					style = typography.body.copy(fontSize = 11.sp),
					color = colors.textMuted,
				)

				if (localRuntime != null) {
					CompactButton(
						text = tr("upscale.local"),
						isPrimary = false,
						enabled = !isBusy,
						onClick = {
							draft = draft.copy(
								python = localRuntime.python,
								nunifDirectory = localRuntime.nunifDirectory,
								modelDirectory = localRuntime.modelDirectory,
							)
						},
						modifier = Modifier.fillMaxWidth(),
					)
				}

				// Scale selector
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("upscale.scale"),
						style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
						color = colors.textPrimary,
					)
					CompactDropdown(
						items = listOf(1, 2, 4),
						selectedItem = draft.scale,
						onItemSelected = { draft = draft.copy(scale = it) },
						itemLabel = {
							if (it == 1) tr("upscale.off")
							else if (it == 4 && hasOnly2x) "4× (${tr("upscale.cascaded")})"
							else "${it}×"
						},
						enabled = !isBusy,
						modifier = Modifier.width(if (hasOnly2x) 165.dp else 130.dp),
					)
				}

				// Noise & Sharpening selector
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = tr("upscale.noiseLevel"),
							style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
							color = colors.textPrimary,
						)
						Text(
							text = tr("upscale.noiseLevelDesc"),
							style = typography.caption.copy(fontSize = 9.5.sp),
							color = colors.textMuted,
						)
					}
					Spacer(Modifier.width(8.dp))
					CompactDropdown(
						items = listOf(1, 0, 2, 3, -1),
						selectedItem = draft.noiseLevel,
						onItemSelected = { draft = draft.copy(noiseLevel = it) },
						itemLabel = {
							when (it) {
								0 -> tr("upscale.noiseLevel.0")
								1 -> tr("upscale.noiseLevel.1")
								2 -> tr("upscale.noiseLevel.2")
								3 -> tr("upscale.noiseLevel.3")
								else -> tr("upscale.noiseLevel.none")
							}
						},
						enabled = !isBusy && draft.scale > 1,
						modifier = Modifier.width(200.dp),
					)
				}

				// Form fields
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.python"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.python,
						onValueChange = { draft = draft.copy(python = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.repo"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.nunifDirectory,
						onValueChange = { draft = draft.copy(nunifDirectory = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						text = tr("upscale.model"),
						style = typography.caption.copy(fontSize = 10.5.sp),
						color = colors.textMuted,
					)
					CompactTextField(
						value = draft.modelDirectory,
						onValueChange = { draft = draft.copy(modelDirectory = it) },
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth(),
					)
				}

				// Tile size
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = tr("upscale.tile"),
						style = typography.body.copy(fontSize = 11.sp),
						color = colors.textMuted,
					)
					CompactDropdown(
						items = listOf(64, 128, 256, 512),
						selectedItem = draft.tileSize,
						onItemSelected = { draft = draft.copy(tileSize = it) },
						itemLabel = { "$it px" },
						enabled = !isBusy,
						modifier = Modifier.width(130.dp),
					)
				}

				// Neural alpha
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					CompactCheckbox(
						checked = draft.neuralAlpha,
						onCheckedChange = { draft = draft.copy(neuralAlpha = it) },
						label = tr("upscale.neuralAlpha"),
						enabled = !isBusy && draft.scale > 1,
					)
					Text(
						text = tr("upscale.neuralAlphaDesc"),
						style = typography.caption.copy(fontSize = 9.5.sp),
						color = colors.textMuted,
						modifier = Modifier.padding(start = 22.dp),
					)
				}

				Text(
					text = tr("upscale.setup"),
					style = typography.caption.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
				)
			}

			// Footer Buttons
			Row(
				modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
				horizontalArrangement = Arrangement.End,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(
					text = tr("upscale.cancel"),
					isPrimary = false,
					enabled = !isUpscaling,
					onClick = onDismiss,
				)
				Spacer(Modifier.width(8.dp))
				CompactButton(
					text = tr("upscale.apply"),
					isPrimary = true,
					enabled = !isBusy && canApply,
					onClick = {
						onApply(
							draft.copy(
								python = draft.python.trim(),
								nunifDirectory = draft.nunifDirectory.trim(),
								modelDirectory = draft.modelDirectory.trim(),
							)
						)
						onDismiss()
					},
				)
			}
		}
	}
}
