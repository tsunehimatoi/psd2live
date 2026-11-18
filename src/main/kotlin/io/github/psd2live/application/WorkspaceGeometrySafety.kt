package io.github.psd2live.application

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** Limit the gate to the authoring families covered by the geometry safety contract. */
internal object WorkspaceGeometrySafety {
    val operations = setOf("rig_deform", "keyform_apply", "rig_edit_structure", "object_edit_appearance",
        "canvas_warp", "canvas_rotation", "canvas_glue", "canvas_topology", "canvas_deform_stroke", "path_deform")
    private val journalOperations = setOf("set", "copy", "delete", "structure", "warp", "canvas_geometry",
        "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit")
    private val targetKinds = setOf("mesh", "warp", "rotation")

    fun changed(before: WorkspaceDocument, after: WorkspaceDocument): Boolean {
        val old = before.rigEdits.authoringJournal
        val next = after.rigEdits.authoringJournal
        // Existing records belong to history; only a newly authored suffix triggers this policy.
        if (WorkspaceMeshPointEdits.appended(before, after)) return true
        return next.size > old.size && next.take(old.size) == old &&
            next.drop(old.size).any { it.jsonObject["op"]?.jsonPrimitive?.content in journalOperations }
    }

    /**
     * The targets ([io.github.psd2live.core.GeometrySafetyEvaluator] refs) the journal suffix [after] appends to
     * [before] can move, or null to compare every target. Null whenever the documents differ in anything but
     * that suffix, or an appended command restructures the rig (creates or reparents objects, rebuilds a mesh,
     * replays a stored record) or cannot be read. Every unbaked swing and enabled simulation target joins the
     * scope, since those generators rerun over the whole replayed rig and may rewrite their own targets.
     */
    fun scope(before: WorkspaceDocument, after: WorkspaceDocument): Set<String>? {
        if (before.copy(rigEdits = after.rigEdits) != after) return null
        return scope(before.rigEdits, after.rigEdits)
    }

    /** [scope] of two overlays: null unless [after] only appends journal entries to [before]. */
    fun scope(before: RigEditOverlay, after: RigEditOverlay): Set<String>? {
        val old = before.authoringJournal
        val next = after.authoringJournal
        if (next.size <= old.size || next.subList(0, old.size) != old) return null
        if (before.copy(authoringJournal = next) != after) return null
        val refs = HashSet<String>()
        for (entry in next.subList(old.size, next.size)) refs += runCatching { refsOf(entry) }.getOrNull() ?: return null
        fun anyKind(id: String) = targetKinds.forEach { refs += "$it:$id" }
        after.swingEdits.filter { !it.baked }.forEach { swing -> swing.targets.forEach(::anyKind) }
        after.simEdits.forEach { sim ->
            sim.targets.forEach(::anyKind)
            sim.bake?.vertexCounts?.keys?.forEach(::anyKind)
        }
        return refs
    }

    /** The targets one journal command addresses, or null when it may move targets it does not name. */
    private fun refsOf(command: JsonObject): Set<String>? {
        fun text(name: String) = command[name]?.jsonPrimitive?.contentOrNull
        fun ref(text: String?): Set<String>? {
            val kind = text?.substringBefore(':', "") ?: return null
            return when (kind) {
                in targetKinds -> setOf(text)
                // Part and glue keyforms carry channels only; no compared geometry belongs to them.
                "part", "glue" -> emptySet()
                else -> null
            }
        }
        return when (text("op")) {
            "set", "delete" -> ref(text("target"))
            "copy" -> ref(text("target"))?.let { source -> ref(text("destination") ?: text("target"))?.let { source + it } }
            "canvas_geometry" -> text("kind")?.takeIf { it in targetKinds }?.let { kind -> text("id")?.let { setOf("$kind:$it") } }
            "canvas_topology" -> text("id")?.let { setOf("mesh:$it") }
            "canvas_create_glue", "canvas_glue_edit" -> listOfNotNull(text("mesh_a"), text("mesh_b")).takeIf { it.size == 2 }?.mapTo(HashSet()) { "mesh:$it" }
            "structure" -> command["edits"]?.jsonArray?.let { edits ->
                buildSet {
                    for (edit in edits) {
                        val e = edit.jsonObject
                        // Renames, visibility and static appearance leave geometry and parents alone.
                        if (e["action"]?.jsonPrimitive?.contentOrNull !in setOf("rename", "visibility", "static")) return null
                        val kind = e["kind"]?.jsonPrimitive?.contentOrNull ?: return null
                        val id = e["id"]?.jsonPrimitive?.contentOrNull ?: return null
                        if (kind in targetKinds) add("$kind:$id") else if (kind != "part") return null
                    }
                }
            }
            else -> null
        }
    }
}
