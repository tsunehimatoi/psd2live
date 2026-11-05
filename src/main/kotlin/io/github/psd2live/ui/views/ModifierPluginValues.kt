package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.modifier.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*

/** Plugin configurations use the existing compact fields, with validation before submitting a document edit. */
@Composable
internal fun ModifierPluginValues(instance: RigModifierInstance, enabled: Boolean, vm: PSD2LiveViewModel) {
    val entry = ModifierPluginStore.shared.snapshot().firstOrNull { it.reference == instance.plugin } ?: return
    val definition = entry.manifest.algorithms.single { it.type == instance.type && it.version == instance.version }
    var failure by remember(instance.id) { mutableStateOf<String?>(null) }
    fun update(name: String, value: JsonElement) {
        val config = JsonObject(instance.config + (name to value))
        try {
            ModifierPluginSchema.validate(config, definition.configSchema)
            failure = null
            vm.editModifier("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", config) })
        } catch (error: IllegalArgumentException) { failure = error.message }
    }
    definition.configSchema["properties"]?.jsonObject.orEmpty().forEach { (name, raw) ->
        val schema = raw.jsonObject
        val value = instance.config[name] ?: definition.defaults[name] ?: JsonNull
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(schema["title"]?.jsonPrimitive?.content ?: name, style = LocalToolTypography.current.caption, modifier = Modifier.weight(1f))
            if (schema["type"]?.jsonPrimitive?.content == "boolean") {
                CompactCheckbox(value.jsonPrimitive.booleanOrNull == true, { update(name, JsonPrimitive(it)) }, enabled = enabled)
            } else {
                val string = schema["type"]?.jsonPrimitive?.content == "string"
                var draft by remember(instance.id, name, value) { mutableStateOf(if (string) value.jsonPrimitive.content else value.toString()) }
                CompactTextField(draft, { draft = it }, modifier = Modifier.width(120.dp), enabled = enabled, onCommit = {
                    try { update(name, if (string) JsonPrimitive(draft) else Json.parseToJsonElement(draft)) }
                    catch (error: IllegalArgumentException) { failure = error.message }
                })
            }
        }
    }
    failure?.let { SettingsHint(it, indent = false) }
}
