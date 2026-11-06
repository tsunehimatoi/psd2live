package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.application.WorkspacePluginManagement
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
internal fun SettingsPluginsSection() {
    val revision by ModifierPluginStore.shared.revision.collectAsState()
    val entries = remember(revision) { WorkspacePluginManagement.execute("plugin_list", JsonObject(emptyMap())).getValue("plugins").jsonArray }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun execute(operation: String, request: JsonObject) {
        busy = true; failure = null
        scope.launch {
            try { runInterruptible(Dispatchers.IO) { WorkspacePluginManagement.execute(operation, request) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message }
            finally { busy = false }
        }
    }
    SettingsSectionDescription(tr("plugins.description"))
    ModifierPluginStore.shared.problems().forEach { SettingsHint(it, indent = false) }
    SettingsGroup(tr("plugins.install")) {
        SettingsRow(tr("plugins.source")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactTextField(source, { source = it }, Modifier.weight(1f), enabled = !busy)
                CompactButton(tr("plugins.browse"), {
                    scope.launch { withContext(Dispatchers.IO) { NativeFilePicker.choosePluginFile(title = tr("plugins.install")) }?.let { source = it } }
                }, enabled = !busy)
            }
        }
        SettingsRow(tr("plugins.ref")) { CompactTextField(ref, { ref = it }, enabled = !busy) }
        SettingsIndented { CompactButton(tr("plugins.install"), {
            execute("plugin_install", buildJsonObject { put("source", source); if (ref.isNotBlank()) put("ref", ref) })
        }, enabled = !busy && source.isNotBlank()) }
        failure?.let { SettingsHint(it, indent = false) }
    }
    SettingsGroup(tr("plugins.installed")) {
        for (raw in entries) {
            val entry = raw.jsonObject
            val identity = buildJsonObject { put("id", entry.getValue("id")); put("digest", entry.getValue("digest")) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactCheckbox(entry.getValue("enabled").jsonPrimitive.boolean, {
                    execute(if (it) "plugin_enable" else "plugin_disable", identity)
                }, enabled = !busy)
                val name = if (entry.getValue("builtin").jsonPrimitive.boolean)
                    io.github.psd2live.ui.views.modifierName(entry.getValue("algorithms").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
                else entry.getValue("name").jsonPrimitive.content
                Text(name, style = LocalToolTypography.current.body, modifier = Modifier.weight(1f))
                Text(entry.getValue("version").jsonPrimitive.content, style = LocalToolTypography.current.caption)
                if (!entry.getValue("builtin").jsonPrimitive.boolean) CompactButton(tr("plugins.uninstall"), {
                    execute("plugin_uninstall", identity)
                }, enabled = !busy)
            }
            entry.getValue("issues").jsonArray.forEach { SettingsHint(it.jsonPrimitive.content, indent = false) }
        }
    }
}
