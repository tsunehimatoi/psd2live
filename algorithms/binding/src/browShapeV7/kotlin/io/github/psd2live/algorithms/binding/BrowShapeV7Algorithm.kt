package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*

/** Reconstructs fitted and shaped brow states from original numerical source observations. */
class BrowShapeV7Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        BrowShapeV7Source.validate(context)
        val captured = BrowSourceProvidersV1.captured(input, instance, BrowSourceProvidersV1.Stage.SHAPE)
        val fitted = captured.single { it.type == "brow/fitted" }
        val face = captured.single { it.type == "face/fitted" }
        val eye = captured.single { it.type == "eye/fitted" }
        val source = BrowShapeV7Source.source(context, checkpoint)
        require(source.targets.size == 1 && source.eyeProvider == eye.instanceId &&
            source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets } &&
            source.targets.values.single().eye != null && source.targets.values.single().fit.side == Side.LEFT)
        val head = context.values.getValue("head_turn").jsonObject
        require(head.getValue("provider") == JsonPrimitive(face.instanceId))
        val shell = head.getValue("shell").jsonArray.map { it.jsonPrimitive.float.also { f -> require(f.isFinite()) } }
        require(shell.size == 7 && shell[2] > 0f && shell[3] > 0f)
        val turn = HeadTurn(shell[0], shell[1], shell[2], shell[3], shell[4], shell[5], shell[6], checkpoint)
        val layers = context.values.getValue("source_ancestry").jsonObject.getValue("layers").jsonArray.map { it.jsonObject }
        require(layers.map { it.getValue("id").jsonPrimitive.content }.let { ids ->
            ids.toSet() == instance.targets.mapTo(hashSetOf()) { it.id } && ids.distinct().size == ids.size
        }) { "Brow shape v7 requires its complete captured source inventory" }
        require(layers.count { it.getValue("tag").jsonPrimitive.content == "MOUTH" } == 1 &&
            layers.none { it.getValue("tag").jsonPrimitive.content == "MOUTH_OPEN" }) {
            "Brow shape v7 requires the fitted v5 whole-mouth cohort"
        }
        val branch = BrowShapeV7Source.branch(context, checkpoint)
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val names = context.values.getValue("mesh_names").jsonObject
        require(nativeIds.keys == source.targets.keys && names.keys == source.targets.keys)
        val declared = parameters(source)
        val reused = declared.mapNotNull { parameter -> input.parameter(parameter.id)?.also {
            require(it.copy(name = parameter.name) == parameter) { "Incompatible brow shape parameter ${parameter.id}" }
        } }
        val additions = declared.filter { next -> reused.none { it.id == next.id } }
        val controls = input.parameters + additions
        val lift = knob(fitted.config, "lift", 45f, .2f..1f)
        val strength = knob(instance.config, "strength", 100f, 0f..2f)
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        val changes = source.targets.map { (id, target) ->
            checkpoint()
            val live = requireNotNull(input.mesh(id))
            val side = if (target.fit.side == Side.LEFT) "L" else "R"
            require(input.parameter("ParamBrow${side}Y")?.let {
                it.min == -1f && it.max == 1f && it.default == 0f && !it.blend
            } == true) { "Brow shape v7 requires the ordinary fitted height control" }
            fun lower(shaped: Boolean, parameters: List<Parameter>) = PuppetIr.toPuppet(BrowSourceLoweringV1.lower(
                input.canvas, parameters, live, target, nativeIds.getValue(id).jsonPrimitive.content,
                names.getValue(id).jsonPrimitive.content, branch, turn, lift,
                (if (shaped) BrowOptions(shape = true, shapeStrength = strength) else BrowOptions()), false, false,
                context.values.getValue("turn_name").jsonPrimitive.content,
                context.values.getValue("shape_name").jsonPrimitive.content, emptyList(), emptyList(), checkpoint))
            val before = lower(false, input.parameters)
            val after = lower(true, controls)
            BrowSourceTransitionV1.merge(input, instance, owners, live, before, after, controls, checkpoint)
        }
        val tree = if (additions.isEmpty()) null else {
            val id = instance.outputId("parameter-folder:ParamGroupBrows")
            val children = additions.map { ParameterNode.Param(it.id) }
            val root = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
            val (next, found) = append(root, id, children)
            ModifierFieldChange(input.parameterTree, if (found) next else next + ParameterNode.Group(id, source.groupName, true, children))
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions, reused = reused),
            meshes = ModifierObjectChanges(updated = changes.flatMap { it.meshes.updated }),
            deformers = ModifierObjectChanges(added = changes.flatMap { it.deformers.added }, updated = changes.flatMap { it.deformers.updated }),
            parameterTree = tree)
    }

    private fun append(nodes: List<ParameterNode>, id: String, children: List<ParameterNode>): Pair<List<ParameterNode>, Boolean> {
        var found = false
        val result = nodes.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == id) { found = true; node.copy(children = node.children + children) }
            else {
                val (nested, matched) = append(node.children, id, children)
                found = found || matched
                if (matched) node.copy(children = nested) else node
            }
        }
        return result to found
    }

    private fun knob(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also { require(it in range) }

    companion object {
        const val TYPE = "brow.shape"
        const val VERSION = 7
        const val ENTRYPOINT = "builtin.62726f772e7368617065.v7/shape"
        val requires = BrowSourceProvidersV1.required(BrowSourceProvidersV1.Stage.SHAPE)
        val observed = BrowSourceProvidersV1.observed(BrowSourceProvidersV1.Stage.SHAPE)
        val writes = setOf("mesh", "deformer", "parameter", "hierarchy")
        fun parameters(source: BrowShapeInputs): List<Parameter> {
            val sides = source.targets.values.mapTo(hashSetOf()) { it.fit.side }
            val suffixes = listOf("X", "Angle", "Form")
            return source.names.mapIndexed { index, name ->
                val left = index % 2 == 0
                val suffix = suffixes[index / 2]
                val blend = suffix != "Angle" && (if (left) Side.LEFT else Side.RIGHT) in sides
                Parameter("ParamBrow${if (left) "L" else "R"}$suffix", name, -1f, 1f, 0f, blend = blend,
                    keys = if (blend) Floats.values(-1f, 0f, 1f) else null)
            }
        }
    }
}

object BrowShapeV7Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowShapeV7Algorithm.ENTRYPOINT to BrowShapeV7Algorithm.VERSION) to BrowShapeV7Algorithm()))
}
