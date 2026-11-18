package io.github.psd2live.application

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.serialization.json.*

/** Edit the evaluated topology, including the keyforms, paths, glue and painted groups attached to it. */
internal object WorkspaceMeshPointEdits {
    private fun structural(command: JsonObject): Boolean = when (command["op"]?.jsonPrimitive?.content) {
        "canvas_topology" -> true
        "canvas_geometry" -> command["kind"]?.jsonPrimitive?.content == "mesh" && command["key"]?.jsonObject?.isEmpty() == true
        else -> false
    }

    /** Only the terminal author stage gained mesh edits. Earlier simulations still see their original input. */
    fun appended(before: WorkspaceDocument, after: WorkspaceDocument): Boolean {
        val old = before.rigEdits.modifierStack ?: return false
        val next = after.rigEdits.modifierStack ?: return false
        if (old === next) return false
        val last = next["instances"]?.jsonArray?.lastOrNull()?.jsonObject ?: return false
        if (last["type"]?.jsonPrimitive?.content != ModifierAuthorEdits.TYPE) return false
        val id = last.getValue("id").jsonPrimitive.content
        val prior = old["overrides"]?.jsonObject?.get(id)?.jsonArray.orEmpty()
        val commands = next["overrides"]?.jsonObject?.get(id)?.jsonArray.orEmpty()
        return commands.size > prior.size && commands.take(prior.size) == prior &&
            commands.drop(prior.size).all { "mesh_baseline" in it.jsonObject }
    }

    fun simulationInputsUnchanged(before: WorkspaceDocument, after: WorkspaceDocument): Boolean {
        if (!appended(before, after) || before.copy(rigEdits = after.rigEdits) != after ||
            before.rigEdits.copy(modifierStack = after.rigEdits.modifierStack) != after.rigEdits) return false
        val old = RigModifierStack.fromJson(requireNotNull(before.rigEdits.modifierStack))
        val next = RigModifierStack.fromJson(requireNotNull(after.rigEdits.modifierStack))
        return WorkspaceModifierSimulation.unchangedPrefix(old, next) > next.instances.indexOfLast { it.type == "simulation" }
    }

    /** A newly authored mesh retains baked motion until the next paint or bake. When the solver needs the
     * edited surface, move these same records in front of the live simulations and migrate their fallback
     * weights with the topology reducer. Never reorder across another geometry producer. */
    fun simulationInput(model: RigPreviewModel, targets: Set<String>): RigModifierStack? {
        val evaluation = model.modifierEvaluation ?: return null
        val stack = evaluation.stack
        val tail = stack.instances.takeLastWhile { instance ->
            instance.enabled && instance.type == ModifierAuthorEdits.TYPE && instance.id in stack.appliedNodes &&
                stack.overrides[instance.id].orEmpty().let { it.isNotEmpty() && it.all { edit -> "mesh_baseline" in edit } }
        }
        val edits = tail.flatMap { stack.overrides.getValue(it.id) }
        val meshes = edits.mapTo(hashSetOf()) { it.getValue("id").jsonPrimitive.content }
        if (targets.none { it in meshes }) return null
        val start = stack.instances.indexOfFirst { it.type == "simulation" && it.id !in stack.appliedNodes }
        if (start < 0) return null
        val simulations = stack.instances.subList(start, stack.instances.size - tail.size)
        require(simulations.all { it.type == "simulation" && it.id !in stack.appliedNodes }) {
            "The edited mesh crosses another modifier after the simulation. Place its mesh edits at the simulation input before baking or painting."
        }
        val input = PuppetIr.toPuppet(if (start == 0) evaluation.authored else evaluation.outputs[start - 1])
        fun project(puppet: org.umamo.runtime.model.PuppetModel) = edits.fold(puppet) { current, edit -> RigAuthoringJournal.apply(current, edit) }
        val surface = project(input)
        for (id in meshes + targets) {
            val projected = surface.drawables.single { it.id.raw == id }
            val shown = model.rig.puppet.drawables.single { it.id.raw == id }
            require(projected.parentDeformerId == shown.parentDeformerId && projected.mesh!!.positions.contentEquals(shown.mesh!!.positions) &&
                projected.mesh!!.indices.contentEquals(shown.mesh!!.indices)) { "Cannot match the edited vertices of $id at the simulation input" }
        }
        val updated = simulations.map { sim ->
            val weights = sim.config["automatic_weights"]?.jsonArray ?: return@map sim
            if (weights.none { it.jsonObject.getValue("target").jsonPrimitive.content.removePrefix("mesh:") in meshes }) return@map sim
            val groups = project(RigSimulationWeights.apply(input.copy(vertexGroups = emptyList()), sim.config)).vertexGroups
                .associateBy { it.drawableId.raw to it.name }
            val migrated = weights.map { raw ->
                val command = raw.jsonObject
                val mesh = command.getValue("target").jsonPrimitive.content.removePrefix("mesh:")
                val group = groups[mesh to command.getValue("name").jsonPrimitive.content]
                if (mesh !in meshes || group == null) raw else JsonObject(command + ("weights" to JsonArray(group.weights.map(::JsonPrimitive))))
            }
            sim.copy(config = JsonObject(sim.config + ("automatic_weights" to JsonArray(migrated))))
        }
        return stack.copy(instances = stack.instances.take(start) + tail + updated)
    }

