package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

@Composable
fun RebuildMeshPromptDialog(
    layerName: String,
    onConfirmRebuild: () -> Unit,
    onKeepExisting: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.panelElevated)
                .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(8.dp))
                .clickable(enabled = false) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp, 14.dp)
                            .background(colors.accent, RoundedCornerShape(3.dp))
                    )
                    Text(
                        text = tr("editor.paint.rebuildTitle"),
                        style = typography.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "✕",
                    style = typography.caption.copy(fontSize = 14.sp),
                    color = colors.textMuted,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(4.dp),
                )
            }

            Text(
                text = tr("editor.paint.rebuildBody", layerName),
                style = typography.body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textPrimary,
            )

            Text(
                text = tr("editor.paint.rebuildHint"),
                style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                color = colors.textMuted,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                CompactButton(
                    text = tr("editor.paint.keepMesh"),
                    onClick = onKeepExisting,
                )
                CompactButton(
                    text = tr("editor.paint.rebuildMesh"),
                    onClick = onConfirmRebuild,
                    isPrimary = true,
                )
            }
        }
    }
}
