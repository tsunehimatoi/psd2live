package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.utils.NativeFilePicker
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane

@Composable
fun ProjectLocationDialog(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
    if (!state.showProjectLocationDialog) return
    val source = Path.of(state.projectFile ?: state.loadedInputPath ?: state.inputPath).toAbsolutePath()
    var location by remember { mutableStateOf(if (state.projectFile == null) 1 else 0) }
    var name by remember { mutableStateOf(state.projectFile?.let { Path.of(it).fileName.toString().removeSuffix(".psd2live") } ?: source.fileName.toString().substringBeforeLast('.')) }
    var custom by remember { mutableStateOf(state.projectFile?.let { Path.of(it).parent.toString() } ?: source.parent.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val directory = when (location) { 1 -> source.parent; 2 -> ProjectArchive.installationProjectsDirectory(); else -> runCatching { Path.of(custom).toAbsolutePath() }.getOrNull() }
    val target = runCatching {
        require(name.isNotBlank() && name.none { it in "/\\:*?\"<>|" || it.isISOControl() } && name != "." && name != "..") { tr("project.invalidName") }
        requireNotNull(directory).resolve("${name.removeSuffix(".psd2live")}.psd2live")
    }
    Dialog(onDismissRequest = { if (!state.projectSaving) viewModel.cancelProjectLocation() }) {
        Surface {
            Column(Modifier.width(560.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("project.location"), style = MaterialTheme.typography.h6)
                listOf("project.custom", "project.nearPsd", "project.installation").forEachIndexed { index, key ->
                    Row { RadioButton(location == index, onClick = { location = index }, enabled = !state.projectSaving); Text(tr(key), Modifier.padding(top = 12.dp)) }
                }
                if (location == 0) Row {
                    OutlinedTextField(custom, { custom = it }, modifier = Modifier.weight(1f), label = { Text(tr("project.directory")) }, enabled = !state.projectSaving)
                    TextButton(onClick = { NativeFilePicker.chooseDirectory(null, custom)?.let { custom = it } }, enabled = !state.projectSaving) { Text(tr("project.browse")) }
                }
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("project.name")) }, enabled = !state.projectSaving)
                Text(target.getOrNull()?.toString() ?: tr("project.invalidName"))
                (error ?: state.projectSaveError)?.let { Text(it, color = MaterialTheme.colors.error) }
                if (state.projectSaving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("project.saving")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::cancelProjectLocation, enabled = !state.projectSaving) { Text(tr("project.cancel")) }
                    Button(enabled = !state.projectSaving, onClick = {
                        target.fold(onSuccess = { path ->
                            if (!Files.exists(path) || JOptionPane.showConfirmDialog(null, tr("project.overwrite", path), tr("project.save"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                                error = null; viewModel.saveProjectTo(path)
                            }
                        }, onFailure = { error = it.message })
                    }) { Text(tr("project.save")) }
                }
            }
        }
    }
}
