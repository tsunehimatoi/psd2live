package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Parameter as IrParameter
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Computes the legacy mouth's geometry, fade and clipping on the preceding authored rig. */
class MouthLegacyAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val captured = MouthLegacyInputs.decode(context)
        val providerId = instance.config.getValue("scaffold").jsonPrimitive.content
        val provider = context.upstream.singleOrNull {
            it.capability == "rig.scaffold" && it.instanceId == providerId
        } ?: error("Missing legacy mouth scaffold provider")
        require(provider.type == "rig.scaffold")
        val scaffold = RigModifierInstance(providerId, provider.type, provider.version, provider.targets,
            config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
        val definitions = parameters(captured.formName, captured.openName)
        val existing = input.parameters.associateBy { it.id }
        val added = definitions.filter { it.id !in existing }
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val meshes = input.meshes.associateBy { it.id }
        require(targets.all { it in meshes }) { "Unknown legacy mouth target" }
        val sources = ScaffoldMeshContext.decodeRawMeshes(captured.scaffold.resources.getValue("source_meshes"), checkpoint)
        val neutral by lazy { ScaffoldAlgorithm().buildSource(captured.scaffold, checkpoint) }
        val candidates = captured.layers.values.filter { it.maskSource && it.canonical in meshes }
        val live by lazy {
            PuppetIr.toPuppet(input.copy(parameters = input.parameters + added, meshes = input.meshes.filter { it.id in targets && it.id in captured.layers }))
        }
        val changes = instance.targets.mapNotNull { target ->
            checkpoint()
            val before = meshes.getValue(target.id)
            val layer = captured.layers[target.id] ?: return@mapNotNull null
            val actual = live.drawables.single { it.id.raw == target.id }
            val grid = if (layer.tag in MouthLegacyInputs.OPEN_TAGS && actual.mesh != null) {
                val raw = sources.getValue(layer.canonical)
                val original = neutral.drawables.single { it.id.raw == layer.canonical }.copy(id = actual.id)
                val authoredMesh = requireNotNull(actual.mesh)
                val originalMesh = requireNotNull(original.mesh)
                val parent = original.parentDeformerId?.let { ScaffoldBinding.deformerId(scaffold, target.id, it.raw) }
                val desired = geometry(raw.rigPositions, layer, captured.motion, checkpoint)
                val unchanged = actual.parentDeformerId?.raw == parent &&
                    authoredMesh.positions.contentEquals(originalMesh.positions) &&
                    authoredMesh.indices.contentEquals(originalMesh.indices) &&
                    authoredMesh.uvs.contentEquals(originalMesh.uvs) &&
                    before.offsets == PuppetIr.meshToIr(original).offsets
                if (unchanged) desired else {
                    val vertices = correspondence(originalMesh, authoredMesh, checkpoint)
                    val baseline = neutral.copy(drawables = listOf(original),
                        canvasWidth = live.canvasWidth, canvasHeight = live.canvasHeight,
                        worldOriginX = live.worldOriginX, worldOriginY = live.worldOriginY)
                    GeometryResidual.geometry(live, baseline, baseline.copy(drawables = listOf(original.copy(geometryGrid = desired))),
                        actual.id, vertices, vertices, checkpoint)
                }
            } else actual.geometryGrid
            val opacity = opacity(layer, captured, live, actual, checkpoint)
            val mask = if (layer.tag in MouthLegacyInputs.COMPONENTS) {
                val expected = listOfNotNull(layer.originalMask)
                val desired = listOfNotNull(selectMask(layer, candidates)?.canonical)
                if (expected == desired) before.maskedBy else {
                    require(!before.invertMask && (before.maskedBy == expected || before.maskedBy == desired)) {
                        "${instance.id}: incompatible authored masks on ${before.id}"
                    }
                    desired
                }
            } else before.maskedBy
            val converted = PuppetIr.meshToIr(actual.copy(geometryGrid = grid, channelGrids = opacity))
            before.copy(offsets = converted.offsets, channels = converted.channels, maskedBy = mask)
                .takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        val tree = parameterTree(input, instance, added, captured.groupName)
        val pose = input.restPose + (OPEN.raw to 1f)
        return ModifierModelChanges(
            parameters = ModifierObjectChanges(added = added, reused = definitions.mapNotNull { existing[it.id] }),
            meshes = ModifierObjectChanges(updated = changes),
            parameterTree = tree.takeIf { it != input.parameterTree }?.let { ModifierFieldChange(input.parameterTree, it) },
            restPose = pose.takeIf { it != input.restPose }?.let { ModifierFieldChange(input.restPose, it) },
        )
    }

    private fun parameterTree(input: RigIR, instance: RigModifierInstance, added: List<IrParameter>, name: String): List<ParameterNode> {
        if (added.isEmpty()) return input.parameterTree
        val id = instance.outputId("parameter-folder:ParamGroupMouth")
        val items = added.map { ParameterNode.Param(it.id) }
        val tree = input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
        var found = false
        fun append(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == id) {
                found = true
                node.copy(children = node.children + items)
            } else node.copy(children = append(node.children))
        }
        val updated = append(tree)
        return if (found) updated else updated + ParameterNode.Group(id, name, true, items)
    }

    private fun selectMask(layer: MouthLegacyInputs.Layer, masks: List<MouthLegacyInputs.Layer>): MouthLegacyInputs.Layer? {
        val side = masks.filter { it.side == layer.side }
        val exact = side.filter { it.variant == layer.variant }
        return exact.ifEmpty { side.ifEmpty { masks.filter { it.side == Side.NONE }.ifEmpty { masks } } }.minByOrNull { mask ->
            val dx = mask.bounds.centerX - layer.bounds.centerX
            val dy = mask.bounds.centerY - layer.bounds.centerY
            dx * dx + dy * dy
        }
    }

    private fun opacity(layer: MouthLegacyInputs.Layer, inputs: MouthLegacyInputs, model: PuppetModel, actual: Drawable,
                        checkpoint: () -> Unit): ChannelGrids {
        if (!layer.preset) return actual.channelGrids
        val closed = layer.tag == "MOUTH_CLOSE"
        if (!closed && layer.tag !in MouthLegacyInputs.COMPONENTS &&
            !(layer.tag == "MOUTH_OPEN" && inputs.closedMouth)) return actual.channelGrids
        val fade = (inputs.teethFade / 100f).coerceAtLeast(0.01f)
        val keys = if (closed) floatArrayOf(0f, 1f) else floatArrayOf(0f, fade, 1f)
        val grid = KeyformGrid(listOf(KeyformAxis(OPEN, keys)), keys.mapIndexed { i, open ->
            KeyformCell<ChannelValue>(intArrayOf(i), ChannelValue.Scalar(if (closed) 1f - open else (open / fade).coerceIn(0f, 1f)))
        })
        val rest = if (closed) 0f else layer.opacity
        val existing = actual.channelGrids
        if (existing[FormChannel.OPACITY] == null && actual.opacity == rest)
            return ChannelGrids(existing.gridsByChannel + (FormChannel.OPACITY to grid))
        val before = actual.copy(opacity = rest, channelGrids = ChannelGrids.Empty)
        val after = before.copy(opacity = layer.opacity, channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to grid)))
        val merged = ChannelResidual.channels(model, actual.copy(channelGrids =
            ChannelGrids(existing.gridsByChannel.filterKeys { it == FormChannel.OPACITY })), before, after, checkpoint)
        return ChannelGrids(existing.gridsByChannel + merged.gridsByChannel)
    }

    private fun geometry(source: FloatArray, layer: MouthLegacyInputs.Layer, motion: MouthFittedInputs.Fallback,
                         checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm> {
        val formKeys = floatArrayOf(-1f, 0f, 1f)
        val openKeys = floatArrayOf(0f, 0.5f, 1f)
        val width = layer.frame.width.coerceAtLeast(1e-4f)
        val height = layer.frame.height.coerceAtLeast(1e-4f)
        val cells = buildList {
            for ((f, form) in formKeys.withIndex()) for ((o, open) in openKeys.withIndex()) {
                checkpoint()
                val deltas = FloatArray(source.size)
                for (i in source.indices step 2) {
                    if (i % 1024 == 0) checkpoint()
                    val (x, y) = motion.point(source[i], source[i + 1], layer.aperture, form, open)
                    deltas[i] = (x - source[i]) / width
                    deltas[i + 1] = (y - source[i + 1]) / height
                }
                add(KeyformCell(intArrayOf(f, o), MeshDeltaForm(deltas)))
            }
        }
        return KeyformGrid(listOf(KeyformAxis(FORM, formKeys), KeyformAxis(OPEN, openKeys)), cells)
    }

    /** UVs identify source artwork on authored tessellations, independently of their posed positions. */
    private fun correspondence(source: DrawableMesh, authored: DrawableMesh, checkpoint: () -> Unit): List<VertexSource> {
        if (source.uvs.contentEquals(authored.uvs)) return List(authored.vertexCount) { VertexSource.FromOld(it) }
        return List(authored.vertexCount) { index ->
            checkpoint()
            val x = authored.uvs[index * 2]
            val y = authored.uvs[index * 2 + 1]
            val nearest = (0 until source.vertexCount).minBy { vertex ->
                val dx = source.uvs[vertex * 2].toDouble() - x
                val dy = source.uvs[vertex * 2 + 1].toDouble() - y
                dx * dx + dy * dy
            }
            if (abs(source.uvs[nearest * 2] - x) <= 1e-7f && abs(source.uvs[nearest * 2 + 1] - y) <= 1e-7f) {
                VertexSource.FromOld(nearest)
            } else {
                val point = BindingPaths.bind(source.uvs, source.indices, x, y)
                if (minOf(point.wa, point.wb, point.wc) < -1e-4f) VertexSource.FromOld(nearest)
                else {
                    val a = point.wa.coerceAtLeast(0f)
                    val b = point.wb.coerceAtLeast(0f)
                    val c = point.wc.coerceAtLeast(0f)
                    val sum = a + b + c
                    VertexSource.BarycentricOf(point.a, point.b, point.c, a / sum, b / sum, c / sum)
                }
            }
        }
    }

    companion object {
        const val TYPE = "mouth/legacy"
        const val VERSION = 2
        private val FORM = ParameterId("ParamMouthForm")
        private val OPEN = ParameterId("ParamMouthOpenY")

        fun parameters(formName: String, openName: String) = listOf(
            IrParameter(FORM.raw, formName, -1f, 1f, 0f), IrParameter(OPEN.raw, openName, 0f, 1f, 0f),
        )
    }
}
