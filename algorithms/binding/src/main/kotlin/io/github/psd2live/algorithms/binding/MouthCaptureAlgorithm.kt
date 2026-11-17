package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Adds facial capture axes to the fitted mouth and its surrounding skin. */
class MouthCaptureAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val base = instance.config.getValue("base").jsonPrimitive.content
        val scaffoldProvider = requireNotNull(input.providers("rig.scaffold").singleOrNull {
            it.instanceId == scaffold && it.version == 2 && it.targets.containsAll(instance.targets)
        }) { "Mouth capture v2 requires a shared v2 scaffold" }
        require(input.providers("mouth/fitted").any {
            it.instanceId == base && it.version in 1..2 && it.targets.containsAll(instance.targets) &&
                it.config["scaffold"] == JsonPrimitive(scaffold)
        }) { "Mouth capture v2 requires a compatible fitted mouth provider" }

        val structure = RigModifierInstance(scaffold, scaffoldProvider.type, scaffoldProvider.version, scaffoldProvider.targets,
            config = scaffoldProvider.config, outputAliases = scaffoldProvider.outputAliases, plugin = scaffoldProvider.plugin)
        val context = input.context
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("source").jsonObject,
            context.resources, context.upstream))
        val vertices = requireNotNull(captured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val mouth = (captured.source ?: return ModifierModelChanges()).rig()
        require(mouth.options.capture)
        val lippedSkin = context.values.getValue("lippedSkin").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val follows = context.values.getValue("follows").jsonObject
        require(captured.layers.keys == vertices.targets.keys && captured.layers.keys == follows.keys &&
            captured.layers.keys.containsAll(lippedSkin) && follows.keys.all { ModifierTarget("mesh", it) in instance.targets })
        val live = input.parameters
        val declared = parameters(context)
        val byParameter = live.associateBy { it.id }
        val additions = declared.filter { parameter ->
            val existing = byParameter[parameter.id]
            require(existing == null || existing.copy(name = parameter.name) == parameter) {
                "Incompatible capture parameter ${parameter.id}"
            }
            existing == null
        }
        val mouthOpen = ParameterId("ParamMouthOpenY")
        val mouthForm = ParameterId("ParamMouthForm")
        require(listOf(mouthOpen, mouthForm).all { axis -> input.parameter(axis.raw)?.let { !it.blend && it.default == 0f } == true })
        val rest = { parameter: ParameterId -> byParameter[parameter.raw]?.default ?: if (parameter.raw in eyeOpen) 1f else 0f }
        val meshes = captured.layers.keys.map { requireNotNull(input.mesh(it)) }
        val tree = input.parameterTree
        val model = PuppetIr.toPuppet(RigIR(input.canvas, live + additions, meshes = meshes, parameterTree = tree))
        val carriers = ArrayList<Deformer>()
        val untouchedShapes = HashMap<String, List<BlendBinding<MeshShape>>>()
        val drawables = meshes.zip(model.drawables).map { (mesh, drawable) ->
            checkpoint()
            val id = mesh.id
            val layer = captured.layers.getValue(id)
            val target = vertices.targets.getValue(id)
            val geometry = requireNotNull(mesh.geometry)
            val material = raw.getValue(target.canonical)
            val frame = layer.frame
            val rawPositions = mouthLayerPositions(material.rigPositions, frame, target.parent, checkpoint)
            val parent = target.parent?.let { ScaffoldBinding.deformerId(structure, id, it) }
            val matchesSource = mesh.parent == parent &&
                geometry == MeshGeometry(Floats.wrap(rawPositions), Floats.wrap(material.atlasUvs), Ints.wrap(material.triangles))
            require(mesh.shapes.none { it.parameter in captureIds }) {
                "Mouth capture v2 requires a mesh without existing capture tracks: $id"
            }
            require(mesh.channels.values.none { track -> track.axes.any { it.parameter in captureIds } }) {
                "Mouth capture v2 found existing capture channel keys: $id"
            }
            val points = when {
                matchesSource -> material.rigPositions
                mesh.parent == parent && geometry.uvs == Floats.wrap(material.atlasUvs) ->
                    mouthRigPointsFromLocal(geometry.positions, frame, target.parent != null, checkpoint)
                else -> mouthSourcePoints(material, geometry, checkpoint)
            }
            val positions = if (matchesSource) rawPositions else mouthLayerPositions(points, frame, target.parent, checkpoint)
            val follow = follows.getValue(id).jsonPrimitive.contentOrNull
            val role = follow?.let(MouthRig.Follow::valueOf)
            val closed = layer.tag == "MOUTH_CLOSE"
            val fittedGrid = when {
                closed -> null
                role != null -> mouth.grid(points, frame, mouthForm, mouthOpen, role, captured.chin)
                id in lippedSkin -> mouth.faceGrid(points, frame, mouthForm, mouthOpen, captured.chin)
                else -> null
            }
            val baseline = fittedGrid?.let { targetGrid ->
                val expanded = requireNotNull(PuppetIr.meshToIr(drawable.copy(geometryGrid = targetGrid)).offsets)
                KeyGrid(expanded.axes.dropLast(1), expanded.cells.mapNotNull { cell ->
                    val coordinate = cell.coordinate.shared()
                    if (coordinate.last() != 1) null else cell.copy(coordinate = Ints.wrap(coordinate.copyOf(coordinate.size - 1)))
                })
            }
            val shapes = if (role == null) {
                mouth.faceShapes(points, frame, captured.chin, layer.tag == "NOSE", mouthOpen, null, id in lippedSkin)
            } else {
                mouth.shapes(points, frame, closed, { opens -> if (!opens) Float.NaN else if (closed) 0f else 1f },
                    layer.tag == "TONGUE", role, chin = captured.chin)
            }
            val opacity = if (drawable.opacity <= 0f) 0f else drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, { byParameter[it.raw]?.default ?: 0f })
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val neutralized = shapes.filter { it.parameterId.raw in captureIds }.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, order,
                        if (drawable.opacity <= 0f) 0f else if (it.opacity.isNaN()) opacity else it.opacity * drawable.opacity,
                        multiply, screen) }
                })
            }
            val (vowels, otherShapes) = drawable.blendShapes.partition { it.parameterId in vowelIds }
            untouchedShapes[id] = mesh.shapes.filter { binding ->
                binding.parameter !in captureIds && vowelIds.none { it.raw == binding.parameter }
            }
            val canReplaceGrid = mesh.parent == parent && (fittedGrid == null || mesh.offsets == baseline)
            if (canReplaceGrid) {
                val grid = fittedGrid ?: drawable.geometryGrid
                drawable.copy(geometryGrid = grid, blendShapes = vowels + neutralized)
            } else {
                val expandedGrid = if (mesh.parent == parent && fittedGrid != null && mesh.offsets != null) {
                    expandOffsetsWithPress(mesh.offsets!!, fittedGrid, baseline!!)
                } else null
                val sourceGrid = when {
                    role == null -> mouth.faceGrid(points, frame, mouthForm, mouthOpen, captured.chin)
                    closed -> mouth.closedGrid(points, frame, mouthForm)
                    else -> mouth.grid(points, frame, mouthForm, mouthOpen, role, captured.chin)
                }
                val pressShape = if (expandedGrid != null || fittedGrid == null) emptyList() else {
                    val pressForms = MouthRig.PRESS_KEYS.mapIndexed { pIdx, press ->
                        if (press == 0f) null else {
                            val cellDeltas = pressSliceDeltas(fittedGrid, pIdx)
                            val formOpacity = if (press > 0f && role != null) 1f * drawable.opacity else opacity
                            MeshForm(cellDeltas, order, formOpacity, multiply, screen)
                        }
                    }
                    listOf(org.umamo.runtime.model.BlendShapeBinding(MouthRig.PRESS_OPEN, MouthRig.PRESS_KEYS, 1, pressForms))
                }
                val toProject = neutralized + pressShape
                val projected = mouthProjectShapes(input, live + additions, mesh, drawable, target.parent, parent,
                    points, positions, frame, sourceGrid, toProject, checkpoint)
                val baseGrid = expandedGrid?.let { PuppetIr.toPuppet(RigIR(input.canvas, live + additions, meshes = listOf(mesh.copy(offsets = it)))).drawables.single().geometryGrid }
                    ?: drawable.geometryGrid
                drawable.copy(geometryGrid = baseGrid, blendShapes = vowels + projected)
            }
        }
        // Fold lip-press opacity into channel tracks using the same keyed axis as the fitted geometry.
        val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(model.copy(drawables = drawables))
        val addedCarriers = lowered.deformers.drop(model.deformers.size)
        val remap = ModelIdRemap(deformers = addedCarriers.associate {
            it.id to org.umamo.runtime.model.DeformerId(instance.outputId("carrier:${it.id.raw}"))
        })
        carriers += addedCarriers.map { PuppetIr.deformerToIr(remap.deformer(it)) }
        val updates = meshes.zip(lowered.drawables).mapNotNull { (mesh, drawable) ->
            val next = PuppetIr.meshToIr(remap.drawable(drawable))
            val extra = untouchedShapes[mesh.id].orEmpty()
            val after = mesh.copy(parent = next.parent, geometry = next.geometry,
                shapes = next.shapes + extra, offsets = next.offsets, channels = next.channels)
            after.takeIf { it != mesh }?.let { ModifierObjectUpdate(mesh, it) }
        }
        val parameterView = model.copy(parameters = model.parameters.filter { it.id.raw in byParameter })
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupMouth")),
            context.values.getValue("folder").jsonPrimitive.content, true, declared.map { PuppetParameterNode.Param(ParameterId(it.id)) })
        val updatedTree = BindingMerge.appendTree(parameterView, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(parameterView.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = updates),
            deformers = ModifierObjectChanges(added = carriers),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }

    private fun pressSliceDeltas(grid: org.umamo.runtime.model.KeyformGrid<org.umamo.runtime.model.MeshDeltaForm>, pressIndex: Int): FloatArray {
        val formIdx = grid.axes[0].keys.indexOfFirst { it == 0f }.coerceAtLeast(0)
        val openIdx = grid.axes[1].keys.indexOfFirst { it == 0f }.coerceAtLeast(0)
        val n0 = grid.axes[0].keys.size
        val n1 = grid.axes[1].keys.size
        return grid.cells[pressIndex * n0 * n1 + openIdx * n0 + formIdx].form.positionDeltas
    }

    private fun expandOffsetsWithPress(
        existing: KeyGrid<MeshOffsets>,
        fittedGrid: org.umamo.runtime.model.KeyformGrid<org.umamo.runtime.model.MeshDeltaForm>,
        baseline: KeyGrid<MeshOffsets>,
    ): KeyGrid<MeshOffsets>? {
        val fAxis = existing.axes.indexOfFirst { it.parameter == "ParamMouthForm" }
        val oAxis = existing.axes.indexOfFirst { it.parameter == "ParamMouthOpenY" }
        if (fAxis < 0 || oAxis < 0 || existing.axes.any { it.parameter == MouthRig.PRESS_OPEN.raw }) return null
        if (existing.cells.size * MouthRig.PRESS_KEYS.size > 1024) return null
        val pressKeys = MouthRig.PRESS_KEYS
        val baseIr = KeyGrid(baseline.axes, baseline.cells)
        val baseLookup = baseIr.cells.associate { it.coordinate.shared().toList() to it.form.deltas.shared() }
        val fKeys = fittedGrid.axes[0].keys
        val oKeys = fittedGrid.axes[1].keys
        val n0 = fKeys.size
        val n1 = oKeys.size
        val size = existing.cells.firstOrNull()?.form?.deltas?.size ?: return null
        fun sliceAt(fVal: Float, oVal: Float, pressIdx: Int): FloatArray {
            val fi = fKeys.indexOfFirst { kotlin.math.abs(it - fVal) < 1e-4f }
            val oi = oKeys.indexOfFirst { kotlin.math.abs(it - oVal) < 1e-4f }
            if (fi >= 0 && oi >= 0) {
                val pDeltas = fittedGrid.cells[pressIdx * n0 * n1 + oi * n0 + fi].form.positionDeltas
                val bDeltas = baseLookup[listOf(fi, oi)] ?: FloatArray(size)
                return FloatArray(size) { i -> pDeltas[i] - bDeltas[i] }
            }
            return FloatArray(size)
        }
        val newAxes = existing.axes + KeyAxis(MouthRig.PRESS_OPEN.raw, Floats.wrap(pressKeys))
        val newCells = ArrayList<KeyCell<MeshOffsets>>(existing.cells.size * pressKeys.size)
        for ((pIdx, press) in pressKeys.withIndex()) {
            for (cell in existing.cells) {
                val coord = cell.coordinate.shared()
                val fVal = existing.axes[fAxis].keys[coord[fAxis]]
                val oVal = existing.axes[oAxis].keys[coord[oAxis]]
                val shift = if (press == 0f) null else sliceAt(fVal, oVal, pIdx)
                val deltas = if (shift == null) cell.form.deltas else Floats.wrap(FloatArray(size) { i ->
                    cell.form.deltas[i] + shift[i]
                })
                newCells += KeyCell(Ints.wrap(coord + pIdx), MeshOffsets(deltas))
            }
        }
        return KeyGrid(newAxes, newCells)
    }

    companion object {
        const val TYPE = "mouth.capture"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.6d6f7574682e63617074757265.v2/capture"
        val WRITES = setOf("mesh", "deformer", "parameter", "hierarchy")
        val optionalUpstream = setOf("face/fitted", "face.depth-turn", "mouth.outline", "mouth.vowel")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val ranges = linkedMapOf(
            MouthRig.MOUTH_X to -1f, MouthRig.PUCKER_WIDEN to -1f, MouthRig.SHRUG to 0f,
            MouthRig.PRESS_OPEN to -1f, MouthRig.FUNNEL to 0f, MouthRig.CORNER_L to -1f,
            MouthRig.CORNER_R to -1f, MouthRig.PUCKER_WIDEN_L to -1f, MouthRig.PUCKER_WIDEN_R to -1f,
            MouthRig.CHEEK_PUFF to 0f, MouthRig.JAW_OPEN to 0f,
        )
        private val captureIds = ranges.keys.mapTo(hashSetOf()) { it.raw }
        private val vowelIds = MouthRig.VOWELS.mapTo(hashSetOf()) { it.second }

        fun context(source: ModifierAlgorithmContext, follows: Map<String, MouthRig.Follow?>, lippedSkin: Set<String>, names: Map<String, String>, folder: String) =
            ModifierAlgorithmContext(buildJsonObject {
                put("schema", "mouth-capture/1")
                put("source", source.values)
                put("follows", JsonObject(follows.mapValues { it.value?.name?.let(::JsonPrimitive) ?: JsonNull }))
                put("lippedSkin", JsonArray(lippedSkin.map(::JsonPrimitive)))
                put("names", JsonObject(names.mapValues { JsonPrimitive(it.value) }))
                put("folder", folder)
            }, source.resources)

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> {
            require(context.values.keys == setOf("schema", "source", "follows", "lippedSkin", "names", "folder") &&
                context.values.getValue("schema").jsonPrimitive.content == "mouth-capture/1")
            val names = context.values.getValue("names").jsonObject
            return ranges.map { (id, min) ->
                val blend = id != MouthRig.PRESS_OPEN
                Parameter(id.raw, names.getValue(id.raw).jsonPrimitive.content, min, 1f, 0f, blend = blend,
                    keys = if (!blend) null else Floats.wrap(if (min < 0f) floatArrayOf(-1f, 0f, 1f) else floatArrayOf(0f, 1f)))
            }
        }
    }
}
