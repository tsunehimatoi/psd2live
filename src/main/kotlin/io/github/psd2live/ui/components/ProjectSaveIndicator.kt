package io.github.psd2live.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.ProjectSaveFeedback
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.views.ParameterTooltip

/** The status bar shares the normal compact controls, tooltip delay and theme tokens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectSaveIndicator(feedback: ProjectSaveFeedback, retryRecovery: () -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    TooltipArea(
        tooltip = { Box(Modifier.widthIn(max = 480.dp)) { ParameterTooltip(feedback.error ?: tr(feedback.key)) } },
        delayMillis = Motion.TooltipDelay,
    ) {
        if (feedback.retryRecovery) {
            CompactButton(text = tr("project.feedback.retry"), onClick = retryRecovery, height = 20.dp)
        } else {
            Text(tr(feedback.key), style = typography.caption,
                color = if (feedback.error != null) colors.error else colors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
