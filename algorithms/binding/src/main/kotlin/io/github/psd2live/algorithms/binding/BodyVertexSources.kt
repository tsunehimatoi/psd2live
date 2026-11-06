package io.github.psd2live.algorithms.binding

import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Maps authored topology through the same texture-space observation used by the legacy binding merge. */
internal object BodyVertexSources {
    fun match(model: PuppetModel, original: Drawable, target: Drawable, checkpoint: () -> Unit): List<VertexSource> {
        val tile = model.atlas.tiles.single { it.id == target.atlasTileId }
        val reference = requireNotNull(tile.source) { "Body mesh migration requires source artwork" }
        val layer = model.sources.single { it.id == reference.sourceId }.layers.single { it.key == reference.layerKey }
        val placement = tile.placement?.takeIf { model.atlas.storedUvsAddressPages }
        val page = placement?.let { model.atlas.pages[it.pageIndex] }
        require(tile.width > 0 && tile.height > 0 && layer.width > 0 && layer.height > 0)
        val scaleX = tile.width.toFloat() / layer.width
        val scaleY = tile.height.toFloat() / layer.height
        fun canvas(mesh: DrawableMesh) = FloatArray(mesh.uvs.size).also { points ->
            for (i in points.indices step 2) {
                checkpoint()
                val u = mesh.uvs[i]
                val v = mesh.uvs[i + 1]
                val raster = when {
                    placement == null -> floatArrayOf(u * tile.width, v * tile.height)
                    placement.rotationDegrees == 0f -> floatArrayOf((u * page!!.width - placement.positionX) / placement.scaleX,
                        (v * page.height - placement.positionY) / placement.scaleY)
                    else -> requireNotNull(layerPixelOf(placement, u * page!!.width, v * page.height))
                }
                points[i] = layer.left + raster[0] / scaleX
                points[i + 1] = layer.top + raster[1] / scaleY
            }
        }
        val old = requireNotNull(original.mesh)
        val next = requireNotNull(target.mesh)
        val a = canvas(old)
        val b = canvas(next)
        if (a.contentEquals(b) && old.indices.contentEquals(next.indices))
            return List(old.vertexCount) { VertexSource.FromOld(it) }
        return List(next.vertexCount) { vertex ->
            checkpoint()
            val x = b[vertex * 2]
            val y = b[vertex * 2 + 1]
            val nearest = if (vertex < old.vertexCount && a[vertex * 2] == x && a[vertex * 2 + 1] == y) vertex
                else (0 until old.vertexCount).minBy { index ->
                    val dx = a[index * 2].toDouble() - x
                    val dy = a[index * 2 + 1].toDouble() - y
                    dx * dx + dy * dy
                }
            if (abs(a[nearest * 2] - x) <= 1e-6f && abs(a[nearest * 2 + 1] - y) <= 1e-6f) VertexSource.FromOld(nearest)
            else {
                val bound = BindingPaths.bind(a, old.indices, x, y)
                if (minOf(bound.wa, bound.wb, bound.wc) < -1e-4f) VertexSource.FromOld(nearest)
                else {
                    val wa = bound.wa.coerceAtLeast(0f)
                    val wb = bound.wb.coerceAtLeast(0f)
                    val wc = bound.wc.coerceAtLeast(0f)
                    val total = wa + wb + wc
                    VertexSource.BarycentricOf(bound.a, bound.b, bound.c, wa / total, wb / total, wc / total)
                }
            }
        }
    }
}
