package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ClipEvent
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.PuppetModel
import org.umamo.edit.withParametersSyncedFromTree
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Adapters for generators that already accept an authored model. Binding recipes need source-aware adapters. */
internal object RigModifierAlgorithms {
    val swing = RigModifierDefinition("swing", 1, validate = { _, instance ->
        require(instance.targets.all { it.kind == "deformer" || it.kind == "mesh" }) { "A swing modifier targets Warps or meshes" }
        val source = RigSwingEdit.fromJson(instance.config)
        require(!source.baked) { "A baked swing is ordinary content" }
        require(source.targets == instance.targets.map { it.id }) { "Swing targets must match the captured modifier targets" }
    }, composableKinds = setOf("deformer", "mesh", "hierarchy"), evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = RigSwingEdit.fromJson(instance.config)
        val requested = source.copy(id = instance.outputId("swing"), targets = instance.targets.map { it.id },
            motions = source.motions.map { motion -> motion.copy(parameterIds = motion.parameterIds.indices.map {
                instance.outputId("swing:${motion.kind.name.lowercase()}:$it")
            }) })
        val wrapped = SwingAuthoring.wrap(PuppetIr.toPuppet(rig), requested)
        // The native wrapper can allocate a free suffix for ordinary authoring. Modifier identities must be fixed.
        wrapped.commands.forEachIndexed { index, command ->
            checkpoint()
            val expected = "Warp_Swing_${requested.id}" + if (index == 0) "" else "_${index + 1}"
            require(command["id"]?.jsonPrimitive?.content == expected) { "Swing wrapper ID collides with existing content: $expected" }
        }
        val edit = requested.copy(targets = wrapped.targets)
        val (model, issues) = SwingGenerator.applyOne(wrapped.model, edit)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        checkpoint()
        val result = rig.withPuppet(model)
        val available = model.parameters.mapTo(HashSet()) { it.id.raw }
        addPhysics(result, PhysicsGenerator.swingRules(listOf(edit), available).map(Physics3Json::group))
    })

    val simulation = RigModifierDefinition("simulation", 1,
        motionInputs = { RigSimEdit.fromJson(it.config).trainingClips.keys }, validate = { _, instance ->
        require(instance.targets.all { it.kind == "mesh" }) { "A simulation modifier targets meshes" }
    }, composableKinds = setOf("mesh", "hierarchy"), evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = RigSimEdit.fromJson(instance.config)
        require(source.enabled && source.bake != null) { "A simulation modifier needs an enabled, valid bake" }
        require(source.targets == instance.targets.map { it.id }) { "Simulation targets changed; bake again before adding the modifier" }
        val weighted = RigSimulationWeights.apply(PuppetIr.toPuppet(rig), instance.config)
        require(!io.github.psd2live.core.sim.SimBake.stale(weighted, source)) { "Simulation inputs changed; bake again before adding the modifier" }
        require(instance.inputFingerprint == null || instance.inputFingerprint == RigModifierInput.fingerprint(rig)) {
            "${instance.id}: upstream modifier input changed; bake the simulation again"
        }
        val edit = simulationOutputs(instance, source)
        val (model, issues) = SimGenerator.applyOne(weighted, edit)
        require(issues.isEmpty()) { issues.joinToString("; ") }
        checkpoint()
        val groups = simulationPhysics(instance, edit, model.parameters.mapTo(HashSet()) { it.id.raw })
        addPhysics(rig.withPuppet(model), groups.values.map(Physics3Json::group))
    })

    val physics = RigModifierDefinition("physics", 1, evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val edit = RigPhysicsEdit.fromJson(instance.config).copy(id = instance.outputId("physics"))
        val available = rig.parameters.mapTo(HashSet()) { it.id }
        require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available }) {
            "${instance.id}: physics inputs and outputs must reference existing parameters"
        }
        addPhysics(rig, listOf(Physics3Json.group(edit)))
    })

    val motion = RigModifierDefinition("motion", 1, motionOutputs = { setOf(it.outputId("motion")) }, evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = MotionClips.fromJson(instance.config)
        require(source.enabled) { "Disable the modifier to disable its motion" }
        val available = rig.parameters.mapTo(HashSet()) { it.id }
        require(source.curves.isNotEmpty() && source.curves.all { it.parameterId in available }) { "Motion parameters are missing" }
        val file = source.fileStem ?: ("modifier_" + instance.id.encodeToByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
        val clip = Clip(instance.outputId("motion"), source.name, source.exportGroup ?: if (source.loop) "Idle" else source.name,
            file, source.duration, source.fps, source.loop, source.fadeIn.takeUnless { source.runtimeDefaultFades },
            source.fadeOut.takeUnless { source.runtimeDefaultFades },
            source.curves.map(MotionGenerator::curve), source.events.map { ClipEvent(it.time, it.value) })
        require(rig.clips.none { it.id == clip.id || it.file.equals(clip.file, ignoreCase = true) }) { "Motion ID or file collision: ${clip.id} / ${clip.file}" }
        val roles = source.parameterRoles.map { (role, parameters) ->
            require(parameters.all { it in available }) { "Motion role $role references missing parameters" }
            require(rig.parameterRoles.none { it.role == role && it.parameters != parameters }) { "Conflicting motion parameter role $role" }
            io.github.psd2live.format.model.ParameterRole(role, parameters)
        }
        rig.copy(clips = rig.clips + clip, parameterRoles = (roles + rig.parameterRoles).distinct())
    })

    private val native = listOf(swing, simulation, physics, motion)
    val registry = RigModifierRegistry(native + RigSourceModifiers.definitions(null))

    fun evaluator(source: RigModifierSource? = null) = RigModifierEvaluator(
        if (source == null) registry else RigModifierRegistry(native + RigSourceModifiers.definitions(source)),
        physicsOrder = source?.physicsOrder.orEmpty()) { rig, instance, commands ->
        var model = PuppetIr.toPuppet(rig)
        val issues = ArrayList<GeneratedOverrideIssue>()
        val parameterOverrides = HashSet<String>()
        for (command in commands) {
            if (GeneratedOverrides.isOverride(command)) {
                val result = GeneratedOverrides.apply(model, command)
                model = result.model
                issues += result.issues
            } else if (command["op"]?.jsonPrimitive?.content == "structure" && command["generator"]?.jsonPrimitive?.content == "modifier:${instance.id}") {
                val edit = command.getValue("edits").jsonArray.single().jsonObject
                val kind = edit.getValue("kind").jsonPrimitive.content
                val id = edit.getValue("id").jsonPrimitive.content
                try {
                    model = RigAuthoringJournal.apply(model, command)
                    if (kind == "parameter" && edit.getValue("action").jsonPrimitive.content == "update") parameterOverrides += id
                } catch (_: IllegalArgumentException) {
                    issues += GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED, "modifier:${instance.id}",
                        "$kind:$id", emptyMap(), 1, 1, GeneratedOverrideOrphanReason.SUPERSEDED)
                }
            } else if (command["op"]?.jsonPrimitive?.content in setOf("parameter_keys", "delete") &&
                command["generator"]?.jsonPrimitive?.content == "modifier:${instance.id}") {
                val changed = try {
                    RigAuthoringJournal.apply(model, command)
                } catch (_: IllegalArgumentException) {
                    model
                } catch (_: NoSuchElementException) {
                    model
                }
                val target = command["target"]?.jsonPrimitive?.content
                val parameter = command.getValue("parameter").jsonPrimitive.content
                if (changed === model) {
                    issues += GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED, "modifier:${instance.id}",
                        target ?: "parameter:$parameter", emptyMap(), 1, 1, GeneratedOverrideOrphanReason.MISSING_KEYFORM)
                } else if (target == null) parameterOverrides += parameter
                model = changed
            } else model = RigAuthoringJournal.apply(model, command)
        }
        val updated = rig.withPuppet(model)
        ModifierOverrideResult(updated, issues, updated.parameters.filter { it.id in parameterOverrides }.associateBy { it.id })
    }

    internal fun simulationOutputs(instance: RigModifierInstance, source: RigSimEdit): RigSimEdit =
        source.copy(outputs = requireNotNull(source.bake).parameters.associateWith { parameter ->
            val previous = source.outputs[parameter]
            (previous ?: io.github.psd2live.core.sim.SimOutput()).copy(id = instance.outputId("simulation:$parameter"))
        })

    internal fun simulationPhysics(instance: RigModifierInstance, edit: RigSimEdit, available: Set<String>): Map<String, RigPhysicsEdit> {
        val valid = SimGenerator.physicsRules(listOf(edit), available).associateBy { it.id }
        return SimGenerator.writtenPendulums(edit).mapIndexedNotNull { index, group ->
            valid[group.id]?.let { group.id to it.copy(id = instance.outputId("physics:$index")) }
        }.toMap()
    }

    private fun addPhysics(rig: RigIR, groups: List<PhysicsGroup>): RigIR {
        val ids = rig.physics.groups.mapTo(HashSet()) { it.id }
        require(groups.map { it.id }.distinct().size == groups.size && groups.none { it.id in ids }) { "Physics group ID collision" }
        val claimed = rig.physics.groups.flatMapTo(HashSet()) { group -> group.outputs.map { it.parameter } }
        for (group in groups) for (output in group.outputs) require(claimed.add(output.parameter)) {
            "Multiple physics groups drive ${output.parameter}"
        }
        return rig.copy(physics = rig.physics.copy(groups = rig.physics.groups + groups))
    }

}

/** Keep sidecars and export-only fields while replacing all fields owned by the editable puppet. */
internal fun RigIR.withPuppet(model: PuppetModel): RigIR = PuppetIr.toIr(model.withParametersSyncedFromTree()).copy(
    textures = textures, physics = physics, clips = clips, parameterRoles = parameterRoles,
    restPose = restPose, advanced = advanced, hitAreas = hitAreas,
)
