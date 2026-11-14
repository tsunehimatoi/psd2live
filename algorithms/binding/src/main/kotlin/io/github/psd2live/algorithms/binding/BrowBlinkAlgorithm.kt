package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Adds the fitted brow's blink, widening and smile response in the captured source frame. */
class BrowBlinkAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val names = context.values.getValue("carrier_names").jsonObject
        val source = BrowShapeInputs.decode(ModifierAlgorithmContext(JsonObject(context.values - "carrier_names"), context.resources), checkpoint)
        val scaffold = instance.config.getValue("scaffold")
        val base = instance.config.getValue("base")
        require(input.providers("rig.scaffold").any {
            it.instanceId == scaffold.jsonPrimitive.content && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Brow blink v2 requires a shared v2 scaffold" }
        val fitted = requireNotNull(input.providers("brow/fitted").singleOrNull {
            it.instanceId == base.jsonPrimitive.content && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == scaffold
        }) { "Brow blink v2 requires a compatible fitted brow provider" }
        require(source.targets.keys == names.keys && source.targets.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val lift = knob(fitted.config, "lift", 45f, 20f..100f)
        val dip = knob(instance.config, "dip", 100f, 0f..200f)

        val carriers = ArrayList<Deformer>()
        val updates = source.targets.mapNotNull { (id, target) ->
            checkpoint()
            val providers = input.upstream.filter { it.config["scaffold"] == scaffold && ModifierTarget("mesh", id) in it.targets }
            require(providers.none { it.capability == TYPE }) {
                "Brow blink v2 requires a brow without preceding blink"
            }
            val eye = providers.lastOrNull { it.capability == "eye/fitted" }
            if (source.eyeProvider != null && target.eye != null)
                require(eye?.instanceId == source.eyeProvider && eye.version in 1..4) { "Missing captured fitted eyes" }
            // Legacy projection removes the blink axis when its target has no upstream eye binding.
            if (eye == null && providers.none { it.capability == "eye/legacy" }) return@mapNotNull null

            val side = target.fit.side
            val suffix = if (side == Side.LEFT) "L" else "R"
            val open = EyeModules.open(side)
            val smileProvider = providers.lastOrNull { it.capability == "eye.smile" }
                ?: eye?.takeIf { it.type == "eye" && it.config["smile"]?.jsonPrimitive?.booleanOrNull == true }
            val smile = EyeModules.smile(side).takeIf { target.eye != null && smileProvider != null }
            val wide = eye != null && (providers.any { it.capability == "eye.wide" } ||
                (eye.type == "eye" && eye.config["wide"]?.jsonPrimitive?.booleanOrNull == true))
            val openParam = requireNotNull(input.parameter(open.raw))
            require(!openParam.blend && openParam.min == 0f && openParam.default == 1f && openParam.max == (if (wide) 2f else 1f))
            val smileParam = smile?.let { requireNotNull(input.parameter(it.raw)) }
            require(smileParam == null || !smileParam.blend && smileParam.min == 0f && smileParam.max == 1f && smileParam.default == 0f)
            val mesh = requireNotNull(input.mesh(id))
            val geometry = requireNotNull(mesh.geometry)
            val raw = target.raw
            val frame = target.frame
            val expected = Floats.wrap(FloatArray(raw.rigPositions.size) { i ->
                if (i % 1024 == 0) checkpoint()
                val value = raw.rigPositions[i]
                if (target.parent == null) value
                else if (i % 2 == 0) (value - frame.left) / frame.width.coerceAtLeast(1e-4f)
                else (value - frame.top) / frame.height.coerceAtLeast(1e-4f)
            })
            require(mesh.parent == target.parent && geometry == MeshGeometry(expected, Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles))) {
                "Brow blink v2 requires the fitted source mesh and parent frame: $id"
            }
            val shape = providers.lastOrNull { it.capability == "brow.shape" }
            val strength = shape?.let { knob(it.config, "strength", 100f, 0f..200f) } ?: 1f
            val travel = target.eye?.let { it.opening * it.width * lift } ?: (target.fit.length * .25f * lift / .45f)
            val rig = BrowRig(target.fit, travel, BrowOptions(shape = shape != null, shapeStrength = strength, blink = true, blinkDip = dip), checkpoint)
            val height = ParameterId("ParamBrow${suffix}Y")
            val angle = ParameterId("ParamBrow${suffix}Angle")
            val grid = rig.grid(raw.rigPositions, frame, height, angle)
            require(mesh.offsets == KeyGrid(grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
                grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) }) &&
                mesh.channels.isEmpty() && mesh.shapes.all { it.parameter in setOf("ParamBrow${suffix}X", "ParamBrow${suffix}Form", "ParamBrow${suffix}Spring") }) {
                "Brow blink v2 requires the fitted height and angle grid without other local motion or drawing tracks: $id"
            }
            val lid = smile?.let { parameter ->
                requireNotNull(requireNotNull(smileProvider).targets.asSequence().mapNotNull { input.mesh(it.id) }
                    .firstOrNull { other -> other.offsets?.axes?.any { it.parameter == parameter.raw } == true }) {
                    "Brow blink v2 requires an upstream lid grid keyed by ${parameter.raw}"
                }
            }
            val parent = mesh.parent?.let(input::deformer)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, meshes = listOfNotNull(mesh, lid?.copy(shapes = emptyList())),
                deformers = listOfNotNull(parent)))
            val drawable = model.drawables.first()
            val blink = rig.shapes(raw.rigPositions, frame, ParameterId("ParamBrow${suffix}X"), ParameterId("ParamBrow${suffix}Form"),
                open, wide, smile).filter { it.parameterId == open || it.parameterId == smile }.map { binding ->
                binding.copy(forms = binding.forms.map { form -> form?.let {
                    org.umamo.runtime.model.MeshForm(it.positionDeltas, drawable.drawOrder, drawable.opacity, drawable.multiplyColor, drawable.screenColor)
                } })
            }
            val lowering = BlendShapeLowering({ _, _ -> names.getValue(id).jsonPrimitive.content }, checkpoint)
            val candidate = model.copy(drawables = listOf(drawable.copy(blendShapes = drawable.blendShapes + blink)) + model.drawables.drop(1),
                deformers = model.deformers.map { deformer ->
                    when (deformer) {
                        is org.umamo.runtime.model.Deformer.Warp -> deformer.copy(blendShapes = emptyList())
                        is org.umamo.runtime.model.Deformer.Rotation -> deformer.copy(blendShapes = emptyList())
                    }
                })
            val lowered = lowering.lawful(candidate)
            val added = lowered.deformers.drop(candidate.deformers.size)
            val remap = ModelIdRemap(deformers = added.associate {
                it.id to org.umamo.runtime.model.DeformerId(ScaffoldBinding.deformerId(instance, id, it.id.raw, shared = true))
            })
            carriers += added.map { PuppetIr.deformerToIr(remap.deformer(it)) }
            val next = PuppetIr.meshToIr(lowered.drawables.first().let { it.copy(parentDeformerId = it.parentDeformerId?.let(remap::deformer)) })
            ModifierObjectUpdate(mesh, next).takeIf { mesh != next }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates), deformers = ModifierObjectChanges(added = carriers))
    }

    companion object {
        const val TYPE = "brow.blink"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.62726f772e626c696e6b.v2/blink"
        val optionalUpstream = setOf("eye/fitted", "eye/legacy", "eye.wide", "eye.smile", "brow.shape", TYPE)
        val WRITES = setOf("mesh", "deformer")

        private fun knob(config: JsonObject, name: String, default: Float, range: ClosedFloatingPointRange<Float>) =
            (config["values"]?.jsonObject?.get(name)?.jsonPrimitive?.float ?: default).also { require(it in range) } / 100f
    }
}
