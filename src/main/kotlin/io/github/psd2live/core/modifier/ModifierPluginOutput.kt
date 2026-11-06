package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/** Candidate-only model commands. Transport and process lifetime are owned by ModifierPluginWorkers. */
internal object ModifierPluginOutput {
    val operations = setOf("physics", "motion", "mesh_opacity", "structure", "set", "parameter_keys", "delete", "copy", "warp",
        "canvas_geometry", "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit",
        "path_put", "path_delete", "vertex_group_put", "vertex_group_delete")
    val components = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue", "textures", "hierarchy", "relations",
        "canvas", "authoring", "advanced", "hit_areas", "rest_pose", "*")
    fun sidecars(before: RigIR, after: RigIR): List<ModifierContribution> = buildList {
        fun changed(kind: String, id: String, a: Any?, b: Any?) {
            if (a != b) add(ModifierContribution(kind, id, ModifierChange.MODIFIED))
        }
        changed("canvas", "canvas", before.canvas, after.canvas)
        changed("textures", "textures", before.textures, after.textures)
        changed("authoring", "authoring", before.authoring, after.authoring)
        changed("advanced", "advanced", before.advanced, after.advanced)
        changed("hit_areas", "hit-areas", before.hitAreas, after.hitAreas)
        changed("rest_pose", "rest-pose", before.restPose, after.restPose)
        changed("hierarchy", "render-root", before.renderRoot, after.renderRoot)
        changed("hierarchy", "root-part", before.rootPart, after.rootPart)
        changed("physics", "fps", before.physics.fps, after.physics.fps)
    }
    fun apply(rig: RigIR, instance: RigModifierInstance, algorithm: ModifierPluginAlgorithm, commands: JsonArray, checkpoint: () -> Unit): RigIR {
        require(commands.size <= 10000) { "Too many plugin commands" }
        var output = rig
        for (raw in commands) {
            checkpoint()
            val command = raw.jsonObject
            when (command["op"]?.jsonPrimitive?.content) {
                "physics", "motion" -> {
                    val definition = if (command.getValue("op").jsonPrimitive.content == "physics") RigModifierAlgorithms.physics else RigModifierAlgorithms.motion
                    output = definition.plugin.evaluate(output, instance.copy(config = command.getValue("config").jsonObject), checkpoint)
                }
                "mesh_opacity" -> {
                    val id = command.getValue("id").jsonPrimitive.content
                    require(instance.targets.any { it.kind == "mesh" && it.id == id } && ("mesh" in algorithm.writes || "*" in algorithm.writes)) { "Plugin write is outside declared targets" }
                    val opacity = command.getValue("value").jsonPrimitive.float
                    require(opacity.isFinite() && opacity in 0f..1f) { "Invalid plugin opacity" }
                    output = output.copy(meshes = output.meshes.map { if (it.id == id) it.copy(opacity = opacity) else it })
                }
                else -> {
                    require(command["op"]?.jsonPrimitive?.content in operations) { "Unsupported plugin command" }
                    output = output.withPuppet(RigAuthoringJournal.apply(PuppetIr.toPuppet(output), command))
                }
            }
        }
        return validate(rig, output, instance, algorithm)
    }

    fun validate(rig: RigIR, output: RigIR, instance: RigModifierInstance, algorithm: ModifierPluginAlgorithm): RigIR {
        fun field(kind: String, before: Any?, after: Any?) {
            require(before == after || kind in algorithm.writes || "*" in algorithm.writes) { "Plugin wrote undeclared $kind data" }
        }
        field("canvas", rig.canvas, output.canvas)
        field("textures", rig.textures, output.textures)
        field("authoring", rig.authoring, output.authoring)
        field("advanced", rig.advanced, output.advanced)
        field("hit_areas", rig.hitAreas, output.hitAreas)
        field("rest_pose", rig.restPose, output.restPose)
        field("hierarchy", rig.renderRoot, output.renderRoot)
        field("hierarchy", rig.rootPart, output.rootPart)
        field("physics", rig.physics.fps, output.physics.fps)
        val changes = RigModifierEvaluator.contributions(rig, output)
        require(changes.all { it.kind in algorithm.writes || "*" in algorithm.writes }) { "Plugin wrote undeclared object kinds" }
        require(changes.filter { it.change == ModifierChange.ADDED && it.kind !in setOf("relations", "hierarchy") }.all {
            instance.localOutputName(it.id) != null || it.kind == "parameter" && algorithm.parameters.any { p -> p.id == it.id } ||
                algorithm.derivedOutputs.any { rule -> rule.matches(instance, it.kind, it.id) }
        }) { "Plugin outputs must use instance-owned deterministic IDs or declared shared parameters" }
        return output
    }
}
