package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** Physics panel actions are resolved against a captured candidate, never a displayed preview. */
sealed interface WorkspacePhysicsIntent {
    data class Put(val edit: RigPhysicsEdit) : WorkspacePhysicsIntent
    data class Enabled(val id: String, val enabled: Boolean) : WorkspacePhysicsIntent
    data class Delete(val id: String) : WorkspacePhysicsIntent
    data class Create(val name: String, val copyFrom: String? = null) : WorkspacePhysicsIntent
    data class Move(val id: String, val by: Int) : WorkspacePhysicsIntent
    data class Fps(val fps: Int) : WorkspacePhysicsIntent
    data class Preset(val id: String, val preset: PhysicsPresets.Preset) : WorkspacePhysicsIntent
    /** [peaks] are each output's observed reach toward its parameter's end; [target] is the reach to scale it to. */
    data class FitObserved(val id: String, val peaks: Map<Int, Float>, val target: Float = 1f) : WorkspacePhysicsIntent
}

internal object WorkspacePhysicsIntents {
    fun operation(document: WorkspaceDocument, model: RigPreviewModel, intent: WorkspacePhysicsIntent): WorkspaceDocumentOperation {
        val allGroups = WorkspaceDocumentEdits.physicsCatalog(document, model)
        val groups = if (intent is WorkspacePhysicsIntent.Move && document.rigEdits.modifierStack != null)
            allGroups.filter { it.origin != PhysicsOrigin.MODIFIER } else allGroups
        val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        fun group(id: String) = groups.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Physics group not found: $id")
        // toJson omits the default normalization. A full typed replacement must explicitly reset it.
        fun put(edit: RigPhysicsEdit) = WorkspaceDocumentOperation("physics_put",
            JsonObject(edit.toJson() + ("normalization" to edit.normalization.toJson())))
        fun fields(id: String) = buildJsonObject { put("id", id) }
        return when (intent) {
            is WorkspacePhysicsIntent.Put -> put(intent.edit)
            is WorkspacePhysicsIntent.Enabled -> WorkspaceDocumentOperation("physics_put", JsonObject(fields(intent.id) + ("enabled" to JsonPrimitive(intent.enabled))))
            is WorkspacePhysicsIntent.Delete -> WorkspaceDocumentOperation("physics_delete", fields(intent.id))
            is WorkspacePhysicsIntent.Create -> {
                require(intent.name.isNotBlank() && intent.name.none(Char::isISOControl)) { "Physics name is required" }
                val id = PhysicsAuthoring.freshId(groups, document.rigEdits)
                val names = groups.mapTo(HashSet()) { it.setting.name }
                val name = generateSequence(1) { it + 1 }.map { if (it == 1) intent.name else "${intent.name} $it" }.first { it !in names }
                put(intent.copyFrom?.let { group(it).setting.copy(id = id, name = name, outputs = emptyList()) }
                    ?: PhysicsAuthoring.template(id, name, available))
            }
            is WorkspacePhysicsIntent.Move -> {
                val next = PhysicsAuthoring.move(document.rigEdits, groups, intent.id, intent.by)
                WorkspaceDocumentOperation("physics_config", buildJsonObject { put("order", JsonArray(next.physicsOrder.map(::JsonPrimitive))) })
            }
            is WorkspacePhysicsIntent.Fps -> WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", intent.fps) })
            is WorkspacePhysicsIntent.Preset -> put(PhysicsPresets.apply(intent.preset, group(intent.id).setting, available))
            is WorkspacePhysicsIntent.FitObserved -> {
                val setting = group(intent.id).setting
                require(intent.peaks.all { (index, peak) -> index in setting.outputs.indices && peak.isFinite() && peak >= 0f }) {
                    "Observed physics peaks must be finite, nonnegative and identify an output"
                }
                require(intent.peaks.values.any { it > 0.01f }) { "No physics output has a measured response" }
                require(intent.target.isFinite() && intent.target in 0.1f..3f) { "Target fraction must be in 0.1..3" }
                put(PhysicsAuthoring.fitScales(setting, intent.peaks, intent.target))
            }
        }
    }
}
