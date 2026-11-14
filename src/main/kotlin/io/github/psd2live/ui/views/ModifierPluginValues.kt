package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*

/** Plugin configurations render declarative JSON Schema fields with validation before submitting a document edit. */
@Composable
internal fun ModifierPluginValues(instance: RigModifierInstance, enabled: Boolean, vm: PSD2LiveViewModel) {
    val ref = instance.plugin ?: return
    val manifest = ModifierPluginStore.shared.runnablePackages().firstOrNull { it.reference == ref }?.manifest
        ?: PluginDevArtifactStore.resolve(ref)?.manifest
        ?: return
    val definition = manifest.algorithms.firstOrNull {
        (it.type == instance.type || instance.type in it.aliases) && it.version == instance.version
    } ?: return
    var failure by remember(instance.id) { mutableStateOf<String?>(null) }

    fun update(name: String, value: JsonElement) {
        val config = JsonObject(instance.config + (name to value))
        try {
            ModifierPluginSchema.validate(config, definition.configSchema)
            failure = null
            vm.editModifier("modifier_update", buildJsonObject {
                put("id", instance.id)
                put("type", instance.type)
                put("config", config)
            })
        } catch (error: IllegalArgumentException) {
            failure = error.message
        }
    }

    val properties = definition.configSchema["properties"]?.jsonObject.orEmpty()
    if (properties.isEmpty()) {
        Text(tr("modifier.plugin.noParameters"), style = LocalToolTypography.current.caption)
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        properties.forEach { (name, raw) ->
            val schema = raw as? JsonObject
            if (schema == null) {
                InspectorHint(tr("modifier.plugin.unsupportedField", name, "invalid"), warning = true)
                return@forEach
            }
            val title = schema["title"]?.jsonPrimitive?.contentOrNull ?: name
            val description = schema["description"]?.jsonPrimitive?.contentOrNull
            val defaultVal = schema["default"] ?: definition.defaults[name]
            val value = instance.config[name] ?: defaultVal ?: JsonNull
            val typeName = schema["type"]?.jsonPrimitive?.contentOrNull
            val enumChoices = schema["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }

            val unsupportedReason: String? = when {
                "\$ref" in schema -> "\$ref"
                "oneOf" in schema -> "oneOf"
                typeName == "array" || typeName == "object" -> typeName
                typeName == "string" && "enum" in schema && enumChoices.isNullOrEmpty() -> "enum"
                typeName !in setOf("boolean", "number", "integer", "string") -> typeName ?: "unknown"
                else -> null
            }

            if (unsupportedReason != null) {
                InspectorFormRow(title) {
                    Text(unsupportedReason, style = LocalToolTypography.current.caption)
                }
                InspectorHint(tr("modifier.plugin.unsupportedField", title, unsupportedReason), warning = true)
            } else when (typeName) {
                "boolean" -> {
                    InspectorFormRow(title) {
                        CompactCheckbox(
                            checked = (value as? JsonPrimitive)?.booleanOrNull == true,
                            onCheckedChange = { update(name, JsonPrimitive(it)) },
                            enabled = enabled,
                        )
                    }
                }
                "string" -> {
                    if (!enumChoices.isNullOrEmpty()) {
                        val current = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it in enumChoices } ?: enumChoices.first()
                        InspectorFormRow(title) {
                            CompactDropdown(
                                items = enumChoices,
                                selectedItem = current,
                                onItemSelected = { update(name, JsonPrimitive(it)) },
                                enabled = enabled,
                                height = 22.dp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        val currentText = (value as? JsonPrimitive)?.contentOrNull ?: ""
                        var draft by remember(instance.id, name, currentText) { mutableStateOf(currentText) }
                        InspectorFormRow(title) {
                            CompactTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = enabled,
                                onCommit = { update(name, JsonPrimitive(draft)) },
                            )
                        }
                    }
                }
                "number", "integer" -> {
                    val isInteger = typeName == "integer"
                    val currentText = when {
                        value is JsonNull -> ""
                        value is JsonPrimitive && isInteger -> value.longOrNull?.toString() ?: value.content
                        value is JsonPrimitive -> value.doubleOrNull?.let { d ->
                            if (d % 1.0 == 0.0 && d in -1e15..1e15) d.toLong().toString() else d.toString()
                        } ?: value.content
                        else -> value.toString()
                    }
                    var draft by remember(instance.id, name, currentText) { mutableStateOf(currentText) }
                    InspectorFormRow(title) {
                        CompactTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                            onCommit = {
                                val trimmed = draft.trim()
                                if (isInteger) {
                                    val parsed = trimmed.toLongOrNull()
                                    if (parsed == null) failure = tr("modifier.plugin.invalidNumber", title)
                                    else update(name, JsonPrimitive(parsed))
                                } else {
                                    val parsed = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
                                    if (parsed == null) failure = tr("modifier.plugin.invalidNumber", title)
                                    else update(name, JsonPrimitive(parsed))
                                }
                            },
                        )
                    }
                    numericConstraintHint(schema, defaultVal)?.let { hint ->
                        InspectorHint(hint)
                    }
                }
            }
            if (!description.isNullOrBlank()) {
                InspectorHint(description)
            }
        }
        failure?.let { InspectorHint(it, warning = true) }
    }
}

private fun numericConstraintHint(schema: JsonObject, defaultVal: JsonElement?): String? {
    val parts = mutableListOf<String>()
    val min = schema["minimum"]?.jsonPrimitive?.contentOrNull
        ?: schema["exclusiveMinimum"]?.jsonPrimitive?.contentOrNull?.let { ">$it" }
    val max = schema["maximum"]?.jsonPrimitive?.contentOrNull
        ?: schema["exclusiveMaximum"]?.jsonPrimitive?.contentOrNull?.let { "<$it" }
    if (min != null || max != null) {
        parts += tr("modifier.plugin.rangeHint", min ?: "-∞", max ?: "+∞")
    }
    val defText = (defaultVal as? JsonPrimitive)?.contentOrNull
    if (defText != null) {
        parts += tr("modifier.plugin.defaultHint", defText)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
