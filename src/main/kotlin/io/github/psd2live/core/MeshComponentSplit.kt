package io.github.psd2live.core

import io.github.psd2live.document.WorkspaceSourceLayer
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.DrawableMesh
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/** Partitions source pixels by the connected triangle islands of the mesh currently on screen. */
internal object MeshComponentSplit {
    data class Component(val centerX: Float, val centerY: Float)

    class Plan(
        val components: List<Component>,
        private val ownerByPixel: IntArray,
        private val source: SourceLayer,
        checkpoint: () -> Unit = {},
        val ownerByVertex: IntArray = IntArray(0),
    ) {
        /** Small transparent thumbnails, prepared with the split plan so the dialog stays responsive. */
        val previewImages: List<BufferedImage> = buildPreviewImages(checkpoint)

        private fun buildPreviewImages(checkpoint: () -> Unit): List<BufferedImage> {
            val raster = source.raster
            val boxes = Array(components.size) { intArrayOf(raster.width, raster.height, -1, -1) }
            for (pixel in ownerByPixel.indices) {
                if (pixel % 1024 == 0) checkpoint()
                if ((raster.rgba[pixel * 4 + 3].toInt() and 0xff) == 0) continue
                val box = boxes[ownerByPixel[pixel]]
                val x = pixel % raster.width
                val y = pixel / raster.width
                if (x < box[0]) box[0] = x
                if (y < box[1]) box[1] = y
                if (x > box[2]) box[2] = x
                if (y > box[3]) box[3] = y
            }
            val images = boxes.map { box ->
                require(box[2] >= box[0] && box[3] >= box[1]) { "Every mesh island needs source pixels" }
                val width = box[2] - box[0] + 1
                val height = box[3] - box[1] + 1
                val scale = minOf(1f, 96f / width, 64f / height)
                val previewWidth = (width * scale).roundToInt().coerceAtLeast(1)
                val previewHeight = (height * scale).roundToInt().coerceAtLeast(1)
                BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_ARGB)
            }
            for (pixel in ownerByPixel.indices) {
                if (pixel % 1024 == 0) checkpoint()
                val component = ownerByPixel[pixel]
                val box = boxes[component]
                val offset = pixel * 4
                val alpha = raster.rgba[offset + 3].toInt() and 0xff
                if (alpha == 0) continue
                val color = (alpha shl 24) or
                    ((raster.rgba[offset].toInt() and 0xff) shl 16) or
                    ((raster.rgba[offset + 1].toInt() and 0xff) shl 8) or
                    (raster.rgba[offset + 2].toInt() and 0xff)
                val width = box[2] - box[0] + 1
                val height = box[3] - box[1] + 1
                val preview = images[component]
                val x = ((pixel % raster.width - box[0]) * preview.width / width).coerceIn(0, preview.width - 1)
                val y = ((pixel / raster.width - box[1]) * preview.height / height).coerceIn(0, preview.height - 1)
                preview.setRGB(x, y, color)
            }
            return images
        }

        // A prepared plan must not retain the completed scan's coroutine or cancellation lifetime.
        fun pieces(names: List<String>, ids: List<String>? = null, checkpoint: () -> Unit = {}): List<WorkspaceSourceLayer> {
            require(names.size == components.size && names.all { it.isNotBlank() })
            require(ids == null || ids.size == names.size && ids.distinct().size == ids.size && ids.all { it.isNotBlank() })
            val raster = source.raster
            val base = WorkspaceSourceLayer.copyOf(source, source.order) as WorkspaceSourceLayer
            val boxes = Array(components.size) { intArrayOf(raster.width, raster.height, -1, -1) }
            for (pixel in ownerByPixel.indices) {
                if (pixel % 1024 == 0) checkpoint()
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
            // Each piece is cropped in the source's own raster grid, so a dense layer's pieces keep its density.
            val space = LayerSpace.of(source)
            return boxes.mapIndexed { index, box ->
                val left = box[0]
                val top = box[1]
                val width = box[2] - left + 1
                val height = box[3] - top + 1
                val rgba = ByteArray(width * height * 4)
                for (y in top until top + height) for (x in left until left + width) {
                    if (x == left) checkpoint()
                    val pixel = y * raster.width + x
                    if (ownerByPixel[pixel] != index) continue
                    raster.rgba.copyInto(rgba, ((y - top) * width + x - left) * 4, pixel * 4, pixel * 4 + 4)
                }
                val placed = PaintSpace.placedOn(space, left, top, width, height, LayerRaster(width, height, rgba))
                base.copy(
                    id = LayerId(ids?.get(index) ?: StableIds.stem("split:", source.id.raw, index, names[index])),
                    name = names[index].trim(),
                    bounds = placed.bounds,
                    rect = placed.rect,
                    raster = placed.raster,
                    sourceAssetId = null,
                    sourceSpatialReferenceId = null,
                    derived = true,
                )
            }
        }
    }

