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
    val foldController = LocalModifierFoldController.current
    val foldGeneration = foldController?.generation ?: 0

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Margin)) {
        RememberedModifierSection(
            stateKey = "${instance.id}:base",
            title = tr("modifier.part.base"),
            defaultExpanded = true,
            summary = if (modes.isNotEmpty()) tr("modifier.part.mode.${baseType.substringAfter('/')}") else "",
        ) {
            if (modes.isNotEmpty()) InspectorFormRow(tr("modifier.part.mode")) {
                val mode = baseType.substringAfter('/')
                CompactDropdown(modes, mode, { next ->
                    if (next != mode) commit(JsonObject(instance.config + ("mode" to JsonPrimitive(next))), vm.modifierEditState()) {}
                }, itemLabel = { tr("modifier.part.mode.$it") }, enabled = enabled, height = 20.dp, modifier = Modifier.fillMaxWidth())
            }
            if (instance.type in setOf("brow", "hair") && RigLegacyBindings.find(baseType)?.hasPhysics != true) {
                InspectorFormRow(tr("swing.physics")) {
                    CompactCheckbox(instance.config["physicsEnabled"]?.jsonPrimitive?.booleanOrNull != false, { value ->
                        commit(JsonObject(instance.config + ("physicsEnabled" to JsonPrimitive(value))), vm.modifierEditState()) {}
                    }, enabled = enabled)
                }
            }
            key(baseType) { ModifierBindingValues(instance.copy(type = baseType, plugin = null), enabled, vm, ::commit) }
        }

        for ((flag, type) in ModifierPartConfig.features[instance.type].orEmpty()) key(instance.id, type) {
            val active = ModifierPartConfig.enabled(instance.config, flag)
            val child = ModifierPartEditing.child(instance, type)
            var open by rememberSaveable(instance.id, type) { mutableStateOf(active) }
            LaunchedEffect(foldGeneration) {
                if (foldGeneration > 0) {
                    foldController?.targetExpanded?.let { open = it }
                }
            }
            ModifierCollapsibleSection(
                title = tr("settings.rig.module.$type"),
                expanded = open,
                onToggle = { open = !open },
                summary = tr("modifier.count", child.targets.size),
                leading = {
                    CompactCheckbox(active, { value ->
                        if (value) open = true
                        commit(ModifierPartConfig.withFlag(instance.config, flag, value), vm.modifierEditState()) {}
                    }, enabled = enabled)
                },
            ) {
                val ownTargets = ModifierPartEditing.hasOwnTargets(instance, type)
                RememberedModifierSection(
                    stateKey = "${instance.id}:$type:targets",
                    title = tr(if (ownTargets) "modifier.part.ownTargets" else "modifier.part.inheritedTargets"),
                    defaultExpanded = false,
                    level = 1,
                    summary = tr("modifier.count", child.targets.size),
                ) {
                    child.targets.forEach { target ->
                        CompactButton(
                            contentName(state, target.kind, target.id),
                            { vm.locateModifierContent(target.kind, target.id) },
                            height = 20.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                fun childCommit(config: JsonObject, captured: String?, done: (String?) -> Unit) =
                    commit(ModifierPartEditing.withChildConfig(instance, type, config), captured, done)
                ModifierBindingValues(child, enabled && active, vm, ::childCommit)
                ModifierPartPhysics(child, state, enabled && active, vm, ::childCommit)
            }
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
    RememberedModifierSection(
        stateKey = "${child.id}:physics",
        title = tr("swing.physics"),
        defaultExpanded = false,
        level = 1,
        summary = tr("modifier.count", values.size),
    ) {
        if (values.isEmpty()) InspectorHint(tr("modifier.part.physicsPending"))
        values.forEach { (slot, value) ->
            RememberedModifierSection(
                stateKey = "${child.id}:physics:$slot",
                title = value["name"]?.jsonPrimitive?.content ?: slot,
                defaultExpanded = true,
                level = 1,
            ) {
                ModifierNativeValues("physics", value, state, enabled, vm, editName = false, instanceId = "${child.id}:$slot") { next, captured, done ->
                    val physics = JsonObject(overrides + (slot to next))
                    commit(JsonObject(child.config + ("physics" to physics)), captured, done)
                }
            }
        }
    }
}
