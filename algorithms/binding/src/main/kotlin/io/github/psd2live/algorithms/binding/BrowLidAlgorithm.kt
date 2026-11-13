package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.DeformerId

/** Couples the fitted lids and iris to the live fitted brow parameters. */
class BrowLidAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold")
        val targets = instance.targets.toSet()
        fun provider(type: String, id: JsonElement) = requireNotNull(input.providers(type).singleOrNull {
            it.instanceId == id.jsonPrimitive.content && it.targets.containsAll(targets) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffold)
        }) { "Missing brow and lid provider: $type" }
        require(provider("rig.scaffold", scaffold).version == 2)
        val brow = provider("brow/fitted", instance.config.getValue("base"))
        require(brow.version in 1..2) { "Brow and lid v2 requires fitted brows v1 or v2" }
        val eye = provider("eye/fitted", instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted"))
        require(eye.version == 2) { "Brow and lid v2 requires fitted eyes v2" }
        val previous = input.upstream.filter { it.config["scaffold"] == scaffold && it.targets.any(targets::contains) }
        require(previous.none { it.capability in unsupported }) {
            "Brow and lid v2 requires eye meshes without previously lowered lid coupling or depth turn"
        }

        val context = input.context
        val names = context.values.getValue("carrier_names").jsonObject
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val browIds = context.values.getValue("brow_meshes").jsonArray.map { it.jsonPrimitive.content }
        require(browIds.distinct().size == browIds.size && browIds.all { ModifierTarget("mesh", it) in brow.targets })
        val browMeshes = browIds.mapNotNull(input::mesh).map { it.copy(shapes = emptyList()) }
        val parameters = input.parameters
        val defaults = parameters.associate { ParameterId(it.id) to it.default }
        val atDefault = { id: ParameterId -> defaults[id] ?: 0f }
        val cover = context.values.getValue("brow_cover").jsonPrimitive.float
        require(cover in 0f..1f)
        val source = EyeWideInputs.decode(ModifierAlgorithmContext(
            JsonObject(context.values - setOf("carrier_names", "native_ids", "brow_cover", "brow_meshes")), context.resources), checkpoint)
        require(source.measurements.targets.keys.all { ModifierTarget("mesh", it) in targets } && names.keys == nativeIds.keys)
        val measured = source.measurements
        val align = { x: Float, y: Float -> measured.alignment?.point(x, y) ?: (x to y) }
        val fits = EyeFitting.fit(source.art, measured.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(measured.moves[side.name].orEmpty())
        }
        val artwork = EyeArtworkFitting.fit(source.art, measured.artworkStamps, fits, measured.corrections, align)
        val lower = knob(eye.config, "lowerShare", 5f, 0f..50f)
        val roundness = knob(eye.config, "roundness", 100f, 40f..160f)
        val squash = knob(eye.config, "lashSquash", 60f, 20f..100f)
        val options = EyeOptions(browCoupling = true, browStrength = knob(instance.config, "strength", 100f, 0f..200f) * (1f + .6f * cover))
        val motion = FittedEyeMotion(fits.mapValues { (side, fit) -> EyeRig(fit.copy(artwork = artwork[side]), lower, roundness, squash) }, options)
        val carriers = LinkedHashMap<String, Deformer>()
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val material = measured.targets[target.id] ?: return@mapNotNull null
            val layer = source.art.layerForTarget(target.id)
            val side = Side.valueOf(layer.side)
            val raw = source.topology.getValue(material.layer)
            val positions = raw.rigPositions
            val frame = material.frame.run { Bounds(left, top, right, bottom) }
            val lid = EyeModules.meshOf(layer.tag, layer.role)
            val shapes = when {
                lid != null -> motion.rigs.filterKeys { side == Side.NONE || side == it }.flatMap { (owner, rig) ->
                    val local = EyeModules.lidShapes(lid, owner, positions, frame, rig, options, layer.id)
                    if (side != Side.NONE) local else {
                        val vertexSides = List(positions.size / 2) { i -> motion.sideAt(layer.id, positions[i * 2], positions[i * 2 + 1]) }
                        local.map { shape -> shape.copy(forms = shape.forms.map { form ->
                            form?.let { MeshForm(FloatArray(positions.size) { i -> if (vertexSides[i / 2] == owner) it.positionDeltas[i] else 0f }) }
                        }) }
                    }
                }
                layer.tag == "IRIDES" && layer.role != "HIGHLIGHT" && side in motion.rigs -> {
                    val bounds = layer.bounds.run { Bounds(left, top, right, bottom) }
                    EyeModules.pupilShapes(side, positions, frame, motion.rigs.getValue(side).fit.iris ?: bounds, options)
                }
                else -> emptyList()
            }
            if (shapes.isEmpty()) return@mapNotNull null
            val mesh = requireNotNull(input.mesh(target.id))
            val points = Floats.wrap(FloatArray(positions.size) { i ->
                val point = positions[i]
                if (material.parent == null) point
                else if (i % 2 == 0) (point - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (point - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            require(mesh.parent == material.parent && mesh.geometry == MeshGeometry(points, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)) &&
                mesh.shapes.none { binding -> shapes.any { it.parameterId.raw == binding.parameter } }) {
                "Brow and lid v2 requires the fitted eye source mesh and parent frame: ${target.id}"
            }
            val parameterIds = shapes.mapTo(hashSetOf()) { it.parameterId.raw }

            require(parameterIds.all { id ->
                val parameter = input.parameter(id)
                // Without brow.shape the legacy rig keeps its dormant angle shape, even without a live angle axis.
                if (parameter == null) id.endsWith("Angle") && previous.none { it.capability == "eye.aftershock" }
                else !parameter.blend && parameter.default == 0f && parameter.min == -1f && parameter.max == 1f &&
                    browMeshes.any { other -> other.offsets?.axes.orEmpty().any { it.parameter == id } }
            }) { "Brow and lid v2 requires ordinary fitted brow axes" }
            val nativeId = nativeIds.getValue(target.id).jsonPrimitive.content
            val parent = mesh.parent?.let(input::deformer)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(mesh.copy(id = nativeId)) + browMeshes,
                deformers = listOfNotNull(parent)))
            val drawable = model.drawables.first()
            val lowering = BlendShapeLowering({ _, _ -> names.getValue(target.id).jsonPrimitive.content }, checkpoint)
            val restOpacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, atDefault)
            val restOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, atDefault)
            val restMultiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, atDefault)
            val restScreen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, atDefault)
            val lowered = lowering.lawful(model.copy(drawables = listOf(drawable.copy(
                blendShapes = drawable.blendShapes + shapes.map { shape -> shape.copy(forms = shape.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, restOrder, restOpacity, restMultiply, restScreen) }
                }) })) + model.drawables.drop(1)))
            val added = lowered.deformers.drop(model.deformers.size)
            val remap = ModelIdRemap(deformers = added.associate {
                it.id to DeformerId(ScaffoldBinding.deformerId(instance, target.id, it.id.raw, shared = true))
            })
            for (carrier in added) {
                val converted = PuppetIr.deformerToIr(remap.deformer(carrier))
                val existing = carriers.putIfAbsent(converted.id, converted)
                require(existing == null || existing == converted) { "Incompatible shared eye carrier: ${converted.id}" }
            }
            val after = lowered.drawables.first()
            val result = PuppetIr.meshToIr(after).copy(id = mesh.id, parent = after.parentDeformerId?.let(remap::deformer)?.raw)
            val next = mesh.copy(parent = result.parent, geometry = result.geometry,
                offsets = result.offsets, channels = result.channels, shapes = result.shapes)
            ModifierObjectUpdate(mesh, next).takeIf { mesh != next }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(added = carriers.values.toList()))
    }

    companion object {
        const val TYPE = "brow.lid-coupling"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.62726f772e6c69642d636f75706c696e67.v2/coupling"
        val WRITES = setOf("mesh", "deformer")
        private val unsupported = setOf(TYPE, "face/fitted", "face.depth-turn", "eye.gaze-lid", "mouth.cheek-lid")
        val optionalUpstream = unsupported + setOf("brow.shape", "brow.spring", "brow.blink", "eye.smile",
            "eye.wide", "eye.squash", "eye.lid-fade", "eye.highlight", "eye.emotion", "eye.aftershock")

        private fun knob(config: JsonObject, name: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config["values"]?.jsonObject?.get(name)?.jsonPrimitive?.float ?: default).also { require(it in range) } / 100f
    }
}
