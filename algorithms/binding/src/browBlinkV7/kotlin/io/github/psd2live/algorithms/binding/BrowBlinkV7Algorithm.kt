package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/** Reconstruct both blink states from raw source observations; no host motion is transmitted. */
class BrowBlinkV7Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.isNotEmpty() &&
            instance.targets.all { it.kind == "mesh" } && instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        BrowBlinkV7Source.validate(context)
        val captured = BrowSourceProvidersV1.captured(input, instance, BrowSourceProvidersV1.Stage.BLINK)
        val fitted = captured.single { it.type == "brow/fitted" }
        val face = captured.single { it.type == "face/fitted" }
        val eye = captured.single { it.type == "eye/fitted" }
        val shape = captured.single { it.type == "brow.shape" }
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
            "Brow blink v7 requires the complete captured source inventory"
        }
        val branch = BrowBlinkV7Source.branch(context, checkpoint)
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val names = context.values.getValue("mesh_names").jsonObject
        require(nativeIds.keys == source.targets.keys && names.keys == source.targets.keys)
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val shapeName = context.values.getValue("shape_name").jsonPrimitive.content
        val parameters = input.parameters
        val lift = knob(fitted.config, "lift", 45f, .2f..1f)
        val options = BrowOptions(shape = true, shapeStrength = knob(shape.config, "strength", 100f, 0f..2f),
            blinkDip = knob(instance.config, "dip", 100f, 0f..2f))
        val wide = captured.any { it.type == "eye.wide" }
        val smile = captured.any { it.type == "eye.smile" }
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        require(instance.targets.flatMap { input.parentsOfMesh(it.id) }.all { node ->
            when (node) {
                is Deformer.Warp -> node.shapes.isEmpty()
                is Deformer.Rotation -> node.shapes.isEmpty()
            }
        }) { "Brow blink v7 does not support ancestor blend shapes" }
        val changes = source.targets.map { (id, target) ->
            checkpoint()
            val live = requireNotNull(input.mesh(id))
            validateControls(input, target, wide, smile)
            val ordinaryAxes = setOfNotNull(EyeModules.open(target.fit.side).raw,
                EyeModules.smile(target.fit.side).raw.takeIf { smile })
            // Actual eye meshes and carrier axes are frozen read-only witnesses, never borrowed motion.
            val witnessMeshes = eye.targets.filter { it.id != live.id }.mapNotNull { input.mesh(it.id) }
                .filter { mesh -> mesh.offsets?.axes.orEmpty().any { it.parameter in ordinaryAxes } }
                .map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
            val witnessWarps = eye.targets.filter { it.id != live.id }.flatMap { input.parentsOfMesh(it.id) }
                .distinctBy { it.id }.filterIsInstance<Deformer.Warp>()
                .filter { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in ordinaryAxes } }
                .map { it.copy(parent = null, part = null, shapes = emptyList(), channels = emptyMap()) }
            val meshAxes = witnessMeshes.flatMap { it.offsets?.axes.orEmpty() }
            val carrierAxes = witnessWarps.flatMap { it.lattice?.axes.orEmpty() }
            for (axis in ordinaryAxes)
                BrowBlinkV6Witnesses.check(requireNotNull(input.parameter(axis)), meshAxes, carrierAxes)
            fun lower(blink: Boolean) = PuppetIr.toPuppet(BrowSourceLoweringV1.lower(input.canvas, parameters, live, target,
                nativeIds.getValue(id).jsonPrimitive.content, names.getValue(id).jsonPrimitive.content, branch,
                turn, lift, options.copy(blink = blink), wide, smile, turnName, shapeName,
                witnessMeshes, witnessWarps, checkpoint))
            val prior = lower(false)
            val next = lower(true)
            BrowSourceTransitionV1.merge(input, instance, owners, live, prior, next, parameters, checkpoint)
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = changes.flatMap { it.meshes.updated }),
            deformers = ModifierObjectChanges(added = changes.flatMap { it.deformers.added }, updated = changes.flatMap { it.deformers.updated }))
    }

    private fun validateControls(input: ModifierEvaluationInput, target: BrowShapeInputs.Target, wide: Boolean, smile: Boolean) {
        val side = if (target.fit.side == Side.LEFT) "L" else "R"
        val height = ParameterId("ParamBrow${side}Y")
        val angle = ParameterId("ParamBrow${side}Angle")
        for (id in listOf(height, angle)) require(input.parameter(id.raw)?.let {
            it.min == -1f && it.max == 1f && it.default == 0f && !it.blend
        } == true) { "Brow blink v7 requires ordinary fitted brow controls" }
        val open = EyeModules.open(target.fit.side)
        val smileId = EyeModules.smile(target.fit.side).takeIf { smile }
        require(input.parameter(open.raw)?.let {
            it.min == 0f && it.max == (if (wide) 2f else 1f) && it.default == 1f && !it.blend
        } == true) { "Brow blink v7 requires the fitted eye openness control" }
        if (smileId != null) require(input.parameter(smileId.raw)?.let {
            it.min == 0f && it.max == 1f && it.default == 0f && !it.blend
        } == true) { "Brow blink v7 requires the same-side ordinary smile control" }
    }

    private fun knob(config: JsonObject, key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        ((config["values"]?.jsonObject?.get(key)?.jsonPrimitive?.float ?: default) / 100f).also { require(it in range) }

    companion object {
        const val TYPE = "brow.blink"
        const val VERSION = 7
        const val ENTRYPOINT = "builtin.62726f772e626c696e6b.v7/blink"
        val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "brow.shape" to 6, "brow.lid-coupling" to 8)
        val requires = requiredVersions.keys
        // Gaze v3 adds independent iris-ancestor blend shapes, outside this source-only cohort.
        val eyeAddons = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4, "eye.lid-fade" to 3)
        val optionalVersions = eyeAddons + mapOf("hair/legacy" to 2, "hair/chains" to 2,
            "mouth/fitted" to 3, "mouth.outline" to 7, "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7,
            "face.skin-clip" to 1, "eye.see-through" to 1, "brow.see-through" to 1)
        val supported = requires + optionalVersions.keys
        val observed = supported + setOf(TYPE, "brow.spring", "face.depth-turn", "mouth.cheek-lid", "face/legacy", "eye/legacy",
            "brow/legacy", "mouth/legacy", "expression.toggles", "eye.gaze-lid")
        val writes = setOf("mesh", "deformer")
        fun source(context: ModifierAlgorithmContext, checkpoint: () -> Unit = {}) = BrowBlinkV7Source.source(context, checkpoint)

        fun acceptsVersion(type: String, version: Int) = BrowSourceProvidersV1.acceptsVersion(BrowSourceProvidersV1.Stage.BLINK, type, version)
        fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) =
            BrowSourceProvidersV1.validate(providers, instance, BrowSourceProvidersV1.Stage.BLINK)
    }
}

object BrowBlinkV7Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowBlinkV7Algorithm.ENTRYPOINT to BrowBlinkV7Algorithm.VERSION) to BrowBlinkV7Algorithm()))
}
