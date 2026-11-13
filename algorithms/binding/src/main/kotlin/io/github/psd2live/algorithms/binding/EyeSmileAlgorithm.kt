package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Adds smile keys to fitted lids in their captured source frame. */
class EyeSmileAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val captured = EyeSmileInputs.decode(input.context, checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Eye smile v2 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.version == 2) { "Eye smile v2 requires fitted eyes v2" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) { "Eye smile v2 cannot replace an earlier smile on the same mesh" }
        require(preceding.none { it.capability in coupled }) { "Eye smile v2 cannot lower preceding coupled lid or brow bindings" }
        val values = fitted.config["values"]?.jsonObject.orEmpty()
        val lower = knob(values, "lowerShare", 5f, 0f..50f)
        val roundness = knob(values, "roundness", 100f, 40f..160f)
        val lashSquash = knob(values, "lashSquash", 60f, 20f..100f)
        val smileValues = instance.config["values"]?.jsonObject.orEmpty()
        val arch = .12f * knob(smileValues, "arch", 100f, 0f..200f)
        val push = knob(smileValues, "push", 50f, 0f..100f)
        val align = { x: Float, y: Float -> source.alignment?.point(x, y) ?: (x to y) }
        val fits = EyeFitting.fit(captured.art, source.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(source.moves[side.name].orEmpty())
        }
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Eye smile v2 needs readable fitted eye artwork"
        }
        val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fits, source.corrections, align)
        val rigs = HashMap<EyeOptions, FittedEyeMotion>()
        fun motion(options: EyeOptions) = rigs.getOrPut(options) {
            FittedEyeMotion(fits.mapValues { (side, fit) ->
                EyeRig(fit.copy(artwork = artwork[side]), lower, roundness, lashSquash,
                    options.smileArc, options.smilePush, options.wideLift)
            }, options)
        }

        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val wide = preceding.lastOrNull { it.capability == "eye.wide" && target in it.targets }
            val options = EyeOptions(wide = wide != null, wideLift = knob(wide?.config?.get("values")?.jsonObject.orEmpty(), "lift", 10f, 0f..80f))
            val raw = captured.topology.getValue(material.layer)
            val frame = material.frame.let { Bounds(it.left, it.top, it.right, it.bottom) }
            val after = grid(layer, raw.rigPositions, frame, motion(options.copy(smile = true, smileArc = arch, smilePush = push)))
                ?: return@mapNotNull null
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing fitted lid: ${target.id}" }
            require(mesh.parent == material.parent) { "Eye smile v2 cannot migrate a fitted lid carrier: ${target.id}" }
            val geometry = requireNotNull(mesh.geometry)
            require(geometry.uvs == Floats.wrap(raw.atlasUvs) && geometry.indices == Ints.wrap(raw.triangles)) {
                "Eye smile v2 requires the captured lid topology: ${target.id}"
            }
            val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val value = raw.rigPositions[i]
                if (material.parent == null) value
                else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val before = grid(layer, raw.rigPositions, frame, motion(options))?.offsets()
            val unchanged = if (before != null) mesh.offsets == before else mesh.offsets?.let {
                it.axes.isEmpty() && it.cells.singleOrNull()?.form?.deltas?.shared()?.all { value -> value == 0f } == true
            } ?: true
            require(geometry.positions == original && unchanged && mesh.shapes.isEmpty()) {
                "Eye smile v2 cannot migrate edited or lowered lid geometry: ${target.id}"
            }
            ModifierObjectUpdate(mesh, mesh.copy(offsets = after.offsets()))
        }
        val declared = parameters(source.parameterNames)
        val live = input.parameters
        val additions = declared.filter { parameter ->
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible eye smile parameter: ${parameter.id}"
            }
            existing == null
        }
        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId(FOLDER)), source.folderName, true,
            (eyeParameterIds + declared.map { it.id }).map { PuppetParameterNode.Param(ParameterId(it)) })
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun grid(layer: ModifierSourceLayer, positions: FloatArray, frame: Bounds,
                     motion: FittedEyeMotion): KeyformGrid<MeshDeltaForm>? {
        val side = Side.valueOf(layer.side)
        val eye = motion.rigs[side]
        val lid = EyeModules.meshOf(layer.tag, layer.role)
        return when {
            lid != null && side == Side.NONE -> EyeModules.pairedLidGrid(lid, layer.id, positions, frame, motion)
            lid != null && eye != null -> EyeModules.lidGrid(lid, side, positions, frame, eye, motion.options, layer.id)
            layer.tag == "EYE_CLOSE" && eye != null -> EyeModules.closedLineGrid(side, positions, frame, eye, motion.options)
            else -> null
        }
    }

    companion object {
        const val TYPE = "eye.smile"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e736d696c65.v2/smile"
        private const val FOLDER = "parameter-folder:ParamGroupEyes"
        val WRITES = setOf("mesh", "parameter", "hierarchy")
        private val coupled = setOf("eye.gaze-lid", "eye.emotion", "eye.aftershock", "brow.blink", "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = coupled + setOf(TYPE, "eye.wide")
        private val eyeParameterIds = listOf("ParamEyeLOpen", "ParamEyeROpen", "ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm")

        fun parameters(names: Map<String, String>) = listOf(Side.LEFT, Side.RIGHT).map {
            val id = EyeModules.smile(it).raw
            Parameter(id, names.getValue(id), 0f, 1f, 0f)
        }

        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f

        private fun KeyformGrid<MeshDeltaForm>.offsets() = KeyGrid(
            axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
            cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) },
        )
    }
}