    /** Weights painted on a mesh that has already been edited must use that vertex numbering.
     * Ordinary weight gestures continue through the existing weight journal. */
    fun recordWeights(document: WorkspaceDocument, model: RigPreviewModel, edits: JsonArray): WorkspaceDocument? {
        val evaluation = model.modifierEvaluation ?: return null
        if (edits.isEmpty() || edits.any { it.jsonObject["op"]?.jsonPrimitive?.content !in weightOperations } ||
            GeometryEditStages.stage(edits) != "auto") return null
        var stack = evaluation.stack
        val targets = edits.mapTo(linkedSetOf()) { it.jsonObject.getValue("target").jsonPrimitive.content.removePrefix("mesh:") }
        val edited = stack.instances.any { entry ->
            entry.type == ModifierAuthorEdits.TYPE && entry.enabled && stack.overrides[entry.id].orEmpty().any {
                "mesh_baseline" in it && it.getValue("id").jsonPrimitive.content in targets
            }
        }
        if (!edited) return null
        val commands = RigAuthoringJournal.compile(model.rig.puppet, GeometryEditStages.clean(edits)).second
        if (commands.isEmpty()) return document
        val moved = simulationInput(model, targets)
        if (moved != null) stack = moved
        val boundary = stack.instances.indexOfFirst { it.type == "simulation" && it.id !in stack.appliedNodes }
            .takeIf { it >= 0 } ?: stack.instances.size
        if (moved == null && boundary < stack.instances.size) {
            val input = if (boundary == 0) evaluation.authored else evaluation.outputs[boundary - 1]
            val shown = requireNotNull(model.rig.modifierOutput)
            require(targets.all { id ->
                val a = input.meshes.singleOrNull { it.id == id }?.geometry
                val b = shown.meshes.singleOrNull { it.id == id }?.geometry
                a != null && b != null && a.positions.size == b.positions.size && a.indices == b.indices && a.uvs == b.uvs
            }) { "Cannot match the painted vertices at the simulation input" }
            for (index in boundary until stack.instances.size) {
                if (stack.instances[index].type == "simulation") continue
                val before = if (index == 0) evaluation.authored else evaluation.outputs[index - 1]
                val after = evaluation.outputs[index]
                require(before.authoring.vertexGroups.filter { it.mesh in targets } == after.authoring.vertexGroups.filter { it.mesh in targets }) {
                    "A later modifier rewrites the weight groups on the edited mesh"
                }
            }
        }
        val last = stack.instances.getOrNull(boundary - 1)
        val owner = if (last?.type == ModifierAuthorEdits.TYPE && last.enabled && last.id in stack.appliedNodes &&
            last.targets.map { it.id }.containsAll(targets)) last.id else {
            val appended = ModifierAuthorEdits.append(stack, targets.map { ModifierTarget("mesh", it) })
            val entry = appended.instances.last()
            stack = appended.copy(instances = stack.instances.take(boundary) + entry + stack.instances.drop(boundary))
            entry.id
        }
        val updated = stack.withOverrides(owner, stack.overrides[owner].orEmpty() + commands)
        return document.copy(rigEdits = document.rigEdits.copy(modifierStack = updated.toJson()))
    }

