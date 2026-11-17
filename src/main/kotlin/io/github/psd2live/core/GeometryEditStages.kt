package io.github.psd2live.core

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** A geometry edit resolves its source and execution position from one captured evaluation. */
internal object GeometryEditStages {
    const val FIELD = "edit_stage"
    data class Resolved(val puppet: PuppetModel, val generator: String?, val report: JsonObject?)
    fun resolve(model: RigPreviewModel, stage: String): Resolved {
        if (stage == "auto") return Resolved(model.rig.puppet, null, model.rig.modifierReport)
        val snapshot = requireNotNull(model.modifierEvaluation) { "Editing stages require an evaluated modifier stack" }
        if (stage == "base") return Resolved(PuppetIr.toPuppet(snapshot.authored), null, null)
        val id = if (stage == "final") snapshot.entries.lastOrNull { it.status == ModifierStatus.ACTIVE }?.instance?.id
            ?: return Resolved(PuppetIr.toPuppet(snapshot.authored), null, null)
        else { require(stage.startsWith("modifier:")) { "Unknown edit stage: $stage" }; stage.removePrefix("modifier:") }
        val index = snapshot.stack.index(id)
        require(snapshot.entries[index].status == ModifierStatus.ACTIVE) { "The edit stage is not active: $id" }
        val entries = model.rig.modifierReport?.get("entries")?.jsonArray.orEmpty().take(index + 1)
        val report = model.rig.modifierReport?.let { JsonObject(it + ("entries" to JsonArray(entries))) }
        return Resolved(PuppetIr.toPuppet(snapshot.outputs[index]), "modifier:$id", report)
    }
    fun stage(edits: JsonArray): String {
        val stages = edits.map { it.jsonObject[FIELD]?.jsonPrimitive?.content ?: "auto" }.distinct()
        require(stages.size <= 1) { "One geometry transaction must use one edit stage" }
        return stages.singleOrNull() ?: "auto"
    }
    fun withStage(command: JsonObject, stage: String): JsonObject = JsonObject(command + (FIELD to JsonPrimitive(stage)))
    fun clean(edits: JsonArray): JsonArray = JsonArray(edits.map { JsonObject(it.jsonObject - FIELD) })
    fun validateExplicit(source: PuppetModel, edits: JsonArray) {
        val parameters = source.parameters.mapTo(HashSet()) { it.id.raw }
        for (element in edits) {
            val edit = element.jsonObject
            require(edit["op"]?.jsonPrimitive?.content in setOf("canvas_geometry", "set", RigBezierJournal.OP)) {
                "Explicit editing stages accept geometry and keyform values"
            }
            val key = edit["key"]?.jsonObject.orEmpty()
            require(key.keys.all { it in parameters }) { "The edit references parameters unavailable at its stage" }
        }
    }
}
