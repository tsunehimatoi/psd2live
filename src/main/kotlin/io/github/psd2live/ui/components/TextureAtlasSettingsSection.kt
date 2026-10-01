package io.github.psd2live.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
internal fun TextureAtlasSettingsSection(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val isBusy = state.isAnalyzing || state.isGenerating || state.canvasEditBusy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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

    }
}
