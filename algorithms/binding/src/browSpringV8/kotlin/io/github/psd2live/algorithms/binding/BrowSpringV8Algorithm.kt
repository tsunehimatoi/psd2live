package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId

/** Adds only the source brow's spring shape. Existing lowered carriers are witnessed, never written. */
class BrowSpringV8Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.isNotEmpty() &&
            instance.targets.all { it.kind == "mesh" } && instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        BrowSpringV8Source.validate(context)
        val captured = BrowSourceProvidersV1.captured(input, instance, BrowSourceProvidersV1.Stage.SPRING)
        val fitted = captured.single { it.type == "brow/fitted" }
        val face = captured.single { it.type == "face/fitted" }
        val eye = captured.single { it.type == "eye/fitted" }
        val shape = captured.single { it.type == "brow.shape" }
        val blink = captured.single { it.type == "brow.blink" }
        val source = source(context, checkpoint)
        require(source.targets.size == 1 && source.eyeProvider == eye.instanceId &&
            source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets } && source.targets.values.single().eye != null && source.targets.values.single().fit.side == Side.LEFT)
        val head = context.values.getValue("head_turn").jsonObject
        require(head.getValue("provider") == JsonPrimitive(face.instanceId))
        val shell = head.getValue("shell").jsonArray.map { it.jsonPrimitive.float.also { f -> require(f.isFinite()) } }
        require(shell.size == 7 && shell[2] > 0f && shell[3] > 0f)
        val turn = HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        val layerIds = context.values.getValue("source_ancestry").jsonObject.getValue("layers").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        require(layerIds.toSet() == instance.targets.mapTo(hashSetOf()) { it.id } && layerIds.distinct().size == layerIds.size) {
            "Brow spring v8 requires the complete captured source inventory"
        }
        val branch = BrowSpringV8Source.branch(context, checkpoint)
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val names = context.values.getValue("mesh_names").jsonObject
        require(nativeIds.keys == source.targets.keys && names.keys == source.targets.keys)
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val shapeName = context.values.getValue("shape_name").jsonPrimitive.content
        val parameters = input.parameters
        val declared = BrowSpringV8Algorithm.parameters(context)
        val reused = declared.mapNotNull { parameter -> input.parameter(parameter.id)?.also {
            require(it.copy(name = parameter.name) == parameter) { "Incompatible brow spring parameter ${parameter.id}" }
        } }
        val added = declared.filterNot { parameter -> reused.any { it.id == parameter.id } }
        val controls = parameters + added
        val lift = knob(fitted.config, "lift", 45f, .2f..1f)
        val options = BrowOptions(shape = true, shapeStrength = knob(shape.config, "strength", 100f, 0f..2f),
            blink = true, blinkDip = knob(blink.config, "dip", 100f, 0f..2f))
        val wide = captured.any { it.type == "eye.wide" }
        val smile = captured.any { it.type == "eye.smile" }
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        // Real ordinary eye/brow axes prevent local lowering from retyping an upstream parameter.
        // Their positions are irrelevant to the brow; detached copies are read-only axis witnesses.
        val ordinaryAxes = setOf("ParamEyeLOpen", "ParamEyeROpen", "ParamEyeBallX", "ParamEyeBallY",
            "ParamBrowLY", "ParamBrowRY", "ParamBrowLAngle", "ParamBrowRAngle")
        val witnesses = instance.targets.map { requireNotNull(input.mesh(it.id)) }
        val ordinaryMeshes = witnesses.filter { mesh -> mesh.id !in source.targets &&
            mesh.offsets?.axes.orEmpty().any { it.parameter in ordinaryAxes } }.map { it.copy(parent = null, shapes = emptyList()) }
        val ordinaryWarps = instance.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
            .filterIsInstance<Deformer.Warp>().filter { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes } &&
                branch.none { it.id == warp.id } }.map { it.copy(parent = null, shapes = emptyList()) }
        require(instance.targets.flatMap { input.parentsOfMesh(it.id) }.all { node ->
            when (node) {
                is Deformer.Warp -> node.shapes.isEmpty()
                is Deformer.Rotation -> node.shapes.isEmpty()
            }
        }) { "Brow spring v8 does not support ancestor blend shapes" }
        val updated = source.targets.map { (id, target) ->
            checkpoint()
            val live = requireNotNull(input.mesh(id))
            val ancestry = input.parentsOfMesh(id)
            val existing = ancestry.associateBy { it.id }
            val side = if (target.fit.side == Side.LEFT) "L" else "R"
            for (suffix in listOf("Y", "Angle")) require(input.parameter("ParamBrow$side$suffix")?.let {
                !it.blend && it.min == -1f && it.max == 1f && it.default == 0f
            } == true) { "Brow spring v8 requires ordinary fitted brow height and angle" }
            fun lower(spring: Boolean) = BrowSourceLoweringV1.lower(input.canvas, controls, live, target,
                nativeIds.getValue(id).jsonPrimitive.content, names.getValue(id).jsonPrimitive.content, branch,
                turn, lift, options.copy(spring = spring), wide, smile, turnName, shapeName,
                ordinaryMeshes, ordinaryWarps, checkpoint)
            val before = lower(false)
            val after = lower(true)
            require(before.deformers == after.deformers) { "Brow spring v8 cannot change existing carriers" }
            val previous = before.meshes.single()
            val next = after.meshes.single()
            val springId = "ParamBrow${side}Spring"
            require(previous.parent == next.parent && previous.geometry == next.geometry && previous.offsets == next.offsets &&
                previous.channels == next.channels && next.shapes.count { it.parameter == springId } == 1 &&
                next.shapes.filterNot { it.parameter == springId } == previous.shapes && previous.shapes.none { it.parameter == springId }) {
                "Brow spring v8 may add exactly its spring shape without changing ordinary motion"
            }
            val aliases = before.deformers.associate { carrier ->
                val matches = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                    ScaffoldBinding.deformerId(it, id, carrier.id, shared = true)
                }.distinct().filter { it in existing }
                require(matches.size == 1) { "Brow spring v8 cannot resolve one upstream owner for ${carrier.id}" }
                DeformerId(carrier.id) to DeformerId(matches.single())
            }
            require(aliases.values.map { it.raw }.toSet() == existing.keys && aliases.values.distinct().size == aliases.size) {
                "Brow spring v8 requires the exact complete preceding brow branch"
            }
            val parts = before.deformers.mapNotNull { carrier ->
                val part = existing.getValue(aliases.getValue(DeformerId(carrier.id)).raw).part
                carrier.part?.let { PartId(it) to PartId(requireNotNull(part)) }
            }.toMap()
            val remap = ModelIdRemap(deformers = aliases, parts = parts)
            for (carrier in PuppetIr.toPuppet(before).deformers) {
                val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                require(existing.getValue(expected.id) == expected) { "Brow spring v8 requires the exact preceding brow carrier: ${expected.id}" }
            }
            val expected = PuppetIr.meshToIr(remap.drawable(PuppetIr.toPuppet(before).drawables.single()))
            require(live.parent == expected.parent && live.geometry == expected.geometry && live.offsets == expected.offsets &&
                live.channels == expected.channels && live.shapes == expected.shapes) {
                "Brow spring v8 requires the exact preceding brow topology, grid and shapes: $id"
            }
            val result = PuppetIr.meshToIr(remap.drawable(PuppetIr.toPuppet(after).drawables.single()))
            ModifierObjectUpdate(live, live.copy(shapes = result.shapes))
        }
        val tree = if (added.isEmpty()) null else {
            val before = input.parameterTree
            val roots = before.ifEmpty { parameters.map { ParameterNode.Param(it.id) } }
            val id = instance.outputId("parameter-folder:ParamGroupPhysics")
            val children = added.map { ParameterNode.Param(it.id) }
            val next = append(roots, id, children) ?: (roots + ParameterNode.Group(id,
                context.values.getValue("physics_group").jsonPrimitive.content, true, children))
            ModifierFieldChange(before, next)
        }
        val physics = if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) ModifierObjectChanges()
        else BrowSpringPhysics.changes(input.physics, instance, controls.mapTo(hashSetOf()) { it.id },
            context.values.getValue("physics_names").jsonObject.mapKeys { Side.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.content })
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added, reused = reused),
            meshes = ModifierObjectChanges(updated = updated), parameterTree = tree, physicsGroups = physics)
    }

    private fun append(nodes: List<ParameterNode>, id: String, children: List<ParameterNode>): List<ParameterNode>? {
        for ((index, node) in nodes.withIndex()) {
            if (node !is ParameterNode.Group) continue
            val next = if (node.id == id) node.children + children else append(node.children, id, children) ?: continue
            return nodes.toMutableList().apply { set(index, node.copy(children = next)) }
        }
        return null
    }

    private fun knob(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also { require(it in range) }

    companion object {
        const val TYPE = "brow.spring"
        const val VERSION = 8
        const val ENTRYPOINT = "builtin.62726f772e737072696e67.v8/spring"
        val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "brow.shape" to 6, "brow.lid-coupling" to 8, "brow.blink" to 6)
        val requires = requiredVersions.keys
        // Gaze v3 adds independent iris-ancestor blend shapes, outside this source-only cohort.
        val eyeAddons = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4, "eye.lid-fade" to 3)
        val optionalVersions = eyeAddons + mapOf("hair/legacy" to 2, "hair/chains" to 2,
            "mouth/fitted" to 3, "mouth.outline" to 7, "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7,
            "face.skin-clip" to 1, "eye.see-through" to 1, "brow.see-through" to 1)
        fun acceptsVersion(type: String, version: Int) = BrowSourceProvidersV1.acceptsVersion(BrowSourceProvidersV1.Stage.SPRING, type, version)
        val supported = requires + optionalVersions.keys
        val observed = supported + setOf(TYPE, "face.depth-turn", "mouth.cheek-lid", "face/legacy", "eye/legacy",
            "brow/legacy", "mouth/legacy", "expression.toggles", "eye.gaze-lid")
        val writes = setOf("mesh", "parameter", "hierarchy", "physics")
        fun source(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}) = BrowSpringV8Source.source(context, checkpoint)

        fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) =
            BrowSourceProvidersV1.validate(providers, instance, BrowSourceProvidersV1.Stage.SPRING)
        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val source = source(context)
            val sides = source.targets.values.mapTo(hashSetOf()) { it.fit.side }
            val names = context.values.getValue("spring_names").jsonObject
            return listOf(Side.LEFT, Side.RIGHT).map { side ->
                val blend = side in sides
                Parameter("ParamBrow${if (side == Side.LEFT) "L" else "R"}Spring", names.getValue(side.name).jsonPrimitive.content,
                    -1f, 1f, 0f, blend = blend, keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }
    }
}

object BrowSpringV8Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowSpringV8Algorithm.ENTRYPOINT to BrowSpringV8Algorithm.VERSION) to BrowSpringV8Algorithm()))
}
