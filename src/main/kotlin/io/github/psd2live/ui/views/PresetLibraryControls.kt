package io.github.psd2live.ui.views

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.application.WorkspaceModelPresetLibrary
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.ui.views.texture.FloatingMenuDivider
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.menuAppearance
import io.github.psd2live.ui.views.texture.menuPanel
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
    val colors = LocalToolColors.current
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
                val visibility = remember { MutableTransitionState(false) }
                visibility.targetState = menu
                if (visibility.currentState || visibility.targetState) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, 26),
                        onDismissRequest = { menu = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            modifier = Modifier
                                .menuAppearance(visibility, TransformOrigin(0.12f, 0f), rise = false)
                                .menuPanel()
                                .padding(4.dp),
                        ) {
                            MenuGlide(Modifier.width(224.dp)) {
                                Column(Modifier.fillMaxWidth()) {
                                    if (entries.isNotEmpty()) {
                                        FloatingMenuSection(tr("modifier.library.manage"))
                                        Column(
                                            Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                                        ) {
                                            entries.forEach { entry ->
                                                val label = entry.nameKey?.let { tr(it) } ?: entry.name
                                                FloatingMenuRow(
                                                    label = label,
                                                    onClick = { chosenId = entry.id },
                                                    selected = entry.id == chosen?.id,
                                                    bar = false,
                                                )
                                            }
                                        }
                                        FloatingMenuDivider()
                                    }
                                    FloatingMenuRow(
                                        label = tr("modifier.library.save"),
                                        onClick = { menu = false; naming = true; name = "" },
                                        enabled = state.rigEdits.modifierStack != null && model != null,
                                        bar = false,
                                    )
                                    FloatingMenuRow(
                                        label = tr("modifier.library.import"),
                                        onClick = {
                                            menu = false
                                            task {
                                                val path = withContext(Dispatchers.IO) { NativeFilePicker.chooseJsonFile(title = tr("modifier.library.import")) } ?: return@task
                                                val saved = withContext(Dispatchers.IO) { library.importJson(Files.readString(Path.of(path))) }
                                                chosenId = saved.id; onSaved(saved.id)
                                            }
                                        },
                                        bar = false,
                                    )
                                    FloatingMenuRow(
                                        label = tr("modifier.library.export"),
                                        onClick = {
                                            menu = false
                                            val id = chosen?.id ?: return@FloatingMenuRow
                                            task {
                                                val raw = library.exportJson(id)
                                                val path = withContext(Dispatchers.IO) { NativeFilePicker.chooseSaveJsonFile(title = tr("modifier.library.export"), defaultName = "preset.json") } ?: return@task
                                                withContext(Dispatchers.IO) { Files.writeString(Path.of(path), raw) }
                                            }
                                        },
                                        enabled = chosen != null,
                                        bar = false,
                                    )
                                    FloatingMenuDivider()
                                    FloatingMenuRow(
                                        label = tr("modifier.library.delete"),
                                        onClick = {
                                            menu = false
                                            val id = chosen?.id ?: return@FloatingMenuRow
                                            task { withContext(Dispatchers.IO) { library.delete(id) } }
                                        },
                                        enabled = chosen != null,
                                        danger = true,
                                        bar = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (naming) Text(tr("modifier.library.configOnly"), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.body)
        error?.let { Text(tr("modifier.library.failed", it), color = LocalToolColors.current.textMuted, style = LocalToolTypography.current.body) }
    }
}
