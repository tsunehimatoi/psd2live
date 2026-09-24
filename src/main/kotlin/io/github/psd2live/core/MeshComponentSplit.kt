package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceLayer
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.DrawableMesh
import java.util.UUID
import kotlin.math.roundToInt

/** Partitions source pixels by the connected triangle islands of the mesh currently on screen. */
internal object MeshComponentSplit {
    data class Component(val centerX: Float, val centerY: Float)

    class Plan(
        val components: List<Component>,
        private val ownerByPixel: IntArray,
        private val source: SourceLayer,
    ) {
        fun pieces(names: List<String>): List<WorkspaceSourceLayer> {
            require(names.size == components.size && names.all { it.isNotBlank() })
            val raster = source.raster
            val base = WorkspaceSourceLayer.copyOf(source, source.order) as WorkspaceSourceLayer
            val boxes = Array(components.size) { intArrayOf(raster.width, raster.height, -1, -1) }
            for (pixel in ownerByPixel.indices) {
                if ((raster.rgba[pixel * 4 + 3].toInt() and 0xff) == 0) continue
                val box = boxes[ownerByPixel[pixel]]
                val x = pixel % raster.width
                val y = pixel / raster.width
                if (x < box[0]) box[0] = x
                if (y < box[1]) box[1] = y
                if (x > box[2]) box[2] = x
                if (y > box[3]) box[3] = y
            }
            require(boxes.all { it[2] >= it[0] && it[3] >= it[1] }) { "Every mesh island needs source pixels" }
            val crop = source.bounds.width == raster.width && source.bounds.height == raster.height
            return boxes.mapIndexed { index, box ->
                val left = if (crop) box[0] else 0
                val top = if (crop) box[1] else 0
                val width = if (crop) box[2] - left + 1 else raster.width
                val height = if (crop) box[3] - top + 1 else raster.height
                val rgba = ByteArray(width * height * 4)
                for (y in top until top + height) for (x in left until left + width) {
                    val pixel = y * raster.width + x
                    if (ownerByPixel[pixel] != index) continue
                    raster.rgba.copyInto(rgba, ((y - top) * width + x - left) * 4, pixel * 4, pixel * 4 + 4)
                }
                base.copy(
                    id = LayerId("split:${UUID.randomUUID()}"),
                    name = names[index].trim(),
                    bounds = if (crop) LayerBounds(source.bounds.left + left, source.bounds.top + top, width, height) else source.bounds,
                    raster = LayerRaster(width, height, rgba),
                    sourceAssetId = null,
                    sourceSpatialReferenceId = null,
                    derived = true,
                )
            }
        }
    }

    fun detect(mesh: DrawableMesh, source: SourceLayer, placement: AtlasPlacement, pageWidth: Int, pageHeight: Int): Plan? {
        val width = source.raster.width
        val height = source.raster.height
        if (width <= 0 || height <= 0 || mesh.indices.size < 6 || mesh.uvs.size != mesh.positions.size) return null
        val parent = IntArray(mesh.vertexCount) { it }
        fun find(v: Int): Int {
            var root = v
            while (root != parent[root]) root = parent[root]
            var current = v
            while (current != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        val used = BooleanArray(mesh.vertexCount)
        for (i in mesh.indices.indices step 3) {
            val a = mesh.indices[i]
            val b = mesh.indices[i + 1]
            val c = mesh.indices[i + 2]
            if (a !in used.indices || b !in used.indices || c !in used.indices) return null
            used[a] = true; used[b] = true; used[c] = true
            union(a, b); union(b, c)
        }
        val groups = (used.indices).filter { used[it] }.groupBy(::find).values
        if (groups.size < 2) return null
        val scale = placement.scale.toFloat().coerceAtLeast(1f)
        fun localX(v: Int) = (mesh.uvs[v * 2] * pageWidth - placement.x) / scale
        fun localY(v: Int) = (mesh.uvs[v * 2 + 1] * pageHeight - placement.y) / scale
        val sorted = groups.sortedWith(compareBy<List<Int>> { group -> group.map(::localY).average() }
            .thenBy { group -> group.map(::localX).average() })
        val components = sorted.map { group ->
            Component(group.map(::localX).average().toFloat(), group.map(::localY).average().toFloat())
        }
        // Voronoi flood from the UV vertices assigns antialias fringes and tiny unmeshed islands
        // without dropping any nontransparent source pixel.
        val owners = IntArray(width * height) { -1 }
        val queue = IntArray(width * height)
        var tail = 0
        sorted.forEachIndexed { component, vertices ->
            for (vertex in vertices) {
                val x = localX(vertex).roundToInt().coerceIn(0, width - 1)
                val y = localY(vertex).roundToInt().coerceIn(0, height - 1)
                val pixel = y * width + x
                if (owners[pixel] == -1) {
                    owners[pixel] = component
                    queue[tail++] = pixel
                }
            }
        }
        var head = 0
        while (head < tail) {
            val pixel = queue[head++]
            val x = pixel % width
            val y = pixel / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (owners[next] == -1) {
                    owners[next] = owners[pixel]
                    queue[tail++] = next
                }
            }
        }
        val occupied = BooleanArray(sorted.size)
        for (pixel in owners.indices) {
            if ((source.raster.rgba[pixel * 4 + 3].toInt() and 0xff) != 0) occupied[owners[pixel]] = true
        }
        val retained = components.indices.filter { occupied[it] }
        if (retained.size < 2) return null
        if (retained.size == components.size) return Plan(components, owners, source)
        val remap = IntArray(components.size) { old ->
            retained.indexOf(old).takeIf { it >= 0 } ?: 0
        }
        for (pixel in owners.indices) owners[pixel] = remap[owners[pixel]]
        return Plan(retained.map(components::get), owners, source)
    }
}
