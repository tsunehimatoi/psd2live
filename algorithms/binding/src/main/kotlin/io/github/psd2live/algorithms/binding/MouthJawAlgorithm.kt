package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel

/** Adds the fitted mouth's local lower-face drop on the existing openness axis. */
class MouthJawAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        require(instance.version == VERSION && context.values.getValue("schema").jsonPrimitive.content == "mouth-jaw/1")
        val scaffold = input.providers("rig.scaffold").single {
            it.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content && it.version == 2 &&
                it.targets.containsAll(instance.targets)
        }
        require(input.providers("mouth/fitted").any {
            it.instanceId == instance.config.getValue("base").jsonPrimitive.content && it.version in 1..2 &&
                it.targets.containsAll(instance.targets)
        }) { "Mouth jaw v2 requires a compatible fitted mouth" }
        for ((type, id) in context.values.getValue("providers").jsonObject) {
            require(type in optionalUpstream && input.providers(type).any { it.instanceId == id.jsonPrimitive.content }) {
                "Missing source provider for mouth jaw v2: $type"
            }
        }
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val targets = context.values.getValue("targets").jsonObject
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("source_meshes"), checkpoint)
        val (originX, originY, axisX, axisY, width) = context.values.getValue("fit").jsonArray.map { it.jsonPrimitive.float }
        val opening = context.values.getValue("fit").jsonArray[5].jsonPrimitive.float
        val chin = context.values.getValue("chin").jsonPrimitive.float
        val reach = context.values.getValue("reach").jsonPrimitive.float
        val share = instance.config["values"]?.jsonObject?.get("share")?.jsonPrimitive?.float ?: 15f
        require(share in 5f..30f)
        val open = requireNotNull(input.parameter("ParamMouthOpenY"))
        require(!open.blend && open.default == 0f)
        val parameters = input.parameters
        val defaults = parameters.associate { it.id to it.default }
        val carriers = ArrayList<Deformer>()
        val updates = targets.map { (id, data) ->
            checkpoint()
            require(ModifierTarget("mesh", id) in instance.targets)
            val target = data.jsonObject
            val source = raw.getValue(id)
            val mesh = requireNotNull(input.mesh(id))
            val geometry = requireNotNull(mesh.geometry)
            val points = mouthSourcePoints(source, geometry, checkpoint)
            val (left, top, right, bottom) = target.getValue("frame").jsonArray.map { it.jsonPrimitive.float }
            val frame = Bounds(left, top, right, bottom)
            val invW = 1f / (right - left).coerceAtLeast(1e-4f)
            val invH = 1f / (bottom - top).coerceAtLeast(1e-4f)
            val nose = target.getValue("nose").jsonPrimitive.boolean
            val canonicalParent = target.getValue("parent").jsonPrimitive.contentOrNull
            val positions = mouthLayerPositions(points, frame, canonicalParent, checkpoint)
            val parent = canonicalParent?.let { ScaffoldBinding.deformerId(structure, id, it) }
            val rawGeometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(source.atlasUvs), Ints.wrap(source.triangles))
            val preserved = mesh.parent != parent || geometry != rawGeometry
            require(mesh.shapes.none { it.parameter == open.id }) { "Mouth jaw v2 requires an unedited openness track: $id" }
            val localModel = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(mesh)))
            val drawable = localModel.drawables.single()
            val rest: (org.umamo.runtime.model.ParameterId) -> Float = {
                defaults[it.raw] ?: 0f
            }
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it.raw] ?: 0f })
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            fun pose(amount: Float, strength: Float = share): MeshShape {
                val drop = strength / 100f * opening
                val deltas = FloatArray(points.size)
                for (i in points.indices step 2) {
                    if (i % 1024 == 0) checkpoint()
                    val x = (points[i] - originX) / width
                    val y = (points[i + 1] - originY) / width
                    val u = x * axisX + y * axisY
                    val v = x * -axisY + y * axisX
                    val t = ((v - .02f) / (chin - .02f).coerceAtLeast(.05f)).coerceIn(0f, 1f)
                    val weight = t * t * (3f - 2f * t)
                    val du = if (nose) 0f else -(u - .5f) * .25f * amount * drop * weight
                    val dv = if (nose) amount * drop * .08f else amount * drop * weight
                    deltas[i] = (du * axisX + dv * -axisY) * width * invW
                    deltas[i + 1] = (du * axisY + dv * axisX) * width * invH
                }
                return MeshShape(Floats.wrap(deltas), order, opacity,
                    Rgb(multiply.red, multiply.green, multiply.blue), Rgb(screen.red, screen.green, screen.blue))
            }
            val keys = if (reach > 1.001f) floatArrayOf(0f, 1f, reach) else floatArrayOf(0f, 1f)
            val neutralDeltas = meshGridDefaultDeltas(drawable, rest) ?: FloatArray(points.size)
            val jaw = BlendBinding(open.id, Floats.wrap(keys), 0, keys.map { amount ->
                if (amount == 0f) null else pose(amount).let { form ->
                    form.copy(deltas = Floats.wrap(FloatArray(points.size) { i -> neutralDeltas[i] + form.deltas[i] }))
                }
            })
            val projectedJaw = if (!preserved) emptyList() else {
                val rawJaw = org.umamo.runtime.model.BlendShapeBinding(org.umamo.runtime.model.ParameterId(open.id), keys, 0,
                    keys.map { amount ->
                        if (amount == 0f) null
                        else org.umamo.runtime.model.MeshForm(pose(amount).deltas.shared(), order, opacity, multiply, screen)
                    })
                mouthProjectShapes(input, parameters, mesh, drawable, parent, parent, points, positions, frame, null,
                    listOf(rawJaw), checkpoint)
            }
            // Keep the complete upstream grid, including coupled cells and interpolated defaults.
            // Shapes describe absolute forms; lowering subtracts the authored neutral above exactly once.
            val zero = MeshOffsets(Floats.wrap(FloatArray(points.size)))
            val witness = mesh.copy(id = "$id:openness", parent = null, channels = emptyMap(), shapes = emptyList(),
                offsets = KeyGrid(listOf(KeyAxis(open.id, Floats.wrap(keys))),
                    keys.indices.map { KeyCell(Ints.wrap(intArrayOf(it)), zero) }))
            val capture = context.values.getValue("providers").jsonObject.containsKey("mouth.capture")
            val shapes = if (!capture || share == 15f) mesh.shapes else mesh.shapes.map { binding ->
                if (binding.parameter != "ParamJawOpen") binding else {
                    val old = pose(1.2f, 15f).deltas
                    val changed = pose(1.2f).deltas
                    binding.copy(shapes = binding.shapes.mapIndexed { index, form -> form?.let {
                        val amount = binding.keys[index]
                        it.copy(deltas = Floats.wrap(FloatArray(points.size) { i ->
                            it.deltas[i] + (changed[i] - old[i]) * amount
                        }))
                    } })
                }
            }
            val parents = input.parentsOfMesh(id)
            val local = PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                meshes = listOf(mesh.copy(shapes = if (preserved) shapes else shapes + jaw), witness), deformers = parents))
            val loweredInput = if (!preserved) local else local.copy(
                drawables = listOf(local.drawables.first().let { it.copy(blendShapes = it.blendShapes + projectedJaw) }) + local.drawables.drop(1))
            val candidate = loweredInput.copy(deformers = loweredInput.deformers.map {
                when (it) {
                    is org.umamo.runtime.model.Deformer.Warp -> it.copy(blendShapes = emptyList())
                    is org.umamo.runtime.model.Deformer.Rotation -> it.copy(blendShapes = emptyList())
                }
            })
            val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(candidate)
            val added = lowered.deformers.drop(candidate.deformers.size)
            val remap = ModelIdRemap(deformers = added.associate {
                it.id to org.umamo.runtime.model.DeformerId(instance.outputId("$id/carrier:${it.id.raw}"))
            })
            carriers += added.map { PuppetIr.deformerToIr(remap.deformer(it)) }
            val result = lowered.drawables.first().let {
                it.copy(parentDeformerId = it.parentDeformerId?.let(remap::deformer))
            }
            ModifierObjectUpdate(mesh, PuppetIr.meshToIr(result))
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = carriers))
    }

    companion object {
        const val TYPE = "mouth.jaw"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e6a6177.v2/jaw"
        val mouthUpstream = setOf("mouth.outline", "mouth.vowel", "mouth.capture")
        val optionalUpstream = mouthUpstream + setOf("body/legacy", "face/legacy", "face/fitted", "face.depth-turn", "eye/fitted")
    }
}
