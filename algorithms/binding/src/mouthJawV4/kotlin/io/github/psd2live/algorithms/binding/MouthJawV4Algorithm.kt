package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel

object MouthJawV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthJawV4Algorithm.ENTRYPOINT to MouthJawV4Algorithm.VERSION) to MouthJawV4Algorithm(),
    ))
}

/** Rebuilds the lower-face openness grid after compatible source tongue motion. */
class MouthJawV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        require(instance.version == VERSION && context.values.getValue("schema").jsonPrimitive.content == "mouth-jaw/2")
        val scaffold = input.providers("rig.scaffold").single {
            it.instanceId == instance.config.getValue("scaffold").jsonPrimitive.content && it.version == 2 &&
                it.targets.containsAll(instance.targets)
        }
        require(input.providers("mouth/fitted").any {
            it.type == "mouth/fitted" && it.instanceId == instance.config.getValue("base").jsonPrimitive.content &&
                it.version == 3 && it.targets.containsAll(instance.targets) && it.config["scaffold"] == instance.config["scaffold"]
        }) { "Mouth jaw v4 requires a compatible fitted mouth" }
        for ((type, id) in context.values.getValue("providers").jsonObject) {
            require(type in optionalUpstream && input.providers(type).any { it.instanceId == id.jsonPrimitive.content }) {
                "Missing source provider for mouth jaw v4: $type"
            }
        }
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val targets = context.values.getValue("targets").jsonObject
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("source_meshes"), checkpoint)
        val source = MouthSourceContext.decode(ModifierAlgorithmContext(context.values.getValue("mouth").jsonObject,
            context.resources - "source_meshes"))
        val providers = context.values.getValue("providers").jsonObject
        val tongue = input.providers("mouth.tongue")
        val capturedTongue = providers["mouth.tongue"]?.jsonPrimitive?.content
        require(!source.options.tongue || capturedTongue != null) { "Missing captured tongue provider for mouth jaw v4" }
        require(if (capturedTongue == null) tongue.isEmpty() else tongue.singleOrNull()?.let {
            it.type == "mouth.tongue" && it.version == 3 && it.instanceId == capturedTongue &&
                it.config["scaffold"] == instance.config["scaffold"] && it.config["base"] == instance.config["base"] &&
                it.config["dependencies"]?.jsonObject?.get("mouth.capture") == providers["mouth.capture"] &&
                providers["mouth.capture"] != null && it.targets.containsAll(instance.targets)
        } == true) { "Mouth jaw v4 requires the compatible tongue on the same fitted mouth and capture providers" }
        val chin = context.values.getValue("chin").jsonPrimitive.float
        val share = instance.config["values"]?.jsonObject?.get("share")?.jsonPrimitive?.float ?: 15f
        require(share in 5f..30f)
        val jawMotion = source.copy(options = source.options.copy(jaw = true, jawShare = share / 100f)).rig()
        val captureBaseline by lazy { source.copy(options = source.options.copy(capture = true, jawShare = .15f)).rig() }
        val open = requireNotNull(input.parameter("ParamMouthOpenY"))
        require(!open.blend && open.default == 0f)
        val parameters = input.parameters
        val defaults = parameters.associate { it.id to it.default }
        val updates = targets.map { (id, data) ->
            checkpoint()
            require(ModifierTarget("mesh", id) in instance.targets)
            val target = data.jsonObject
            val source = raw.getValue(id)
            val points = source.rigPositions
            val mesh = requireNotNull(input.mesh(id))
            val geometry = requireNotNull(mesh.geometry)
            val (left, top, right, bottom) = target.getValue("frame").jsonArray.map { it.jsonPrimitive.float }
            val frame = Bounds(left, top, right, bottom)
            val nose = target.getValue("nose").jsonPrimitive.boolean
            val canonicalParent = target.getValue("parent").jsonPrimitive.contentOrNull
            val positions = FloatArray(points.size) { i ->
                if (canonicalParent == null) points[i]
                else if (i % 2 == 0) (points[i] - left) / (right - left).coerceAtLeast(1e-4f)
                else (points[i] - top) / (bottom - top).coerceAtLeast(1e-4f)
            }
            val parent = canonicalParent?.let { ScaffoldBinding.deformerId(structure, id, it) }
            require(mesh.parent == parent && geometry == MeshGeometry(Floats.wrap(positions), Floats.wrap(source.atlasUvs), Ints.wrap(source.triangles)) &&
                mesh.shapes.none { it.parameter == open.id }) { "Mouth jaw v4 requires an unedited source mesh and parent: $id" }
            val localModel = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(mesh)))
            val drawable = localModel.drawables.single()
            val rest: (org.umamo.runtime.model.ParameterId) -> Float = {
                if (it.raw in eyeOpen) 1f else 0f
            }
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { defaults[it.raw] ?: 0f })
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val mouthOpen = org.umamo.runtime.model.ParameterId(open.id)
            fun shapes(rig: MouthRig) = rig.faceShapes(points, frame, chin, nose, mouthOpen, null)
            fun form(deltas: FloatArray) = MeshShape(Floats.wrap(deltas), order, opacity,
                Rgb(multiply.red, multiply.green, multiply.blue), Rgb(screen.red, screen.green, screen.blue))
            val bindings = shapes(jawMotion)
            val openBinding = bindings.single { it.parameterId == mouthOpen }
            val keys = openBinding.keys
            val jaw = BlendBinding(open.id, Floats.wrap(keys), openBinding.neutralIndex,
                openBinding.forms.map { shape -> shape?.let { form(it.positionDeltas) } })
            // The native lowering folds jaw first and then the two head axes. Recover the independent
            // head tracks from their neutral slices, so the combined cells keep the same summation order.
            val prior = mesh.offsets
            val headAxes = prior?.axes.orEmpty()
            require(headAxes.all { it.parameter in headParameters }) {
                "Use mouth jaw v1 for an edited lower-face grid: $id"
            }
            val headDefaults = headAxes.map { axis -> axis.keys.shared().indexOfFirst { it == (defaults[axis.parameter] ?: 0f) } }
            require(headDefaults.all { it >= 0 }) { "Mouth jaw v4 requires neutral head keys: $id" }
            val headCells = prior?.cells.orEmpty().associateBy { it.coordinate }
            fun headForm(axis: Int, key: Int) = headCells.getValue(Ints.wrap(headDefaults.toIntArray().also { it[axis] = key })).form
            for (cell in headCells.values) {
                checkpoint()
                val components = headAxes.indices.map { headForm(it, cell.coordinate[it]).deltas }
                require(points.indices.all { i ->
                    var expected = 0f
                    for (part in components) expected += part[i]
                    expected == cell.form.deltas[i]
                }) { "Use mouth jaw v1 for a non-additive lower-face grid: $id" }
            }
            val neutral = form(FloatArray(points.size))
            val head = headAxes.mapIndexed { axis, keys ->
                BlendBinding(keys.parameter, keys.keys, headDefaults[axis], (0 until keys.keys.size).map { key ->
                    if (key == headDefaults[axis]) null else neutral.copy(deltas = headForm(axis, key).deltas)
                })
            }
            // The preceding mouth and face already key these ordinary axes elsewhere. Represent them
            // in this local lowering pass without including or writing the other upstream objects.
            val zero = MeshOffsets(Floats.wrap(FloatArray(points.size)))
            val witness = mesh.copy(id = "$id:openness", parent = null, channels = emptyMap(), shapes = emptyList(),
                offsets = KeyGrid(listOf(KeyAxis(open.id, Floats.wrap(keys))),
                    keys.indices.map { KeyCell(Ints.wrap(intArrayOf(it)), zero) }))
            val local = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(
                mesh.copy(offsets = null, shapes = listOf(jaw) + head), witness, witness.copy(id = "$id:head", offsets = prior))))
            val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(local)
            require(lowered.deformers.isEmpty()) { "Use mouth jaw v1 when the lower-face grid needs a carrier: $id" }
            val result = PuppetIr.meshToIr(lowered.drawables.first())
            val capture = providers.containsKey("mouth.capture")
            val shapes = if (!capture || share == 15f) mesh.shapes else mesh.shapes.map { binding ->
                if (binding.parameter != "ParamJawOpen") binding else {
                    val form = requireNotNull(binding.shapes.getOrNull(1))
                    require(binding.shapes.size == 2 && form.deltas == Floats.wrap(requireNotNull(shapes(captureBaseline)
                        .single { it.parameterId == MouthRig.JAW_OPEN }.forms[1]).positionDeltas)) {
                        "Use mouth jaw v1 for an edited capture jaw track: $id"
                    }
                    binding.copy(shapes = listOf(null, form.copy(deltas = Floats.wrap(requireNotNull(bindings
                        .single { it.parameterId == MouthRig.JAW_OPEN }.forms[1]).positionDeltas))))
                }
            }
            ModifierObjectUpdate(mesh, mesh.copy(offsets = result.offsets, shapes = shapes))
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    companion object {
        const val TYPE = "mouth.jaw"
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6d6f7574682e6a6177.v4/jaw"
        val mouthUpstream = setOf("mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue")
        val optionalUpstream = mouthUpstream + setOf("body/legacy", "face/legacy", "face/fitted", "face.depth-turn", "eye/fitted")
        private val headParameters = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
    }
}
