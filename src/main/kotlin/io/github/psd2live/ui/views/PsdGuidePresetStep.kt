package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceModelPresetLibrary
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.state.PsdGuideStep
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.utils.toImageBitmapFast

@Composable
internal fun PresetGuidePreview(state: PsdGuideState, source: WorkspacePsdGuide.Candidate) {
    val preset = state.preset
    val preview = remember(preset) { preset?.let { state.guide?.preview(it) } }
    if (preview != null) {
        LiveGuidePreview(preview)
        return
    }
    val colors = LocalToolColors.current
    val bitmap = remember(source.model.analysis.preview) { source.model.analysis.preview.toImageBitmapFast() }
    GuideCanvasFrame(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawGuideChecker(light = colors.checkerLight, dark = colors.checkerDark)
        }
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        if (state.busy) {
            GuideStatusCapsule(
                state.taskProgress?.text ?: tr("psdGuide.working"),
                Modifier.align(Alignment.BottomStart).padding(Spacing.Margin),
            )
        }
    }
}

@Composable
internal fun PresetGuideControls(state: PsdGuideState, source: WorkspacePsdGuide.Candidate) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val entries by WorkspaceModelPresetLibrary.shared.state.collectAsState()

    GuideNote(tr("psdGuide.step.preset.desc"))

    GuideSectionCard {
        Text(
            tr("psdGuide.preset.section.config"),
            color = colors.textPrimary,
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
        )
        ModifierPresetControls(state.selection, entries, state.preset?.model ?: source.model, !state.busy, state::selectPreset)
    }

    state.finalCandidate?.model?.let { model ->
        GuideSectionCard {
            Text(
                tr("psdGuide.preset.section.summary"),
                color = colors.textPrimary,
                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            )
            ModifierPresetContents(model)
        }
    }

    val automatic = state.bindings?.automatic.orEmpty()
    if (PsdGuideStep.BINDING !in state.steps && automatic.isNotEmpty()) {
        GuideInfoBanner(
            text = tr("psdGuide.preset.autoNotice", automatic.size),
            tone = GuideBannerTone.SUCCESS,
            actionLabel = tr("psdGuide.preset.reviewBindings"),
            onAction = state::reviewAutomaticBindings,
            enabled = !state.busy,
        )
    }
}
