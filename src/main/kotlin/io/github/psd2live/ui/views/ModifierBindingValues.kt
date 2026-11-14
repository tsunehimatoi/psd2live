package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.RigTuning
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*

/** Binding descriptors drive both ordinary instances and a part's local settings. */
@Composable
internal fun ModifierBindingValues(instance: RigModifierInstance, enabled: Boolean, vm: PSD2LiveViewModel,
    commit: (JsonObject, String?, (String?) -> Unit) -> Unit) {
    val spec = RigLegacyBindings.find(instance.type) ?: return
    if (spec.hasPhysics) InspectorFormRow(tr("swing.physics")) {
        CompactCheckbox(instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull != false, { value ->
            commit(JsonObject(instance.config + ("physicsEnabled" to JsonPrimitive(value))), vm.modifierEditState()) {}
        }, enabled = enabled)
    }
    if (spec.mouthOutline) {
        val color = RigLegacyBindings.outlineColor(instance)
        var hex by remember(instance.id) { mutableStateOf(color?.let { "%06X".format(it) }.orEmpty()) }
        var editing by remember { mutableStateOf(false) }
        var expected by remember { mutableStateOf<String?>(null) }
        val actual by rememberUpdatedState(color)
        LaunchedEffect(color) { if (!editing) hex = color?.let { "%06X".format(it) }.orEmpty() }
        fun setColor(rgb: Int?, captured: String?) {
            val config = JsonObject(if (rgb == null) instance.config - "color" else instance.config + ("color" to JsonPrimitive(rgb)))
            commit(config, captured) { failure -> if (failure != null) hex = actual?.let { "%06X".format(it) }.orEmpty() }
        }
        InspectorFormRow(tr("mouth.outline")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
                CompactTextField(hex, { hex = it }, enabled = enabled, placeholder = "#RRGGBB", isMono = true,
                    modifier = Modifier.width(80.dp), height = 20.dp, endEditOnSettle = false,
                    onEditStart = { editing = true; expected = vm.modifierEditState() }, onEditEnd = {
                        editing = false
                        val digits = hex.trim().removePrefix("#")
                        val rgb = digits.takeIf { it.length == 6 }?.toIntOrNull(16)?.takeIf { it in 0..0xffffff }
                        if (rgb == null) hex = actual?.let { "%06X".format(it) }.orEmpty()
                        else if (rgb != color) setColor(rgb, expected)
                    })
                CompactButton(tr("mouth.autoColor"), { setColor(null, vm.modifierEditState()) }, enabled = enabled && color != null, height = 20.dp)
            }
        }
    }
    fun config(field: String, key: String?, value: Float): JsonObject = if (key == null) JsonObject(instance.config + (field to JsonPrimitive(value)))
        else JsonObject(instance.config + (field to JsonObject(instance.config[field]?.jsonObject.orEmpty() + (key to JsonPrimitive(value)))))
    @Composable fun value(label: String, field: String, key: String?, default: Float, range: ClosedFloatingPointRange<Float>, unit: String) {
        val current = if (key == null) instance.config[field]?.jsonPrimitive?.float else instance.config[field]?.jsonObject?.get(key)?.jsonPrimitive?.float
        ModifierNumber(label, current ?: default, range, unit, enabled, vm) { number, expected, done ->
            commit(config(field, key, number), expected, done)
        }
    }
    if (spec.strength) value(tr("modifier.strength"), "strength", null, 1f, 0f..4f, "")
    if (spec.knobs.isNotEmpty() || spec.fields.isNotEmpty()) {
        run {
            val hasTurnStyle = spec.type == "face.depth-turn" || spec.type == RigLegacyBindings.FITTED_FACE
            if (hasTurnStyle) {
                val style = instance.config["values"]?.jsonObject?.get("style")?.jsonPrimitive?.float ?: 0f
                TurnStyleRow(style, enabled) { index ->
                    val values = buildJsonObject {
                        instance.config["values"]?.jsonObject?.forEach { (key, value) -> put(key, value) }
                        spec.knobs.forEach { knob -> put(knob.id, if (knob.id == "style") index.toFloat() else knob.default) }
                    }
                    commit(JsonObject(instance.config + ("values" to values)), vm.modifierEditState()) {}
                }
            }
            spec.knobs.filterNot { hasTurnStyle && it.id == "style" }.forEach { knob ->
                val label = when {
                    spec.seeThrough -> tr("seeThrough.opacity")
                    spec.mouthOutline -> tr("mouth.thickness")
                    else -> tr("settings.rig.knob.${if (instance.type == RigLegacyBindings.FITTED_FACE) "face.depth-turn" else instance.type.replace('/', '.')}.${knob.id}")
                }
                value(label, "values", knob.id, knob.default, knob.range, unit(knob.unit))
            }
            val groups = spec.fields.map { it.group }.distinct()
            var selectedGroup by remember(instance.id) { mutableStateOf(groups.firstOrNull()) }
            if (groups.size > 1) CompactDropdown(groups, selectedGroup ?: groups.first(), { selectedGroup = it },
                itemLabel = { tr("settings.rigTuning.group." + it.name.lowercase()) }, modifier = Modifier.fillMaxWidth(), height = 22.dp)
            spec.fields.filter { it.group == selectedGroup }.forEach { field ->
                value(tr("settings.rigTuning.${field.id}"), "tuning", field.id, field.default, field.range, unit(field.unit))
            }
        }
    }
}
