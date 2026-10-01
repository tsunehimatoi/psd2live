package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
internal fun ModelPresetActions(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    var selectedOnly by remember { mutableStateOf(false) }
    val baking by viewModel.simulationBaking.collectAsState()
    val status by viewModel.simulationStatus.collectAsState()
    val busy = state.isAnalyzing || state.isGenerating || state.canvasEditBusy || baking != null
    val ready = state.previewModel != null && !state.meshOnly && !busy
    val hasSelection = state.selectedLayerIds.isNotEmpty() || state.selectedLayerId != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompactSectionHeader(tr("presets.simulation"))
        Text(tr("presets.simulationHint"), style = typography.caption, color = colors.textMuted)
        CompactCheckbox(selectedOnly, { selectedOnly = it }, label = tr("presets.selectedOnly"), enabled = !busy)
        for (preset in ModelPresets.Preset.entries) {
            CompactButton(tr("presets.${preset.name.lowercase()}"),
                onClick = { viewModel.applyModelPreset(preset, selectedOnly) },
                enabled = ready && (!selectedOnly || hasSelection), modifier = Modifier.fillMaxWidth())
        }
        if (baking != null) {
            LinearProgressIndicator(baking!!.overall, Modifier.fillMaxWidth(), color = colors.accent)
            CompactButton(tr("sim.cancelBake"), onClick = viewModel::cancelSimulationBake)
        }
        if (status is PSD2LiveViewModel.SimulationStatus.Failed) {
            Text((status as PSD2LiveViewModel.SimulationStatus.Failed).message, style = typography.caption, color = colors.warning)
        }
        CompactSectionHeader(tr("presets.canvasCreation"))
        Text(tr("presets.canvasHint"), style = typography.caption, color = colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(tr("presets.warpCoarse"), onClick = { viewModel.beginCanvasCreationPreset(false, 5) },
                enabled = ready, modifier = Modifier.weight(1f))
            CompactButton(tr("presets.warpFine"), onClick = { viewModel.beginCanvasCreationPreset(false, 9) },
                enabled = ready, modifier = Modifier.weight(1f))
        }
        CompactButton(tr("presets.rotation"), onClick = { viewModel.beginCanvasCreationPreset(true) },
            enabled = ready, modifier = Modifier.fillMaxWidth())
    }
}
