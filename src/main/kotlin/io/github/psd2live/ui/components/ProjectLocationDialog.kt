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
import java.awt.Window
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The name the dialog suggests for a project saved in [directory]: [stem] unless that names an existing file or
 * [current] (the open project's file), else the first free `stem-2`, `stem-3`… (a trailing `-N` of [stem] is
 * replaced, so a copy of `name-2` is `name-3`). Save As therefore never proposes the file it would overwrite.
 */
internal fun distinctProjectName(directory: Path?, stem: String, current: Path?): String {
    val open = current?.toAbsolutePath()?.normalize()
    fun taken(name: String): Boolean {
        val path = directory?.resolve("$name.psd2live")?.toAbsolutePath()?.normalize() ?: return false
        return path == open || Files.exists(path)
    }
    if (!taken(stem)) return stem
    val base = stem.replace(Regex("-\\d+$"), "").ifEmpty { stem }
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { !taken(it) }
}

@Composable
fun ProjectLocationDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel, window: Window? = null) {
    if (!state.showProjectLocationDialog) return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    // Save As of a saved project starts beside it under a new name; a first save starts beside the source.
    val current = state.projectFile?.let { Path.of(it).toAbsolutePath().normalize() }
    val source = Path.of(state.projectFile ?: state.loadedInputPath ?: state.inputPath).toAbsolutePath()
    var location by remember { mutableStateOf(1) }
    var name by remember {
        val stem = current?.fileName?.toString()?.removeSuffix(".psd2live") ?: source.fileName.toString().substringBeforeLast('.')
        mutableStateOf(distinctProjectName(source.parent, stem, current))
    }
    val custom = current?.parent?.toString() ?: source.parent.toString()
    var customPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A target that exists waits for this in-window confirmation; it never saves over a file unasked.
    var confirm by remember { mutableStateOf<Path?>(null) }
    val directory = when (location) {
        2 -> ProjectArchive.installationProjectsDirectory()
        else -> source.parent
    }
    val target = runCatching {
        require(name.isNotBlank() && name.none { it in "/\\:*?\"<>|" || it.isISOControl() } && name != "." && name != "..") { tr("project.invalidName") }
        requireNotNull(directory).resolve("${name.removeSuffix(".psd2live")}.psd2live").toAbsolutePath().normalize()
    }
    val shown = if (location == 3) customPath?.let { Path.of(it).toAbsolutePath().normalize() } else target.getOrNull()
    val fileName = if (name.endsWith(".psd2live", ignoreCase = true)) name else "$name.psd2live"

    fun commit(path: Path) { error = null; viewModel.saveProjectTo(path) }
    fun request(path: Path) { if (Files.exists(path)) confirm = path else commit(path) }
    /** The file picked in the system save dialog; when one is open already it is raised and the dialog says so. */
    fun browse(): String? {
        val picked = NativeFilePicker.chooseSaveProjectFile(window, fileName, custom)?.takeIf(String::isNotBlank)
        if (picked != null) { customPath = picked; error = null }
        else if (NativeFilePicker.isOpen) error = tr("project.pickerOpen")
        return picked
    }
    val save = {
        if (location == 3) {
            val picked = customPath ?: browse()
            if (picked != null) request(Path.of(picked).toAbsolutePath().normalize())
        } else target.fold(onSuccess = { request(it) }, onFailure = { error = it.message })
    }
    Box(Modifier.fillMaxSize()) {
    ModalDialogFrame(
        title = tr("project.location"),
        subtitle = tr("project.singleFileNotice"),
        onDismiss = viewModel::cancelProjectLocation,
        width = 520.dp,
        dismissible = !state.projectSaving,
        bodySpacing = 12.dp,
        footer = {
            CompactButton(text = tr("project.cancel"), onClick = viewModel::cancelProjectLocation, enabled = !state.projectSaving)
            CompactButton(
                text = tr("project.save"),
                onClick = save,
                enabled = !state.projectSaving && (location == 3 || target.isSuccess),
                isPrimary = true,
            )
        },
    ) {
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
                label = tr(if (current != null) "project.nearProject" else "project.nearPsd"),
            )
            CompactRadioButton(
                selected = location == 2,
                onClick = { location = 2 },
                enabled = !state.projectSaving,
                label = tr("project.installation"),
            )
            CompactRadioButton(
                selected = location == 3,
                onClick = { location = 3 },
                enabled = !state.projectSaving,
                label = tr("project.custom"),
            )
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

        // Target Path Preview Box
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = tr("project.targetFile"),
                style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textMuted,
            )
            if (location == 3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.inputBackground, RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = customPath ?: (custom + File.separator + fileName),
                            style = typography.monoSmall.copy(fontSize = 10.sp),
                            color = if (customPath != null) colors.textPrimary else colors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CompactButton(
                        text = tr("project.browse"),
                        isPrimary = false,
                        enabled = !state.projectSaving,
                        onClick = { browse() },
                        height = 24.dp,
                    )
                }
            } else {
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
        }

        if (shown != null && shown == current) Text(
            text = tr("project.targetIsCurrent"),
            style = typography.caption.copy(fontSize = 10.5.sp),
            color = colors.warning,
        )

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
    }
    confirm?.let { path ->
        // Drawn over the location dialog in the same window, so it cannot open behind it as a system dialog could.
        ModalDialogFrame(
            title = tr("project.overwriteTitle"),
            onDismiss = { confirm = null },
            tone = ModalTone.WARNING,
            width = 460.dp,
            footer = {
                CompactButton(text = tr("project.cancel"), onClick = { confirm = null })
                CompactButton(text = tr("project.overwriteConfirm"), onClick = { confirm = null; commit(path) }, isPrimary = true, danger = true)
            },
        ) {
            ModalMessage(tr(if (path == current) "project.overwriteCurrentMessage" else "project.overwriteMessage", path))
        }
    }
    }
}
