package io.github.psd2live.ui

import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.RigPreviewModel
import org.jetbrains.skia.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId

/** Draw the editing texture channel on Compose's Skia canvas, without per-triangle Java2D clips. */
internal class SkiaRigPainter(atlas: PackedAtlas) : AutoCloseable {
    private val images = atlas.pages.map { Image.makeFromEncoded(it.png) }
    private val shaders = images.map {
        it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
    }

    fun paint(
        canvas: Canvas,
        model: RigPreviewModel,
        geometry: DeformedGeometry,
        viewport: CanvasViewport,
        alpha: Float = 1f,
        visibleLayerIds: Set<String>? = null,
        drawOrderOverrides: Map<String, Float> = emptyMap(),
        dimUnselected: Boolean = false,
        highlightedLayerIds: Set<String>? = null,
        dimmedAlphaMultiplier: Float = 0.22f,
    ) {
        val drawables = model.rig.puppet.drawables.filter { it.mesh != null && it.id in geometry.worldPositions }
            .sortedBy {
                drawOrderOverrides[model.rig.layerIdByDrawableId[it.id.raw]]
                    ?: drawOrderOverrides[it.id.raw] ?: geometry.drawOrder[it.id] ?: it.drawOrder
            }
        val byId = model.rig.puppet.drawables.associateBy { it.id }
        val masks = mutableMapOf<List<DrawableId>, Path?>()
        Paint().use { paint ->
            try {
                for (drawable in drawables) {
                    val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
                    if (visibleLayerIds != null && layerId !in visibleLayerIds) continue
                    val highlighted = highlightedLayerIds == null || layerId in highlightedLayerIds || drawable.id.raw in highlightedLayerIds
                    val dim = if (dimUnselected && !highlighted) dimmedAlphaMultiplier else 1f
                    val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * alpha * dim).coerceIn(0f, 1f)
                    if (opacity <= 0.001f) continue
                    val mesh = drawable.mesh ?: continue
                    val world = geometry.worldPositions[drawable.id] ?: continue
                    val page = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
                    val image = images.getOrNull(page) ?: continue
                    val positions = FloatArray(mesh.indices.size * 2)
                    val uvs = FloatArray(positions.size)
                    // Expanded vertices avoid the unsigned-short index limit for large authored meshes.
                    mesh.indices.forEachIndexed { index, vertex ->
                        positions[index * 2] = viewport.x(world[vertex * 2]).toFloat()
                        positions[index * 2 + 1] = viewport.yFromWorld(world[vertex * 2 + 1]).toFloat()
                        uvs[index * 2] = mesh.uvs[vertex * 2] * image.width
                        uvs[index * 2 + 1] = mesh.uvs[vertex * 2 + 1] * image.height
                    }
                    val mask = if (drawable.maskedBy.isNotEmpty() && !drawable.invertMask) {
                        masks.getOrPut(drawable.maskedBy) {
                            PathBuilder().use { path ->
                                var triangles = 0
                                for (id in drawable.maskedBy) {
                                    val source = byId[id] ?: continue
                                    if (!source.isVisible) continue
                                    val maskMesh = source.mesh ?: continue
                                    val points = geometry.worldPositions[id] ?: continue
                                    for (i in maskMesh.indices.indices step 3) {
                                        val a = maskMesh.indices[i] * 2
                                        var b = maskMesh.indices[i + 1] * 2
                                        var c = maskMesh.indices[i + 2] * 2
                                        val cross = (points[b] - points[a]) * (points[c + 1] - points[a + 1]) -
                                            (points[b + 1] - points[a + 1]) * (points[c] - points[a])
                                        if (cross == 0f) continue
                                        if (cross < 0f) { val swap = b; b = c; c = swap }
                                        path.moveTo(viewport.x(points[a]).toFloat(), viewport.yFromWorld(points[a + 1]).toFloat())
                                        path.lineTo(viewport.x(points[b]).toFloat(), viewport.yFromWorld(points[b + 1]).toFloat())
                                        path.lineTo(viewport.x(points[c]).toFloat(), viewport.yFromWorld(points[c + 1]).toFloat())
                                        path.closePath()
                                        triangles++
                                    }
                                }
                                if (triangles == 0) null else path.detach()
                            }
                        }
                    } else null
                    val saved = canvas.save()
                    try {
                        mask?.let { canvas.clipPath(it, false) }
                        paint.shader = shaders[page]
                        paint.setAlphaf(opacity)
                        canvas.drawVertices(VertexMode.TRIANGLES, positions, null, uvs, null, BlendMode.MODULATE, paint)
                    } finally { canvas.restoreToCount(saved) }
                }
            } finally { masks.values.forEach { it?.close() } }
        }
    }

    override fun close() {
        shaders.forEach { it.close() }
        images.forEach { it.close() }
    }
}
