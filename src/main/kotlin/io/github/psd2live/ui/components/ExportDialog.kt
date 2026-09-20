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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.RuntimeTarget

private val exportRuntimeTargets = listOf(
	RuntimeTarget.Cubism30,
	RuntimeTarget.Cubism33,
	RuntimeTarget.Cubism40,
	RuntimeTarget.Cubism42,
	RuntimeTarget.Cubism50,
	RuntimeTarget.Cubism53,
	RuntimeTarget.NoTarget,
)

private fun runtimeTargetLabel(target: RuntimeTarget): String = when (target) {
	RuntimeTarget.NoTarget -> tr("export.sdk.latest")
	RuntimeTarget.Ayagami -> target.displayName
	else -> target.displayName
}

@Composable
fun ExportDialog(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	onChooseOutput: () -> Unit,
	onDismiss: () -> Unit,
) {
	if (!state.showExportDialog) return
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x99000000))
			.clickable(enabled = !isBusy) { onDismiss() },
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(560.dp)
				.heightIn(max = 720.dp)
				.background(colors.panelBackground, RoundedCornerShape(8.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
				.clickable(enabled = false) {}
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = tr("dock.export"),
					style = typography.title.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
					color = colors.textPrimary,
				)
				CompactIconButton(onClick = onDismiss, enabled = !isBusy, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}

			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				ExportActionSection(
					state = state,
					viewModel = viewModel,
					onGenerate = { viewModel.generateRig() },
					onChooseOutput = onChooseOutput,
				)
			}

			if (isBusy) {
				Divider(color = colors.divider, thickness = 1.dp)
				if (state.progress > 0f) {
					LinearProgressIndicator(
						progress = state.progress.coerceIn(0f, 1f),
						modifier = Modifier.fillMaxWidth(),
						color = colors.accent,
						backgroundColor = colors.panelElevated,
					)
				}
				if (state.statusText.isNotBlank()) {
					Text(
						text = state.statusText,
						style = typography.caption.copy(fontSize = 11.sp),
						color = colors.textMuted,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
internal fun ExportActionSection(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	onGenerate: () -> Unit,
	onChooseOutput: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val isBusy = state.isAnalyzing || state.isGenerating
	val autoPpu = state.analysis?.source?.widthPx?.toFloat()?.takeIf { it > 0f }
		?: state.previewModel?.rig?.puppet?.canvasWidth?.takeIf { it > 0f }
		?: 2048f

	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated, RoundedCornerShape(3.dp))
				.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
				.padding(horizontal = 8.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			CompactCheckbox(
				checked = state.meshOnly,
				onCheckedChange = { viewModel.setMeshOnly(it) },
				label = tr("export.meshOnly"),
				enabled = !isBusy,
			)

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = tr("export.formats"),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
				)
				CompactCheckbox(
					checked = state.exportMoc3,
					onCheckedChange = { viewModel.setExportMoc3(it) },
					label = tr("export.moc3.short"),
					enabled = !isBusy,
				)
				CompactCheckbox(
					checked = state.exportCmo3,
					onCheckedChange = { viewModel.setExportCmo3(it) },
					label = tr("export.cmo3.short"),
					enabled = !isBusy,
				)
				CompactCheckbox(
					checked = state.exportJson,
					onCheckedChange = { viewModel.setExportJson(it) },
					label = tr("export.json.short"),
					enabled = !isBusy,
				)
			}
		}

		ExportLabeledRow(label = tr("export.sdkTarget")) {
			CompactDropdown(
				items = exportRuntimeTargets,
				selectedItem = state.runtimeTarget.takeIf { it in exportRuntimeTargets } ?: RuntimeTarget.Cubism50,
				onItemSelected = viewModel::setRuntimeTarget,
				itemLabel = ::runtimeTargetLabel,
				modifier = Modifier.weight(1f),
				enabled = !isBusy,
				height = 22.dp,
			)
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(colors.panelElevated, RoundedCornerShape(3.dp))
				.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(3.dp))
				.padding(horizontal = 8.dp, vertical = 6.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = tr("export.bakeOptions"),
				style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textMuted,
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				CompactCheckbox(
					checked = state.exportHiddenParts,
					onCheckedChange = viewModel::setExportHiddenParts,
					label = tr("export.hiddenParts"),
					enabled = !isBusy && state.exportMoc3,
					modifier = Modifier.weight(1f),
				)
				CompactCheckbox(
					checked = state.exportHiddenDrawables,
					onCheckedChange = viewModel::setExportHiddenDrawables,
					label = tr("export.hiddenDrawables"),
					enabled = !isBusy && state.exportMoc3,
					modifier = Modifier.weight(1f),
				)
			}
			CompactCheckbox(
				checked = state.exportGuideImageParts,
				onCheckedChange = viewModel::setExportGuideImageParts,
				label = tr("export.guideParts"),
				enabled = !isBusy && state.exportMoc3,
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				CompactCheckbox(
					checked = state.exportIncludePhysics,
					onCheckedChange = viewModel::setExportIncludePhysics,
					label = tr("export.includePhysics"),
					enabled = !isBusy && state.exportMoc3,
					modifier = Modifier.weight(1f),
				)
				CompactCheckbox(
					checked = state.exportIncludeDisplayInfo,
					onCheckedChange = viewModel::setExportIncludeDisplayInfo,
					label = tr("export.includeDisplayInfo"),
					enabled = !isBusy && state.exportMoc3,
					modifier = Modifier.weight(1f),
				)
			}
			CompactCheckbox(
				checked = state.exportIncludeUserData,
				onCheckedChange = viewModel::setExportIncludeUserData,
				label = tr("export.includeUserData"),
				enabled = !isBusy && state.exportMoc3,
			)
		}

		ExportLabeledRow(label = tr("export.pixelsPerUnit")) {
			val custom = state.exportPixelsPerUnit != null
			CompactCheckbox(
				checked = custom,
				onCheckedChange = { enabled ->
					viewModel.setExportPixelsPerUnit(if (enabled) (state.exportPixelsPerUnit ?: autoPpu) else null)
				},
				label = tr("export.pixelsPerUnit.custom"),
				enabled = !isBusy && state.exportMoc3,
			)
			Spacer(Modifier.width(8.dp))
			if (custom) {
				CompactNumberSpinner(
					value = (state.exportPixelsPerUnit ?: autoPpu).toDouble(),
					onValueChange = { viewModel.setExportPixelsPerUnit(it.toFloat()) },
					min = 1.0,
					max = 100000.0,
					step = 1.0,
					decimals = 0,
					enabled = !isBusy && state.exportMoc3,
					modifier = Modifier.width(88.dp),
					height = 22.dp,
				)
			} else {
				Text(
					text = tr("export.pixelsPerUnit.auto", autoPpu.toInt()),
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
					modifier = Modifier.weight(1f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = tr("project.output"),
				style = typography.body.copy(fontSize = 11.sp),
				color = colors.textPrimary,
				modifier = Modifier.width(88.dp),
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

		CompactButton(
			text = tr("action.generate"),
			onClick = onGenerate,
			enabled = state.inputPath.isNotBlank() &&
				(state.exportCmo3 || state.exportMoc3 || state.exportJson) && !isBusy,
			isPrimary = true,
			height = 28.dp,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

@Composable
private fun ExportLabeledRow(
	label: String,
	content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = typography.body.copy(fontSize = 11.sp),
			color = colors.textPrimary,
			modifier = Modifier.width(88.dp),
			textAlign = TextAlign.Right,
		)
		Spacer(Modifier.width(6.dp))
		content()
	}
}
