package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Computes the emotion and squint lid shapes from the captured fitted-eye artwork. */
class EyeEmotionAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val captured = EyeSmileInputs.decode(input.context, checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Eye emotion v2 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.version in setOf(2, 4)) { "Eye emotion v2 requires fitted eyes v2 or v4" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) {
            "Eye emotion v2 cannot replace an earlier emotion on the same mesh"
        }
        require(preceding.none { it.capability in coupled }) {
            "Eye emotion v2 cannot compose with preceding coupled lid bindings"
        }

        val fits = fits(captured, checkpoint)
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Eye emotion v2 needs readable fitted eye artwork"
        }
        val values = fitted.config["values"]?.jsonObject.orEmpty()
        val strength = knob(instance.config["values"]?.jsonObject.orEmpty(), "strength", 100f, 0f..200f)
        val options = EyeOptions(emotion = true, emotionStrength = strength)
        val artwork = EyeArtworkFitting.fit(captured.art, source.artworkStamps, fits, source.corrections) { x, y ->
            source.alignment?.point(x, y) ?: (x to y)
        }
        val motions = HashMap<EyeOptions, FittedEyeMotion>()
        fun motion(options: EyeOptions) = motions.getOrPut(options) {
            FittedEyeMotion(fits.mapValues { (side, fit) ->
                EyeRig(fit.copy(artwork = artwork[side]), knob(values, "lowerShare", 5f, 0f..50f),
                    knob(values, "roundness", 100f, 40f..160f), knob(values, "lashSquash", 60f, 20f..100f),
                    options.smileArc, options.smilePush, options.wideLift)
            }, options)
        }

        val declared = parameters(source.parameterNames, fits.keys)
        val live = input.parameters
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = source.targets[target.id] ?: return@mapNotNull null
            val layer = captured.art.layerForTarget(target.id)
            val lid = EyeModules.meshOf(layer.tag, layer.role) ?: return@mapNotNull null
            val raw = captured.topology.getValue(material.layer)
            val frame = material.frame.let { Bounds(it.left, it.top, it.right, it.bottom) }
            val side = Side.valueOf(layer.side)
            val wide = preceding.lastOrNull { it.capability == "eye.wide" && target in it.targets }
            val smile = preceding.lastOrNull { it.capability == "eye.smile" && target in it.targets }
            val wideValues = wide?.config?.get("values")?.jsonObject.orEmpty()
            val smileValues = smile?.config?.get("values")?.jsonObject.orEmpty()
            val aftershock = preceding.lastOrNull { it.capability == "eye.aftershock" && target in it.targets }
            val aftershockValues = aftershock?.config?.get("values")?.jsonObject.orEmpty()
            val lidOptions = options.copy(
                aftershock = aftershock != null, softness = knob(aftershockValues, "softness", 100f, 0f..200f),
                wide = wide != null, wideLift = knob(wideValues, "lift", 10f, 0f..80f),
                smile = smile != null, smileArc = .12f * knob(smileValues, "arch", 100f, 0f..200f),
                smilePush = knob(smileValues, "push", 50f, 0f..100f),
            )
            val motion = motion(lidOptions)
            fun shapes(options: EyeOptions): List<BlendShapeBinding<MeshForm>> = if (side == Side.NONE) {
                val owner = List(raw.rigPositions.size / 2) { i ->
                    motion.sideAt(layer.id, raw.rigPositions[i * 2], raw.rigPositions[i * 2 + 1])
                }
                motion.rigs.flatMap { (eyeSide, eye) ->
                    EyeModules.lidShapes(lid, eyeSide, raw.rigPositions, frame, eye, options, layer.id).map { shape ->
                        shape.copy(forms = shape.forms.map { form -> form?.let {
                            MeshForm(FloatArray(it.positionDeltas.size) { i -> if (owner[i / 2] == eyeSide) it.positionDeltas[i] else 0f })
                        } })
                    }
                }
            } else motion.rigs[side]?.let { EyeModules.lidShapes(lid, side, raw.rigPositions, frame, it, options, layer.id) }.orEmpty()
            val bindings = shapes(lidOptions)
            if (bindings.isEmpty()) return@mapNotNull null
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing fitted lid: ${target.id}" }
            val geometry = requireNotNull(mesh.geometry)
            require(mesh.parent == material.parent) { "Eye emotion v2 cannot migrate a fitted lid carrier: ${target.id}" }
            val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val value = raw.rigPositions[i]
                if (material.parent == null) value
                else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            require(geometry.positions == original && geometry.uvs == Floats.wrap(raw.atlasUvs) &&
                geometry.indices == Ints.wrap(raw.triangles)) { "Eye emotion v2 requires the captured lid topology and positions: ${target.id}" }
            val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, raw.rigPositions, frame, motion)
            else EyeModules.lidGrid(lid, side, raw.rigPositions, frame, motion.rigs.getValue(side), motion.options, layer.id)
            val expected = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            require(mesh.offsets == expected) {
                "Eye emotion v2 cannot migrate edited or lowered lid geometry: ${target.id}"
            }
            val branch = input.parentsOfMesh(target.id)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live, deformers = branch, meshes = listOf(mesh)))
            val drawable = model.drawables.single()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            // The evaluator uses y-up world coordinates while the captured artwork uses y-down canvas pixels.
            val world = mapping.localToWorld(geometry.positions.shared())
            require((world.indices step 2).all { i ->
                val x = raw.rigPositions[i]
                val y = raw.rigPositions[i + 1]
                val canvas = source.alignment?.canvas(x, y) ?: (x to y)
                kotlin.math.abs(world[i] - canvas.first) <= .005f && kotlin.math.abs(-world[i + 1] - canvas.second) <= .005f
            }) { "Eye emotion v2 cannot migrate an edited neutral lid frame: ${target.id}" }
            val previous = neutralized(shapes(lidOptions.copy(emotion = false)), drawable, live)
            require(PuppetIr.meshToIr(drawable.copy(blendShapes = previous)).shapes == mesh.shapes) {
                "Eye emotion v2 cannot migrate edited or lowered lid shapes: ${target.id}"
            }
            mesh to drawable.copy(blendShapes = neutralized(bindings, drawable, live))
        }

        val additions = declared.filter { parameter ->
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible eye emotion parameter: ${parameter.id}"
            }
            existing == null
        }
        val meshes = updates.map { (mesh, drawable) ->
            ModifierObjectUpdate(mesh, mesh.copy(shapes = PuppetIr.meshToIr(drawable).shapes))
        }
        val tree = input.parameterTree
        val treeModel = PuppetIr.toPuppet(RigIR(input.canvas, live, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupEyes")),
            source.folderName, true, (eyeParameterIds + declared.map { it.id }).map {
                PuppetParameterNode.Param(ParameterId(it))
            })
        val updatedTree = BindingMerge.appendTree(treeModel, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(treeModel.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = meshes),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun neutralized(shapes: List<BlendShapeBinding<MeshForm>>, drawable: Drawable,
                            parameters: List<Parameter>): List<BlendShapeBinding<MeshForm>> {
        val rest = { id: ParameterId -> if (id.raw in openIds) 1f else 0f }
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it] ?: 0f })
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
        return shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
        }) }
    }

    companion object {
        const val TYPE = "eye.emotion"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6579652e656d6f74696f6e.v2/emotion"
        val WRITES = setOf("mesh", "parameter", "hierarchy")
        private val coupled = setOf("eye.gaze-lid", "brow.lid-coupling", "mouth.cheek-lid")
        val optionalUpstream = coupled + setOf(TYPE, "eye.wide", "eye.smile", "eye.aftershock")
        private val openIds = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val eyeParameterIds = openIds.toList() + listOf("ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm",
            "ParamEyeLSmile", "ParamEyeRSmile")

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val captured = EyeSmileInputs.decode(context) {}
            if (captured.art.layers.none { it.tag == "EYELASH" }) return emptyList()
            val fits = fits(captured) {}
            return if (fits.isEmpty()) emptyList() else parameters(captured.measurements.parameterNames, fits.keys)
        }

        private fun parameters(names: Map<String, String>, fitted: Set<Side>) =
            listOf("Form" to -1f, "Squint" to 0f).flatMap { (suffix, min) ->
                listOf(Side.LEFT to "L", Side.RIGHT to "R").map { (side, letter) ->
                    val id = "ParamEye$letter$suffix"
                    // These axes drive additive shapes exclusively and rest at zero.
                    // Cubism keeps the undriven side ordinary when only one eye is fitted.
                    val blend = side in fitted
                    val keys = if (!blend) null else if (min < 0f) floatArrayOf(min, 0f, 1f) else floatArrayOf(0f, 1f)
                    Parameter(id, names.getValue(id), min, 1f, 0f, blend = blend, keys = keys?.let(Floats::wrap))
                }
            }

        private fun fits(captured: EyeSmileInputs.Companion.Decoded, checkpoint: () -> Unit): Map<Side, EyeFit> {
            val source = captured.measurements
            return EyeFitting.fit(captured.art, source.faceCenterX) { x, y ->
                source.alignment?.point(x, y) ?: (x to y)
            }.fits.mapValues { (side, fit) ->
                checkpoint()
                fit.moved(source.moves[side.name].orEmpty())
            }
        }

        private fun knob(values: Map<String, JsonElement>, id: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid eye value: $id" } } / 100f
    }
}
