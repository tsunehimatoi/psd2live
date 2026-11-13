package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId

/** Generates the legacy brow translation in its measured scaffold frame. */
class BrowLegacyAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version >= 1 && instance.targets.all { it.kind == "mesh" }) { "Invalid legacy brow invocation" }
        val source = BrowLegacyInputs.decode(input.context)
        val lift = instance.config["tuning"]?.jsonObject?.get("browLift")?.jsonPrimitive?.float ?: source.lift
        require(lift.isFinite() && lift in 0f..40f)
        val definitions = parameters(source)
        val added = definitions.filter { input.parameter(it.id) == null }
        val updates = instance.targets.mapNotNull { target ->
            checkpoint()
            val mesh = requireNotNull(input.mesh(target.id)) { "Missing brow target: ${target.id}" }
            val brow = source.targets[target.id] ?: return@mapNotNull null
            val geometry = mesh.geometry ?: return@mapNotNull null
            val existing = mesh.offsets
            val offsets = if (mesh.parent == brow.parent &&
                (existing == null || existing.axes.isEmpty() && existing.cells.singleOrNull()?.form?.deltas?.shared()?.all { it == 0f } == true)
            ) grid(brow, lift, geometry.positions.size) else residual(input, mesh, brow, lift, definitions, checkpoint)
            val updated = mesh.copy(offsets = offsets)
            ModifierObjectUpdate(mesh, updated).takeIf { mesh != updated }
        }
        val originalTree = input.parameterTree
        val groupId = instance.outputId("parameter-folder:$GROUP")
        val nodes = added.map { ParameterNode.Param(it.id) }
        val existingTree = originalTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } }
        fun contains(nodes: List<ParameterNode>): Boolean =
            nodes.filterIsInstance<ParameterNode.Group>().any { it.id == groupId || contains(it.children) }
        fun insert(tree: List<ParameterNode>): List<ParameterNode> = tree.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == groupId) node.copy(children = node.children + nodes)
            else node.copy(children = insert(node.children))
        }
        val tree = when {
            nodes.isEmpty() -> originalTree
            contains(existingTree) -> insert(existingTree)
            else -> existingTree + ParameterNode.Group(groupId, source.groupName, true, nodes)
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added), meshes = ModifierObjectChanges(updated = updates),
            parameterTree = tree.takeIf { it != originalTree }?.let { ModifierFieldChange(originalTree, it) })
    }

    private fun residual(input: ModifierEvaluationInput, mesh: Mesh, brow: BrowLegacyInputs.Brow, lift: Float,
                         definitions: List<Parameter>, checkpoint: () -> Unit): KeyGrid<MeshOffsets>? {
        val parents = input.parentsOfMesh(mesh.id).associateByTo(linkedMapOf()) { it.id }
        var parent = brow.parent
        val seen = hashSetOf<String>()
        while (parent != null) {
            checkpoint()
            require(seen.add(parent)) { "Cyclic legacy brow scaffold branch" }
            val node = parents[parent] ?: requireNotNull(input.deformer(parent)) { "Missing legacy brow scaffold frame: $parent" }
            parents[node.id] = node
            parent = node.parent
        }
        val parameters = input.parameters.let { existing -> existing + definitions.filter { added -> existing.none { it.id == added.id } } }
        val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, deformers = parents.values.toList(), meshes = listOf(mesh)))
        val drawable = current.drawables.single()
        val neutral = BindingProjection.retain(current, emptySet(), checkpoint = checkpoint)
        val sourceMesh = neutral.drawables.single().copy(geometryGrid = null)
        val before = if (mesh.parent == brow.parent) neutral.copy(drawables = listOf(sourceMesh)) else {
            val from = neutral.copy(drawables = listOf(sourceMesh))
            val to = from.copy(drawables = listOf(sourceMesh.copy(parentDeformerId = brow.parent?.let(::DeformerId))))
            BindingFrames.carriedToParent(from, to, DrawableId(mesh.id), checkpoint)
        }
        val canonical = before.drawables.single()
        val count = requireNotNull(canonical.mesh).positions.size
        val grid = grid(brow, lift, count)
        val desiredGrid = PuppetIr.toPuppet(RigIR(input.canvas, definitions, meshes = listOf(mesh.copy(offsets = grid)))).drawables.single().geometryGrid
        val desired = before.copy(drawables = listOf(canonical.copy(geometryGrid = desiredGrid)))
        val vertices = List(count / 2) { VertexSource.FromOld(it) }
        val offsets = GeometryResidual.geometry(current, before, desired, DrawableId(mesh.id), vertices, vertices, checkpoint)
        return PuppetIr.meshToIr(drawable.copy(geometryGrid = offsets)).offsets
    }

    companion object {
        const val GROUP = "ParamGroupBrows"
        private const val LEFT = "ParamBrowLY"
        private const val RIGHT = "ParamBrowRY"

        fun parameters(inputs: BrowLegacyInputs): List<Parameter> =
            listOf(Parameter(LEFT, inputs.leftName, -1f, 1f, 0f), Parameter(RIGHT, inputs.rightName, -1f, 1f, 0f))

        fun grid(brow: BrowLegacyInputs.Brow, lift: Float, components: Int): KeyGrid<MeshOffsets> {
            val parameter = if (brow.side == Side.LEFT) LEFT else RIGHT
            val keys = Floats.values(-1f, 0f, 1f)
            return KeyGrid(listOf(KeyAxis(parameter, keys)), (0 until keys.size).map { index ->
                val dy = -keys[index] * brow.height * lift / 100f / brow.frameHeight
                KeyCell(Ints.values(index), MeshOffsets(Floats.wrap(FloatArray(components) { if (it % 2 == 0) 0f else dy })))
            })
        }
    }
}
