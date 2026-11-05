package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspaceModelPresetLibrary
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path

/** Preset-library operations change preferences; applying a preset still uses the document command. */
@Composable
internal fun PresetLibraryControls(state: PSD2LiveState, selectedId: String?, enabled: Boolean, onSaved: (String) -> Unit = {}) {
    val library = WorkspaceModelPresetLibrary.shared
    val entries by library.state.collectAsState()
    var chosenId by remember { mutableStateOf(selectedId) }
    val chosen = entries.firstOrNull { it.id == chosenId } ?: entries.firstOrNull()
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val model = state.previewModel
    fun task(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
            finally { busy = false }
        }
    }
    fun save() {
        val raw = state.rigEdits.modifierStack
        val capturedName = name.trim()
        if (capturedName.isNotEmpty() && raw != null && model != null) task {
            val saved = withContext(Dispatchers.IO) { library.save(capturedName, raw, model) }
            naming = false; chosenId = saved.id; onSaved(saved.id)
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (naming) {
                CompactTextField(name, { name = it }, modifier = Modifier.width(160.dp), selectAllOnFocus = true,
                    placeholder = tr("modifier.library.name"))
                CompactButton(tr("modifier.library.save"), ::save, enabled = !busy && name.isNotBlank())
                CompactButton(tr("action.cancel"), { naming = false })
            } else Box {
                CompactButton(tr("modifier.library.manage"), { menu = true }, enabled = enabled && !busy)
                TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (chosen != null) CompactDropdown(entries, chosen, { chosenId = it.id }, modifier = Modifier.width(220.dp),
                        itemLabel = { it.nameKey?.let { key -> tr(key) } ?: it.name })
                    CompactMenuDivider()
                    CompactMenuItem(tr("modifier.library.save"), { menu = false; naming = true; name = "" },
                        enabled = state.rigEdits.modifierStack != null && model != null)
                    CompactMenuItem(tr("modifier.library.import"), {
                        menu = false
                        task {
                            val path = withContext(Dispatchers.IO) { NativeFilePicker.chooseJsonFile(title = tr("modifier.library.import")) } ?: return@task
                            val saved = withContext(Dispatchers.IO) { library.importJson(Files.readString(Path.of(path))) }
                            chosenId = saved.id; onSaved(saved.id)
                        }
                    })
                    CompactMenuItem(tr("modifier.library.export"), {
                        menu = false
                        val id = chosen?.id ?: return@CompactMenuItem
                        task {
                            val raw = library.exportJson(id)
                            val path = withContext(Dispatchers.IO) { NativeFilePicker.chooseSaveJsonFile(title = tr("modifier.library.export"), defaultName = "preset.json") } ?: return@task
                            withContext(Dispatchers.IO) { Files.writeString(Path.of(path), raw) }
                        }
                    }, enabled = chosen != null)
                    CompactMenuDivider()
                    CompactMenuItem(tr("modifier.library.delete"), {
                        menu = false
                        val id = chosen?.id ?: return@CompactMenuItem
                        task { withContext(Dispatchers.IO) { library.delete(id) } }
                    }, enabled = chosen != null, danger = true)
                }
            }
        }
        if (naming) Text(tr("modifier.library.configOnly"), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.body)
        error?.let { Text(tr("modifier.library.failed", it), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.body) }
    }
}