    private val weightOperations = setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE, VertexGroupJournal.RULE)

    /** Commands in a mixed gesture can depend on the previous command's result. Keep one transaction,
     * rebuilding at each change of stage so later commands see the mesh they are indexed against. */
    private fun mixed(document: WorkspaceDocument, model: RigPreviewModel, edits: JsonArray): WorkspaceDocument {
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        var candidate = document
        var source = model
        var start = 0
        try {
            while (start < edits.size) {
                val points = structural(edits[start].jsonObject)
                var end = start + 1
                while (end < edits.size && structural(edits[end].jsonObject) == points) end++
                candidate = WorkspaceDocumentEdits.journal(candidate, source, JsonArray(edits.subList(start, end)))
                if (end < edits.size) source = pipeline.buildPreview(candidate.source, candidate.config(), previousAtlas = source.atlas)
                start = end
            }
        } finally {
            pipeline.modifierBuildOwner.close()
        }
        return candidate
    }

    fun record(document: WorkspaceDocument, model: RigPreviewModel, edits: JsonArray): WorkspaceDocument? {
        val evaluated = model.modifierEvaluation ?: return null
        if (edits.none { structural(it.jsonObject) } || GeometryEditStages.stage(edits) != "auto") return null
        val ids = edits.map { it.jsonObject }.filter(::structural).mapTo(linkedSetOf()) { it.getValue("id").jsonPrimitive.content }
        if (evaluated.entries.none { e -> e.status == ModifierStatus.ACTIVE && (e.instance.targets.any { it.kind == "mesh" && it.id in ids } ||
                    e.contributions.any { it.kind == "mesh" && it.id in ids }) })
            return null
        if (!edits.all { structural(it.jsonObject) }) return mixed(document, model, edits)
        val shown = model.rig.puppet
        val commands = RigAuthoringJournal.compile(shown, GeometryEditStages.clean(edits)).second
        if (commands.isEmpty()) return document
        // Keep the original inputs to live and applied generators. At this stage the ordinary
        // topology reducer can migrate all of their vertex-indexed output in one operation.
        var stack = evaluated.stack
        val last = stack.instances.lastOrNull()
        if (last?.type != ModifierAuthorEdits.TYPE || last.id !in stack.appliedNodes || !last.enabled ||
            !last.targets.map { it.id }.containsAll(ids) || stack.overrides[last.id].orEmpty().any { "mesh_baseline" !in it }) {
            stack = ModifierAuthorEdits.append(stack, ids.map { ModifierTarget("mesh", it) })
        }
        val owner = stack.instances.last().id
        var current = shown
        val guarded = commands.map { command ->
            val drawable = current.drawables.single { it.id.raw == command.getValue("id").jsonPrimitive.content }
            val baseline = RasterMeshJournal.fingerprint(requireNotNull(drawable.mesh))
            current = RigAuthoringJournal.apply(current, command)
            JsonObject(command + mapOf("mesh_baseline" to JsonPrimitive(baseline),
                "mesh_parent" to (drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)))
        }
        stack = stack.withOverrides(owner, stack.overrides[owner].orEmpty() + guarded)
        return document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
    }
}
