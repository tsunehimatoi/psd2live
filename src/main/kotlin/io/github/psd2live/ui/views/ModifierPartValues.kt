package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.ModifierPartEditing
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*

/** A part's public subfeatures use the same fields and captured-state submission as standalone bindings. */
@Composable
internal fun ModifierPartValues(instance: RigModifierInstance, state: PSD2LiveState, enabled: Boolean, vm: PSD2LiveViewModel) {
    fun commit(config: JsonObject, captured: String?, done: (String?) -> Unit) {
        vm.editModifier("modifier_update", buildJsonObject {
            put("id", instance.id); put("type", instance.type); put("config", config)
        }, captured, done)
    }
    val modes = when (instance.type) {
        "hair" -> listOf("chains", "legacy")
        "body" -> emptyList()
        else -> listOf("fitted", "legacy")
    }
    val baseType = ModifierPartEditing.baseType(instance)
    if (modes.isNotEmpty()) InspectorFormRow(tr("modifier.part.mode")) {
        val mode = baseType.substringAfter('/')
        CompactDropdown(modes, mode, { next ->
            if (next != mode) commit(JsonObject(instance.config + ("mode" to JsonPrimitive(next))), vm.modifierEditState()) {}
        }, itemLabel = { tr("modifier.part.mode.$it") }, enabled = enabled, height = 20.dp, modifier = Modifier.fillMaxWidth())
    }
    var baseOpen by rememberSaveable(instance.id) { mutableStateOf(false) }
    PresetFolderRow(tr("modifier.part.base"), "", baseOpen, true) { baseOpen = !baseOpen }
    if (baseOpen) {
        if (instance.type in setOf("brow", "hair") && RigLegacyBindings.find(baseType)?.hasPhysics != true) InspectorFormRow(tr("swing.physics")) {
            CompactCheckbox(instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull != false, { value ->
                commit(JsonObject(instance.config + ("physicsEnabled" to JsonPrimitive(value))), vm.modifierEditState()) {}
            }, enabled = enabled)
        }
        key(baseType) { ModifierBindingValues(instance.copy(type = baseType, plugin = null), enabled, vm, ::commit) }
    }
    for ((flag, type) in ModifierPartConfig.features[instance.type].orEmpty()) key(instance.id, type) {
        val active = ModifierPartConfig.enabled(instance.config, flag)
        val child = ModifierPartEditing.child(instance, type)
        var open by rememberSaveable { mutableStateOf(active) }
        PresetFolderRow(tr("settings.rig.module.$type"), tr("modifier.count", child.targets.size), open, true,
            leading = {
                CompactCheckbox(active, { value ->
                    if (value) open = true
                    commit(ModifierPartConfig.withFlag(instance.config, flag, value), vm.modifierEditState()) {}
                }, enabled = enabled)
            }) { open = !open }
        if (open) Column(Modifier.fillMaxWidth().padding(start = Spacing.Margin), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            var targetsOpen by rememberSaveable { mutableStateOf(false) }
            val ownTargets = ModifierPartEditing.hasOwnTargets(instance, type)
            PresetFolderRow(tr(if (ownTargets) "modifier.part.ownTargets" else "modifier.part.inheritedTargets"),
                tr("modifier.count", child.targets.size), targetsOpen, true) { targetsOpen = !targetsOpen }
            if (targetsOpen) child.targets.forEach { target ->
                CompactButton(contentName(state, target.kind, target.id), { vm.locateModifierContent(target.kind, target.id) },
                    height = 20.dp, modifier = Modifier.fillMaxWidth())
            }
            fun childCommit(config: JsonObject, captured: String?, done: (String?) -> Unit) =
                commit(ModifierPartEditing.withChildConfig(instance, type, config), captured, done)
            ModifierBindingValues(child, enabled && active, vm, ::childCommit)
            ModifierPartPhysics(child, state, enabled && active, vm, ::childCommit)
        }
    }
}

@Composable
private fun ModifierPartPhysics(child: RigModifierInstance, state: PSD2LiveState, enabled: Boolean, vm: PSD2LiveViewModel,
    commit: (JsonObject, String?, (String?) -> Unit) -> Unit) {
    val spec = RigLegacyBindings.find(child.type) ?: return
    if (!spec.hasPhysics) return
    val overrides = child.config["physics"]?.jsonObject.orEmpty()
    val generated = state.previewModel?.rig?.modifierOutput?.physics?.groups.orEmpty().associateBy { it.id }
    val slots = (spec.module?.physicsGroups.orEmpty() + overrides.keys).distinct()
    val values = slots.mapNotNull { slot ->
        val config = overrides[slot]?.jsonObject ?: generated[child.outputId("physics:$slot")]?.let { Physics3Json.setting(it).toJson() }
        config?.let { slot to it }
    }
    var open by rememberSaveable(child.id) { mutableStateOf(false) }
    PresetFolderRow(tr("swing.physics"), tr("modifier.count", values.size), open, true) { open = !open }
    if (open) {
        if (values.isEmpty()) InspectorHint(tr("modifier.part.physicsPending"))
        values.forEach { (slot, value) ->
            Text(value["name"]?.jsonPrimitive?.content ?: slot, style = LocalToolTypography.current.caption,
                color = LocalToolColors.current.textMuted)
            ModifierNativeValues("physics", value, state, enabled, vm, editName = false, instanceId = "${child.id}:$slot") { next, captured, done ->
                val physics = JsonObject(overrides + (slot to next))
                commit(JsonObject(child.config + ("physics" to physics)), captured, done)
            }
        }
    }
}
