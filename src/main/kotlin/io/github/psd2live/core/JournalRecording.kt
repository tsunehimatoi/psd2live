package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/**
 * Edits made on the rig the user sees, written as journal entries for the stage they replay at.
 *
 * The user edits [shown] - the authored rig with every generator run on it - while the journal replays on the
 * authored rig itself, before the generators. So an entry keeps only what that stage reads: a key leaves out the axes
 * the generators add at their defaults ([GeneratedOverrides.journalOnly]), an edit of a keyform a generator makes is
 * merged after the generators as an override or refused ([GeneratedOverrides.capture], [GeneratedOverrides.ownedWrites]),
 * and a generator parameter an entry names is taken into the document before it ([GeneratedParameterAdoption]). The
 * entries then replay on the authored rig to what [shown] showed them doing.
 */
internal object JournalRecording {
	fun record(shown: PuppetModel, authored: PuppetModel, overlay: RigEditOverlay, edits: JsonArray,
			   skins: PrimitiveSkins = PrimitiveSkins.None, modifierReport: JsonObject? = null): List<JsonObject> {
        val graph = DocumentGenerators.graph(overlay, primitives = skins, modifierReport = modifierReport)
		val (_, compiled) = RigAuthoringJournal.compile(shown, GeneratedOverrides.capture(shown, overlay, edits, skins, graph))
		val owned = GeneratedOverrides.ownedWrites(shown, overlay, GeneratedOverrides.journalOnly(shown, authored, compiled), skins, authored, graph)
        if (modifierReport == null) return GeneratedParameterAdoption.adopted(shown, authored, owned)
        val ordinary = ArrayList<JsonObject>()
        val routed = ArrayList<JsonObject>()
        for (command in owned) {
            if (command["op"]?.jsonPrimitive?.content != "structure") {
                if (command["generator"]?.jsonPrimitive?.contentOrNull?.startsWith("modifier:") == true) routed += command
                else ordinary += command
                continue
            }
            val edits = command.getValue("edits").jsonArray.map { it.jsonObject }
            val pending = ArrayList<JsonObject>()
            for (edit in edits) {
                val kind = edit["kind"]?.jsonPrimitive?.content
                val id = edit["id"]?.jsonPrimitive?.content
                val action = edit["action"]?.jsonPrimitive?.content
                val parameterEdit = kind == "parameter" && action in setOf("rename", "update")
                if (!parameterEdit && (action !in setOf("rename", "visibility", "static", "move", "bind", "part") ||
                    kind in setOf("parameter", "param_group"))) {
                    pending += edit
                    continue
                }
                fun owner(kind: String?, id: String?) =
                    DocumentGenerators.owner(graph, "object:$kind:$id")?.takeIf { it.id.startsWith("modifier:") }
                val targetOwner = owner(kind, id)
                val references = buildList {
                    fun reference(field: String, type: String?) {
                        edit[field]?.jsonPrimitive?.contentOrNull?.let { value -> owner(type, value)?.let(::add) }
                    }
                    for (mask in edit["masked_by"]?.jsonArray.orEmpty()) owner("mesh", mask.jsonPrimitive.content)?.let(::add)
                    when (action) {
                        "bind" -> reference("parent_id", "warp")
                        "part" -> reference("part_id", "part")
                        "move" -> if (kind in setOf("warp", "rotation")) {
                            reference("parent_id", "warp")
                            reference("before_id", "warp")
                        } else {
                            reference("parent_id", "part")
                            reference("before_id", edit["before_kind"]?.jsonPrimitive?.contentOrNull)
                        }
                    }
                }.distinct()
                val stage = targetOwner ?: references.maxByOrNull(graph.order::indexOf)
                if (stage == null) {
                    pending += edit
                    continue
                }
                val stageIndex = graph.order.indexOf(stage)
                val later = references.firstOrNull { graph.order.indexOf(it) > stageIndex }
                require(later == null) { "$id depends on an object generated later by ${later?.id}" }
                routed += JsonObject(command + buildJsonObject {
                    put("edits", JsonArray(listOf(edit)))
                    put("generator", stage.id)
                    put("providers", JsonArray(references.filter { it != stage }.map { JsonPrimitive(it.id.removePrefix("modifier:")) }))
                })
            }
            if (pending.isNotEmpty()) ordinary += JsonObject(command + ("edits" to JsonArray(pending)))
        }
        return GeneratedParameterAdoption.adopted(shown, authored, ordinary) + routed
	}
}