    fun detect(mesh: DrawableMesh, source: SourceLayer, placement: AtlasPlacement, pageWidth: Int, pageHeight: Int,
               checkpoint: () -> Unit = {}): Plan? {
        // Page uvs to raster pixels go through the placement alone; the canvas rectangle plays no part.
        val texture = LayerTexture.packed(LayerSpace.of(source), placement, pageWidth, pageHeight)
        val local = FloatArray(mesh.uvs.size)
        for (index in 0 until mesh.uvs.size - 1 step 2) {
            val raster = texture.rasterOfUv(mesh.uvs[index], mesh.uvs[index + 1])
            local[index] = raster[0]; local[index + 1] = raster[1]
        }
        return detect(mesh, source, local, checkpoint)
    }

    /** [canvas] texture coordinates (canvas units) of [mesh] on [source]'s raster, through its canvas rectangle. */
    fun detectCanvas(mesh: DrawableMesh, source: SourceLayer, canvas: FloatArray, checkpoint: () -> Unit = {}): Plan? {
        val space = LayerSpace.of(source)
        val local = FloatArray(canvas.size) { index ->
            if (index % 2 == 0) space.canvasToRasterX(canvas[index]) else space.canvasToRasterY(canvas[index])
        }
        return detect(mesh, source, local, checkpoint)
    }

    /** Actual source-raster coordinates also cover padded, rotated and scaled texture tiles. */
    fun detect(mesh: DrawableMesh, source: SourceLayer, local: FloatArray, checkpoint: () -> Unit = {}): Plan? {
        checkpoint()
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
            if (i % 1023 == 0) checkpoint()
            val a = mesh.indices[i]
            val b = mesh.indices[i + 1]
            val c = mesh.indices[i + 2]
            if (a !in used.indices || b !in used.indices || c !in used.indices) return null
            used[a] = true; used[b] = true; used[c] = true
            union(a, b); union(b, c)
        }
        val groups = (used.indices).filter { used[it] }.groupBy(::find).values
        if (groups.size < 2) return null
        require(local.size == mesh.positions.size && local.all(Float::isFinite))
        fun localX(v: Int) = local[v * 2]
        fun localY(v: Int) = local[v * 2 + 1]
        val sorted = groups.sortedWith(compareBy<List<Int>> { group -> group.map(::localY).average() }
            .thenBy { group -> group.map(::localX).average() })
        val components = sorted.map { group ->
            Component(group.map(::localX).average().toFloat(), group.map(::localY).average().toFloat())
        }
        fun finish(owners: IntArray): Plan? {
            val occupied = BooleanArray(sorted.size)
            for (pixel in owners.indices) {
                if (pixel % 1024 == 0) checkpoint()
                if ((source.raster.rgba[pixel * 4 + 3].toInt() and 0xff) != 0) occupied[owners[pixel]] = true
            }
            val retained = components.indices.filter { occupied[it] }
            if (retained.size < 2) return null
            val remap = IntArray(components.size)
            retained.forEachIndexed { index, old -> remap[old] = index }
            val vertexOwners = IntArray(mesh.vertexCount) { -1 }
            sorted.forEachIndexed { index, vertices -> vertices.forEach { vertexOwners[it] = remap[index] } }
            for (vertex in vertexOwners.indices) if (vertexOwners[vertex] < 0) {
                checkpoint()
                vertexOwners[vertex] = retained.indices.minBy { index ->
                    val center = components[retained[index]]
                    val dx = localX(vertex) - center.centerX; val dy = localY(vertex) - center.centerY
                    dx * dx + dy * dy
                }
            }
            for (pixel in owners.indices) {
                if (pixel % 1024 == 0) checkpoint()
                owners[pixel] = remap[owners[pixel]]
            }
            return Plan(retained.map(components::get), owners, source, checkpoint, vertexOwners)
        }

        axisSeparatedOwners(sorted, width, height, ::localX, ::localY, checkpoint)?.let { return finish(it) }

