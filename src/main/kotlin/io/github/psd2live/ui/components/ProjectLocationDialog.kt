package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane

@Composable
fun ProjectLocationDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
    if (!state.showProjectLocationDialog) return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    val source = Path.of(state.projectFile ?: state.loadedInputPath ?: state.inputPath).toAbsolutePath()
    var location by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf(state.projectFile?.let { Path.of(it).fileName.toString().removeSuffix(".psd2live") } ?: source.fileName.toString().substringBeforeLast('.')) }
    val custom = state.projectFile?.let { Path.of(it).parent.toString() } ?: source.parent.toString()
    var error by remember { mutableStateOf<String?>(null) }
    val directory = when (location) {
        2 -> ProjectArchive.installationProjectsDirectory()
        else -> source.parent
    }
    val target = runCatching {
        require(name.isNotBlank() && name.none { it in "/\\:*?\"<>|" || it.isISOControl() } && name != "." && name != "..") { tr("project.invalidName") }
        requireNotNull(directory).resolve("${name.removeSuffix(".psd2live")}.psd2live")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(enabled = !state.projectSaving, onClick = viewModel::cancelProjectLocation),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(colors.panelBackground, RoundedCornerShape(6.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = tr("project.location"),
                        style = typography.title.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = tr("project.singleFileNotice"),
                        style = typography.caption.copy(fontSize = 10.sp),
                        color = colors.textMuted,
                    )
                }
                CompactIconButton(
                    onClick = viewModel::cancelProjectLocation,
                    enabled = !state.projectSaving,
                    size = 20.dp,
                ) {
                    IconClose(tint = colors.textMuted)
                }
            }

            // Location Radio Selection Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.inputBackground, RoundedCornerShape(4.dp))
                    .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompactRadioButton(
                    selected = location == 1,
                    onClick = { location = 1 },
                    enabled = !state.projectSaving,
                    label = tr("project.nearPsd"),
                )
                CompactRadioButton(
                    selected = location == 2,
                    onClick = { location = 2 },
                    enabled = !state.projectSaving,
                    label = tr("project.installation"),
                )
                val onChooseCustomFile = {
                    val currentTargetName = if (name.endsWith(".psd2live", ignoreCase = true)) name else "$name.psd2live"
                    val picked = NativeFilePicker.chooseSaveProjectFile(null, currentTargetName, custom)
                    if (!picked.isNullOrBlank()) {
                        val p = Path.of(picked)
                        viewModel.saveProjectTo(p)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CompactRadioButton(
                        selected = false,
                        onClick = onChooseCustomFile,
                        enabled = !state.projectSaving,
                        label = tr("project.custom"),
                    )
                    CompactButton(
                        text = tr("project.browse"),
                        onClick = onChooseCustomFile,
                        enabled = !state.projectSaving,
                        height = 22.dp,
                    )
                }
            }

            // Project Name Field
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("project.fileName"),
                    style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                CompactTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = tr("project.name"),
                    modifier = Modifier.fillMaxWidth(),
                    height = 24.dp,
                    enabled = !state.projectSaving,
                )
            }

            // Full Target Path Preview Box
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("project.targetFile"),
                    style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textMuted,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.inputBackground, RoundedCornerShape(4.dp))
                        .border(
                            BorderStroke(1.dp, if (target.isSuccess) colors.divider else colors.error),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = target.getOrNull()?.toString() ?: tr("project.invalidName"),
                        style = typography.monoSmall.copy(fontSize = 10.sp),
                        color = if (target.isSuccess) colors.textPrimary else colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Error Display
            (error ?: state.projectSaveError)?.let {
                Text(
                    text = it,
                    style = typography.caption.copy(fontSize = 10.5.sp),
                    color = colors.error,
                )
            }

            // Saving Indicator
            if (state.projectSaving) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "● " + tr("project.saving"),
                        style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = colors.accent,
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactButton(
                    text = tr("project.cancel"),
                    onClick = viewModel::cancelProjectLocation,
                    enabled = !state.projectSaving,
                    height = 24.dp,
                )
                Spacer(Modifier.width(8.dp))
                CompactButton(
                    text = tr("project.save"),
                    onClick = {
                        target.fold(
                            onSuccess = { path ->
                                if (!Files.exists(path) || JOptionPane.showConfirmDialog(null, tr("project.overwrite", path), tr("project.save"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                                    error = null
                                    viewModel.saveProjectTo(path)
                                }
                            },
                            onFailure = { error = it.message }
                        )
                    },
                    enabled = !state.projectSaving && target.isSuccess,
                    isPrimary = true,
                    height = 24.dp,
                )
            }
        }
    }
}
