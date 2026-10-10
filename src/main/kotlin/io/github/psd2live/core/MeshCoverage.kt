package io.github.psd2live.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How much of a layer's art its meshes show. Pixels outside every mesh of the layer never render - after painting
 * beyond the mesh, or replacing the image with a larger one - and only a mesh rebuild brings them in, so the editor
 * says how many there are instead of leaving them silently missing.
 */
internal object MeshCoverage {
    /** Cells per side the layer's raster is sampled at, at most. */
    private const val GRID = 384

    /**
     * The share (0..1) of [layerId]'s visible pixels that none of its meshes covers, judged on the pixels the meshes'
     * texture coordinates address; null when the layer has no meshes or no visible pixels.
     */
    fun uncovered(model: RigPreviewModel, layerId: String): Float? {
        val layer = model.analysis.source.layers.firstOrNull { it.id.raw == layerId } ?: return null
        val raster = layer.raster
        if (raster.width <= 0 || raster.height <= 0) return null
        val drawables = model.rig.puppet.drawables.filter { model.rig.layerIdByDrawableId[it.id.raw] == layerId && it.mesh != null }
        if (drawables.isEmpty()) return null
        val space = LayerSpace.of(layer)
        val step = max(1, ceil(max(raster.width, raster.height) / GRID.toDouble()).toInt())
        val columns = (raster.width + step - 1) / step; val rows = (raster.height + step - 1) / step
        val covered = BooleanArray(columns * rows)
        for (drawable in drawables) {
            val mesh = requireNotNull(drawable.mesh)
            val canvas = runCatching { RasterMeshJournal.TextureCoordinates(model.rig.puppet, drawable).toCanvas(mesh.uvs) }.getOrNull() ?: continue
            // Each triangle in raster cells.
            fun cx(i: Int) = space.canvasToRasterX(canvas[i * 2]) / step
            fun cy(i: Int) = space.canvasToRasterY(canvas[i * 2 + 1]) / step
            for (t in 0 until mesh.indices.size / 3) {
                val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
                val ax = cx(a); val ay = cy(a); val bx = cx(b); val by = cy(b); val qx = cx(c); val qy = cy(c)
                val area = (bx - ax) * (qy - ay) - (by - ay) * (qx - ax)
                if (area == 0f) continue
                val x0 = max(0, floor(min(ax, min(bx, qx))).toInt()); val x1 = min(columns - 1, ceil(max(ax, max(bx, qx))).toInt())
                val y0 = max(0, floor(min(ay, min(by, qy))).toInt()); val y1 = min(rows - 1, ceil(max(ay, max(by, qy))).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val px = x + 0.5f; val py = y + 0.5f
                    val w0 = ((bx - px) * (qy - py) - (by - py) * (qx - px)) / area
                    val w1 = ((qx - px) * (ay - py) - (qy - py) * (ax - px)) / area
                    val w2 = 1f - w0 - w1
                    // A cell the mesh edge crosses counts as covered: half a cell of slack.
                    val slack = 0.5f / max(1f, kotlin.math.abs(area))
                    if (w0 >= -slack && w1 >= -slack && w2 >= -slack) covered[y * columns + x] = true
                }
            }
        }
        val threshold = model.config.alphaThreshold
        var visible = 0; var outside = 0
        for (y in 0 until rows) for (x in 0 until columns) {
            val px = min(raster.width - 1, x * step + step / 2); val py = min(raster.height - 1, y * step + step / 2)
            if ((raster.rgba[(py * raster.width + px) * 4 + 3].toInt() and 255) <= threshold) continue
            visible++
            if (!covered[y * columns + x]) outside++
        }
        return if (visible == 0) null else outside.toFloat() / visible
    }
}