        // The mesh triangles, rather than their vertices, establish ownership of the art.
        // A vertex Voronoi flood through transparent space can reach another island first.
        fun alpha(pixel: Int) = source.raster.rgba[pixel * 4 + 3].toInt() and 0xff
        val vertexComponent = IntArray(mesh.vertexCount) { -1 }
        sorted.forEachIndexed { component, vertices -> vertices.forEach { vertexComponent[it] = component } }
        val owners = IntArray(width * height) { -1 }
        fun edge(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
            (bx - ax) * (py - ay) - (by - ay) * (px - ax)
        for (triangle in mesh.indices.indices step 3) {
            checkpoint()
            val a = mesh.indices[triangle]
            val b = mesh.indices[triangle + 1]
            val c = mesh.indices[triangle + 2]
            val component = vertexComponent[a]
            val ax = localX(a); val ay = localY(a)
            val bx = localX(b); val by = localY(b)
            val cx = localX(c); val cy = localY(c)
            val area = edge(ax, ay, bx, by, cx, cy)
            if (kotlin.math.abs(area) < 0.001f) continue
            val left = kotlin.math.floor(minOf(ax, bx, cx).toDouble()).toInt().coerceIn(0, width - 1)
            val right = kotlin.math.ceil(maxOf(ax, bx, cx).toDouble()).toInt().coerceIn(0, width - 1)
            val top = kotlin.math.floor(minOf(ay, by, cy).toDouble()).toInt().coerceIn(0, height - 1)
            val bottom = kotlin.math.ceil(maxOf(ay, by, cy).toDouble()).toInt().coerceIn(0, height - 1)
            for (y in top..bottom) for (x in left..right) {
                if (x == left) checkpoint()
                val pixel = y * width + x
                if (alpha(pixel) < 8) continue
                val px = x + 0.5f; val py = y + 0.5f
                val ab = edge(ax, ay, bx, by, px, py)
                val bc = edge(bx, by, cx, cy, px, py)
                val ca = edge(cx, cy, ax, ay, px, py)
                if ((area > 0f && ab >= -0.001f && bc >= -0.001f && ca >= -0.001f) ||
                    (area < 0f && ab <= 0.001f && bc <= 0.001f && ca <= 0.001f)) {
                    owners[pixel] = component
                }
            }
        }

        // A vertex flood is used only to seed tiny unmeshed alpha islands.
        val vertexOwners = IntArray(width * height) { -1 }
        sorted.forEachIndexed { component, vertices ->
            for (vertex in vertices) {
                val x = localX(vertex).roundToInt().coerceIn(0, width - 1)
                val y = localY(vertex).roundToInt().coerceIn(0, height - 1)
                val pixel = y * width + x
                if (vertexOwners[pixel] == -1) vertexOwners[pixel] = component
            }
        }
        floodOwners(vertexOwners, width, height, checkpoint)
        val hasTriangleSeed = BooleanArray(sorted.size)
        for (pixel in owners.indices) {
            if (pixel % 1024 == 0) checkpoint()
            val owner = owners[pixel]
            if (owner >= 0) hasTriangleSeed[owner] = true
        }
        for (pixel in owners.indices) {
            if (pixel % 1024 == 0) checkpoint()
            val component = vertexOwners[pixel]
            if (!hasTriangleSeed[component] && owners[pixel] == -1 && alpha(pixel) > 0) {
                owners[pixel] = component
                hasTriangleSeed[component] = true
            }
        }
        floodOwners(owners, width, height, checkpoint)
        for (pixel in owners.indices) {
            if (pixel % 1024 == 0) checkpoint()
            if (owners[pixel] == -1) owners[pixel] = vertexOwners[pixel]
        }
        return finish(owners)
    }

    /** Use a 1D separator when projected mesh islands do not overlap. */
    private fun axisSeparatedOwners(
        groups: List<List<Int>>, width: Int, height: Int,
        localX: (Int) -> Float, localY: (Int) -> Float,
        checkpoint: () -> Unit,
    ): IntArray? {
        for (horizontal in listOf(true, false)) {
            val spans = groups.mapIndexed { index, vertices ->
                val values = vertices.map { if (horizontal) localX(it) else localY(it) }
                Triple(index, values.min(), values.max())
            }.sortedBy { it.second }
            if (spans.zipWithNext().any { (left, right) -> left.third >= right.second }) continue
            // The visible contour can extend one raster cell beyond its sampled mesh edge.
            val separators = spans.zipWithNext().map { (left, right) -> (left.third + right.second) * 0.5f + 1f }
            val owners = IntArray(width * height) { pixel ->
                if (pixel % 1024 == 0) checkpoint()
                val coordinate = if (horizontal) (pixel % width) + 0.5f else (pixel / width) + 0.5f
                // Upper bound keeps boundary pixels in the same island, without searching every island
                // for every pixel when a layer contains many disconnected details.
                var low = 0
                var high = separators.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (coordinate < separators[middle]) high = middle else low = middle + 1
                }
                spans[low].first
            }
            return owners
        }
        return null
    }

    /** Fill unassigned cells from the complete seed footprints in Chebyshev distance order. */
    private fun floodOwners(owners: IntArray, width: Int, height: Int, checkpoint: () -> Unit) {
        val queue = IntArray(width * height)
        var tail = 0
        for (pixel in owners.indices) {
            if (pixel % 1024 == 0) checkpoint()
            if (owners[pixel] >= 0) queue[tail++] = pixel
        }
        var head = 0
        while (head < tail) {
            if (head % 1024 == 0) checkpoint()
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
    }
}
