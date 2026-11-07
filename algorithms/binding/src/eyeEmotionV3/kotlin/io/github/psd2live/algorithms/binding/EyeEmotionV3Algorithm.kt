package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Computes the emotion and squint lid shapes from the captured fitted-eye artwork. */
class EyeEmotionV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val head = input.context.values["head_turn"]?.jsonObject
        val captured = EyeSmileInputs.decode(sourceContext(input.context), checkpoint)
        val source = captured.measurements
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        require(source.targets.keys.all { ModifierTarget("mesh", it) in targets })
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(targets)
        }) { "Eye emotion v3 requires a version 2 source scaffold" }
        val fitted = requireNotNull(input.providers("eye/fitted").singleOrNull {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.targets.containsAll(targets)
        }) { "Missing fitted eye provider" }
        require(fitted.version in setOf(2, 4, 5)) { "Eye emotion v3 requires fitted eyes v2, v4 or v5" }
        val preceding = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(preceding.none { it.capability == TYPE }) {
            "Eye emotion v3 cannot replace an earlier emotion on the same mesh"
        }
        require(preceding.none { it.capability in coupled }) {
            "Eye emotion v3 cannot compose with preceding coupled lid bindings"
        }

        val turn = head?.let { values ->
            require(preceding.any { it.capability in headProviders && it.instanceId == values.getValue("provider").jsonPrimitive.content }) {
                "Eye emotion v3 requires the captured head turn provider"
            }
            val points = values.getValue("shell").jsonArray.map { it.jsonPrimitive.float }
            require(points.size == 7 && points.all(Float::isFinite) && points[2] > 0f && points[3] > 0f)
            HeadTurn(points[0], points[1], points[2], points[3], points[4], points[5], points[6], checkpoint)
        }
        fun carrierName(isHead: Boolean, meshName: String): String =
            (if (isHead) requireNotNull(head).getValue("turn_name") else input.context.values.getValue("shape_name"))
                .jsonPrimitive.content.replace("{meshName}", meshName)
        val fits = fits(captured, checkpoint)
        require(fits.isNotEmpty() && captured.art.layers.any { it.tag == "EYELASH" }) {
            "Eye emotion v3 needs readable fitted eye artwork"
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
        val addedWarps = ArrayList<Deformer>()
        val changedWarps = ArrayList<ModifierObjectUpdate<Deformer>>()
        val live = input.parameters
        val gazeWarps by lazy {
            if (preceding.none { it.capability == "eye.gaze-lid" }) emptyList()
            else instance.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
                .filterIsInstance<Deformer.Warp>().filter { warp ->
                    warp.lattice?.axes.orEmpty().any { it.parameter in sharedGazeAxes }
                }
        }
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
            val gaze = preceding.lastOrNull { it.capability == "eye.gaze-lid" && target in it.targets }
            val gazeValues = gaze?.config?.get("values")?.jsonObject.orEmpty()
            val lidOptions = options.copy(
                gaze = gaze != null, gazeFollow = knob(gazeValues, "follow", 100f, 0f..200f),
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
            val original = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                val value = raw.rigPositions[i]
                if (material.parent == null) value
                else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            val grid = if (side == Side.NONE) EyeModules.pairedLidGrid(lid, layer.id, raw.rigPositions, frame, motion)
            else EyeModules.lidGrid(lid, side, raw.rigPositions, frame, motion.rigs.getValue(side), motion.options, layer.id)
            val expected = grid?.let {
                KeyGrid(it.axes.map { axis -> KeyAxis(axis.parameterId.raw, Floats.wrap(axis.keys)) },
                    it.cells.map { cell -> KeyCell(Ints.wrap(cell.coordinate), MeshOffsets(Floats.wrap(cell.form.positionDeltas))) })
            }
            val branch = input.parentsOfMesh(target.id)
            val baseBranch = branch.dropWhile { it.id != material.parent }
            require(material.parent == null || baseBranch.isNotEmpty()) { "Missing fitted lid source frame: ${target.id}" }
            val upstreamWarps = branch.take(branch.size - baseBranch.size).asReversed()
            require(upstreamWarps.all { it is Deformer.Warp }) { "Eye emotion v3 requires preceding lid warps" }
            val headFolded = mesh.offsets?.axes.orEmpty().any { it.parameter in headAxes } ||
                mesh.shapes.any { it.parameter in headAxes } ||
                upstreamWarps.filterIsInstance<Deformer.Warp>().any { warp -> warp.lattice?.axes.orEmpty().any { it.parameter in headAxes } }
            require(!headFolded || turn != null) { "Eye emotion v3 needs captured head turn measurements: ${target.id}" }
            val nativeId = head?.getValue("native_ids")?.jsonObject?.getValue(target.id)?.jsonPrimitive?.content ?: target.id
            val canonical = mesh.copy(id = nativeId, parent = material.parent,
                geometry = MeshGeometry(original, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                offsets = expected, shapes = emptyList())
            val deformers = (baseBranch + gazeWarps).distinctBy { it.id }
            val model = PuppetIr.toPuppet(RigIR(input.canvas, live, deformers = deformers, meshes = listOf(canonical)))
            val drawable = model.drawables.single()
            val mapping = requireNotNull(drawableSpaceMapping(model, emptyMap(), drawable.id))
            val world = mapping.localToWorld(original.shared())
            require((world.indices step 2).all { i ->
                val x = raw.rigPositions[i]
                val y = raw.rigPositions[i + 1]
                val canvas = source.alignment?.canvas(x, y) ?: (x to y)
                kotlin.math.abs(world[i] - canvas.first) <= .005f && kotlin.math.abs(-world[i + 1] - canvas.second) <= .005f
            }) { "Eye emotion v3 cannot migrate an edited neutral lid frame: ${target.id}" }
            val headShapes = if (!headFolded) emptyList() else {
                val shell = requireNotNull(DepthTurnMotion.shell(layer.tag, layer.role))
                val rest = meshGridDefaultDeltas(drawable) { id -> if (id.raw in openIds) 1f else 0f }
                val turns = requireNotNull(turn).shapes(raw.rigPositions, frame, shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                neutralized(turns, drawable, live).map { binding ->
                    binding.copy(forms = binding.forms.map { form -> form?.let {
                        if (rest == null) it else MeshForm(FloatArray(rest.size) { i -> it.positionDeltas[i] + rest[i] },
                            it.drawOrder, it.opacity, it.multiplyColor, it.screenColor)
                    } })
                }
            }
            fun lower(shape: List<BlendShapeBinding<MeshForm>>, parameters: List<Parameter>): org.umamo.runtime.model.PuppetModel {
                val bound = drawable.copy(blendShapes = neutralized(shape, drawable, parameters) + headShapes)
                val scene = model.copy(parameters = PuppetIr.toPuppet(RigIR(input.canvas, parameters)).parameters, drawables = listOf(bound))
                return if (gaze == null && !headFolded && upstreamWarps.isEmpty()) scene
                    else BlendShapeLowering(::carrierName, checkpoint).lawful(scene)
            }
            val previous = lower(shapes(lidOptions.copy(emotion = false)), live)
            val generated = previous.deformers.drop(model.deformers.size)
            require(generated.size == upstreamWarps.size) { "Eye emotion v3 cannot reproduce preceding lid warp slots: ${target.id}" }
            val priorIds = generated.zip(upstreamWarps).associate { (native, live) -> native.id to DeformerId(live.id) }
            val remap = ModelIdRemap(deformers = priorIds)
            require(generated.zip(upstreamWarps).all { (native, live) ->
                (PuppetIr.deformerToIr(remap.deformer(native)) as Deformer.Warp).copy(part = live.part) == live
            } && PuppetIr.meshToIr(remap.drawable(previous.drawables.single())).copy(id = mesh.id) == mesh) {
                "Eye emotion v3 cannot reproduce preceding fitted lid: ${target.id}"
            }
            val next = lower(bindings, live + declared.filter { parameter -> live.none { it.id == parameter.id } })
            val nextWarps = next.deformers.drop(model.deformers.size)
            require(priorIds.keys.all { id -> nextWarps.any { it.id == id } } &&
                next.deformers.take(model.deformers.size).map(PuppetIr::deformerToIr) == model.deformers.map(PuppetIr::deformerToIr)) {
                "Eye emotion v3 cannot retire a preceding lid warp or change its base: ${target.id}"
            }
            val nextRemap = ModelIdRemap(deformers = nextWarps.associate { warp ->
                warp.id to (priorIds[warp.id] ?: DeformerId(ScaffoldBinding.deformerId(instance, target.id, warp.id.raw, shared = true)))
            })
            for (warp in nextWarps) {
                val output = PuppetIr.deformerToIr(nextRemap.deformer(warp)) as Deformer.Warp
                val before = upstreamWarps.singleOrNull { it.id == output.id }
                if (before == null) {
                    require(input.deformer(output.id) == null && addedWarps.none { it.id == output.id }) {
                        "Eye emotion v3 lid warp already exists: ${output.id}"
                    }
                    addedWarps += output
                } else {
                    val after = output.copy(part = before.part)
                    if (after != before) {
                        require(input.meshes.none { it.id != mesh.id && it.parent == before.id } &&
                            input.deformers.none { it.parent == before.id && it !in upstreamWarps }) {
                            "Eye emotion v3 cannot refit a shared lid warp"
                        }
                        changedWarps += ModifierObjectUpdate(before, after)
                    }
                }
            }
            val after = PuppetIr.meshToIr(nextRemap.drawable(next.drawables.single())).copy(id = mesh.id)
            ModifierObjectUpdate(mesh, after)
        }

        val additions = declared.filter { parameter ->
            val existing = input.parameter(parameter.id)
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible eye emotion parameter: ${parameter.id}"
            }
            existing == null
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
            meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = addedWarps, updated = changedWarps),
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
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e656d6f74696f6e.v3/emotion"
        val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy")
        private val coupled = setOf("brow.lid-coupling", "mouth.cheek-lid")
        private val headProviders = setOf("face/fitted", "face.depth-turn")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private val sharedGazeAxes = setOf("ParamEyeBallX", "ParamEyeBallY")
        val optionalUpstream = coupled + headProviders + setOf(TYPE, "eye.wide", "eye.smile", "eye.gaze-lid", "eye.aftershock")
        private val openIds = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val eyeParameterIds = openIds.toList() + listOf("ParamEyeBallX", "ParamEyeBallY", "ParamEyeBallForm",
            "ParamEyeLSmile", "ParamEyeRSmile", "ParamEyeBallLX", "ParamEyeBallLY", "ParamEyeBallRX", "ParamEyeBallRY")

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            val captured = EyeSmileInputs.decode(sourceContext(context)) {}
            if (captured.art.layers.none { it.tag == "EYELASH" }) return emptyList()
            val fits = fits(captured) {}
            return if (fits.isEmpty()) emptyList() else parameters(captured.measurements.parameterNames, fits.keys)
        }

        private fun sourceContext(context: ModifierAlgorithmContext) =
            ModifierAlgorithmContext(JsonObject(context.values - setOf("head_turn", "shape_name")), context.resources)

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
