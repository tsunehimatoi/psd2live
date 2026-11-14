package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyCell
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.MeshOffsets
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Splits each targeted mesh into separate meshes for its disconnected triangle islands. */
class MeshSplitAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        checkpoint()
        val targetMeshIds = instance.targets.filter { it.kind == "mesh" }.map { it.id }
        if (targetMeshIds.isEmpty()) return ModifierModelChanges()
        val targetSet = targetMeshIds.toHashSet()
        val customPieceIds = stringListConfig(instance, "pieceIds")
        val customNames = stringListConfig(instance, "names")

        val splits = linkedMapOf<String, MeshSplitResult>()
        for (mesh in input.meshes) {
            checkpoint()
            if (mesh.id !in targetSet) continue
            val split = splitMesh(
                mesh, instance,
                if (targetMeshIds.size == 1) customPieceIds else null,
                if (targetMeshIds.size == 1) customNames else null,
                checkpoint,
            ) ?: continue
            splits[mesh.id] = split
        }
        if (splits.isEmpty()) return ModifierModelChanges()
        return MeshSplitReferences(input, splits, instance, checkpoint).changes()
    }

    private fun stringListConfig(instance: RigModifierInstance, key: String): List<String>? {
        val array = instance.config[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }

    private fun splitMesh(
        mesh: Mesh,
        instance: RigModifierInstance,
        customPieceIds: List<String>?,
        customNames: List<String>?,
        checkpoint: () -> Unit,
    ): MeshSplitResult? {
        val geometry = mesh.geometry ?: return null
        val positions = geometry.positions.shared()
        val uvs = geometry.uvs.shared()
        val indices = geometry.indices.shared()
        val vertexCount = positions.size / 2
        val triCount = indices.size / 3
        if (vertexCount <= 0 || triCount <= 1) return null

        if (positions.size % 2 != 0 || uvs.size != positions.size || indices.size % 3 != 0 ||
            indices.any { it !in 0 until vertexCount }) {
            throw MeshSplitRejected("mesh.split.invalid_geometry", "mesh:${mesh.id}")
        }
        val parent = IntArray(vertexCount) { it }
        fun find(v: Int): Int {
            var x = v
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (t in 0 until triCount) {
            if (t % 256 == 0) checkpoint()
            val base = t * 3
            val a = indices[base]
            val b = indices[base + 1]
            val c = indices[base + 2]
            if (a !in 0 until vertexCount || b !in 0 until vertexCount || c !in 0 until vertexCount) continue
            union(a, b)
            union(a, c)
        }

        val triByRoot = linkedMapOf<Int, MutableList<Int>>()
        for (t in 0 until triCount) {
            val a = indices[t * 3]
            if (a !in 0 until vertexCount) continue
            triByRoot.getOrPut(find(a)) { mutableListOf() }.add(t)
        }
        if (triByRoot.size < 2) return null

        fun checkDeltas(deltas: Floats) {
            if (deltas.size != 0 && deltas.size != positions.size)
                throw MeshSplitRejected("mesh.split.invalid_keyform", "mesh:${mesh.id}")
        }
        mesh.offsets?.cells?.forEach { checkDeltas(it.form.deltas) }
        mesh.shapes.forEach { binding -> binding.shapes.forEach { it?.let { shape -> checkDeltas(shape.deltas) } } }

        data class Island(val triangles: List<Int>, val vertices: IntArray, val centerX: Float, val centerY: Float)

        val islands = triByRoot.values.map { triangles ->
            checkpoint()
            val seen = linkedSetOf<Int>()
            for (t in triangles) {
                val base = t * 3
                seen.add(indices[base])
                seen.add(indices[base + 1])
                seen.add(indices[base + 2])
            }
            val vertices = seen.toIntArray().also { it.sort() }
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (v in vertices) {
                val x = positions[v * 2]
                val y = positions[v * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            Island(triangles, vertices, (minX + maxX) * 0.5f, (minY + maxY) * 0.5f)
        }.sortedWith(compareBy<Island>({ it.centerY }, { it.centerX }))

        val validIds = customPieceIds?.takeIf { it.size == islands.size && it.distinct().size == it.size }
        val validNames = customNames?.takeIf { it.size == islands.size }
        val remap = IntArray(vertexCount)
        val vertexMap = arrayOfNulls<MeshSplitVertex>(vertexCount)
        val pieces = islands.mapIndexed { pieceIndex, island ->
            checkpoint()
            remap.fill(-1)
            val newPos = FloatArray(island.vertices.size * 2)
            val newUvs = FloatArray(island.vertices.size * 2)
            island.vertices.forEachIndexed { newIdx, oldIdx ->
                remap[oldIdx] = newIdx
                newPos[newIdx * 2] = positions[oldIdx * 2]
                newPos[newIdx * 2 + 1] = positions[oldIdx * 2 + 1]
                newUvs[newIdx * 2] = uvs[oldIdx * 2]
                newUvs[newIdx * 2 + 1] = uvs[oldIdx * 2 + 1]
            }
            val newIndices = IntArray(island.triangles.size * 3)
            island.triangles.forEachIndexed { localTri, oldTri ->
                val srcBase = oldTri * 3
                val dstBase = localTri * 3
                newIndices[dstBase] = remap[indices[srcBase]]
                newIndices[dstBase + 1] = remap[indices[srcBase + 1]]
                newIndices[dstBase + 2] = remap[indices[srcBase + 2]]
            }
            val slicedOffsets = mesh.offsets?.let { grid ->
                KeyGrid(
                    axes = grid.axes,
                    cells = grid.cells.map { cell ->
                        KeyCell(cell.coordinate, MeshOffsets(sliceDeltas(cell.form.deltas.shared(), island.vertices)))
                    },
                )
            }
            val slicedShapes = mesh.shapes.map { binding ->
                binding.copy(shapes = binding.shapes.map { shape ->
                    shape?.copy(deltas = sliceDeltas(shape.deltas.shared(), island.vertices))
                })
            }
            val pieceId = validIds?.get(pieceIndex) ?: instance.outputId("split:${mesh.id}:$pieceIndex")
            val pieceName = validNames?.get(pieceIndex) ?: "${mesh.name} ${pieceIndex + 1}"
            island.vertices.forEachIndexed { newIdx, oldIdx ->
                vertexMap[oldIdx] = MeshSplitVertex(pieceId, newIdx)
            }
            mesh.copy(
                id = pieceId,
                name = pieceName,
                geometry = MeshGeometry(Floats.wrap(newPos), Floats.wrap(newUvs), Ints.wrap(newIndices)),
                offsets = slicedOffsets,
                shapes = slicedShapes,
            )
        }
        return MeshSplitResult(mesh, pieces, vertexMap.toList(), islands.map { it.vertices })
    }

    private fun sliceDeltas(deltas: FloatArray, vertices: IntArray): Floats {
        if (deltas.isEmpty()) return Floats.Empty
        val out = FloatArray(vertices.size * 2)
        vertices.forEachIndexed { newIdx, oldIdx ->
            val src = oldIdx * 2
            out[newIdx * 2] = deltas[src]
            out[newIdx * 2 + 1] = deltas[src + 1]
        }
        return Floats.wrap(out)
    }

}
