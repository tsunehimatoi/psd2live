package io.github.psd2live.ui

import io.github.psd2live.core.RigCanvasSupport

import io.github.psd2live.core.CanvasViewport

import io.github.psd2live.ui.utils.toSkiaImage
import io.github.psd2live.core.PackedAtlas
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.textureLayer
import org.jetbrains.skia.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.eval.RenderPlanComposite
import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanNode
import io.github.psd2live.render.ArtworkDraw
import io.github.psd2live.render.ArtworkDrawList
import io.github.psd2live.render.ArtworkOptions
import io.github.psd2live.render.HOVER_TINT_STRENGTH
import org.umamo.render.glsl.skslCompositeBlender
import org.umamo.render.puppet.packedAlphaModeOf
import org.umamo.render.puppet.packedColorModeOf
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode as RigBlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DrawableId
import org.umamo.format.art.LayerRaster


/**
 * Draw the editing texture channel on Compose's Skia canvas. A masked part draws into a layer its masks' union
 * then trims with DST_IN, instead of clipping to a path of every mask triangle.
 * The pages are converted on the first paint: a canvas the GPU draws makes one per atlas and never paints with it.
 */
internal class SkiaRigPainter(private val atlas: PackedAtlas) : AutoCloseable {
    private class Pages(val images: List<Image>, val shaders: List<Shader>)
    private var pages: Pages? = null
    private fun pages(): Pages = pages ?: atlas.pages.map { it.image.toSkiaImage() }.let { images ->
        Pages(images, images.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null) })
    }.also { pages = it }

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
        tintLayerIds: Set<String>? = null,
        tintColor: Int = 0,
        tintAlpha: Float = HOVER_TINT_STRENGTH,
        sources: SourcePixelImages? = null,
    ) = paint(canvas, model, geometry, viewport, ArtworkDrawList.build(model, geometry, ArtworkOptions(
        alpha, visibleLayerIds, drawOrderOverrides, dimUnselected, highlightedLayerIds, dimmedAlphaMultiplier,
        tintLayerIds, tintColor, tintAlpha,
    )), sources, drawOrderOverrides)

    /**
     * Draws [draws], the list the GPU renderer draws too, so the two paths cannot disagree about what shows.
     * With [sources], each mesh samples its layer's own raster instead of the atlas tile (source pixels).
     */
    fun paint(canvas: Canvas, model: RigPreviewModel, geometry: DeformedGeometry, viewport: CanvasViewport, draws: List<ArtworkDraw>,
              sources: SourcePixelImages? = null, drawOrderOverrides: Map<String, Float> = emptyMap()) {
        val pages = pages()
        val images = pages.images
        val shaders = pages.shaders
        val byId = model.rig.puppet.drawables.associateBy { it.id }
        val maskPaint = Paint()
        val blendPaint = Paint()
        val coverPaint = Paint()
        /** View positions and texel coordinates of [id]'s triangles, expanded: no unsigned-short index limit. */
        fun vertices(id: DrawableId, page: Int): Pair<FloatArray, FloatArray>? {
            val mesh = byId[id]?.mesh ?: return null
            val world = geometry.worldPositions[id] ?: return null
            val image = images.getOrNull(page) ?: return null
            // Source pixels: page texel (u * W, v * H) lies at the raster pixel the tile's placement maps it to, through any turn.
            val source = sources?.forDrawable(id.raw, page)
            val positions = FloatArray(mesh.indices.size * 2)
            val uvs = FloatArray(positions.size)
            mesh.indices.forEachIndexed { index, vertex ->
                positions[index * 2] = viewport.x(world[vertex * 2]).toFloat()
                positions[index * 2 + 1] = viewport.yFromWorld(world[vertex * 2 + 1]).toFloat()
                val u = mesh.uvs[vertex * 2] * image.width
                val v = mesh.uvs[vertex * 2 + 1] * image.height
                if (source == null) {
                    uvs[index * 2] = u
                    uvs[index * 2 + 1] = v
                } else {
                    val raster = source.placement.toRaster(u, v)
                    uvs[index * 2] = raster[0]
                    uvs[index * 2 + 1] = raster[1]
                }
            }
            return positions to uvs
        }
        fun shaderFor(id: DrawableId, page: Int): Shader = sources?.forDrawable(id.raw, page)?.shader ?: shaders[page]
        fun pageOf(id: DrawableId): Int? = byId[id]?.let { model.rig.pageByDrawableId[id.raw] ?: it.texturePage }
        fun applyMask(ids: List<DrawableId>, pageOfMask: (Int, DrawableId) -> Int?, invert: Boolean) {
            if (ids.isEmpty()) return
            maskPaint.blendMode = if (invert) BlendMode.DST_OUT else BlendMode.DST_IN
            canvas.saveLayer(null, maskPaint)
            try {
                ids.forEachIndexed { index, id ->
                    val page = pageOfMask(index, id) ?: return@forEachIndexed
                    val (maskPositions, maskUvs) = vertices(id, page) ?: return@forEachIndexed
                    coverPaint.shader = shaderFor(id, page)
                    canvas.drawVertices(VertexMode.TRIANGLES, maskPositions, null, maskUvs, null, BlendMode.MODULATE, coverPaint)
                }
            } finally { canvas.restore() }
        }
        Paint().use { paint ->
            try {
                fun drawMesh(draw: ArtworkDraw) {
                    val (positions, uvs) = vertices(draw.drawableId, draw.page) ?: return
                    val masked = draw.maskIds.isNotEmpty()
                    val saved = canvas.save()
                    try {
                        // Any blend but normal over: the part (masked and washed) draws into a layer that the
                        // preview's composite then blends onto what is under it.
                        if (!(draw.blendMode == RigBlendMode.Normal && draw.fixedFunction)) {
                            blendPaint.blender = blender(packedColorModeOf(draw.blendMode), packedAlphaModeOf(draw.alphaBlend))
                            canvas.saveLayer(bounds(positions), blendPaint)
                        }
                        if (masked) {
                            // Draw the part into its own layer, then apply the masks' combined alpha coverage
                            // with DST_IN, or the inverse with DST_OUT.
                            canvas.saveLayer(bounds(positions), null)
                        }
                        paint.shader = shaderFor(draw.drawableId, draw.page)
                        val tint = if (draw.tinted) tintFilter(draw.multiply, draw.screen) else null
                        paint.colorFilter = tint
                        paint.setAlphaf(draw.opacity)
                        canvas.drawVertices(VertexMode.TRIANGLES, positions, null, uvs, null, BlendMode.MODULATE, paint)
                        paint.colorFilter = null
                        tint?.close()
                        // Hover annotation: wash the very triangles just drawn with the component colour
                        // instead of boxing them. Re-drawing the mesh keeps the tint on the artwork's own
                        // silhouette — a part lights up rather than growing a rectangle — and because it
                        // is masked with the part, a masked part is tinted only where it actually shows.
                        if (draw.tintColor != 0) {
                            paint.shader = null
                            paint.color = draw.tintColor
                            paint.setAlphaf(draw.tintAlpha)
                            canvas.drawVertices(VertexMode.TRIANGLES, positions, null, null, null, BlendMode.SRC_OVER, paint)
                        }
                        applyMask(draw.maskIds, { index, _ -> draw.maskPages.getOrElse(index) { draw.page } }, draw.invertMask)
                    } finally { canvas.restoreToCount(saved) }
                }
                val puppet = model.rig.puppet
                val order = if (drawOrderOverrides.isEmpty()) geometry.drawOrder else puppet.drawables.associate {
                    it.id to RigCanvasSupport.displayOrder(model, it, geometry, drawOrderOverrides)
                }
                val plan = RigCanvasSupport.groupedRenderPlan(puppet, geometry, order)
                if (plan == null) {
                    draws.forEach(::drawMesh)
                } else {
                    val drawsById = draws.associateBy { it.drawableId }
                    fun groupBounds(nodes: List<RenderPlanNode>): Rect? {
                        var region: Rect? = null
                        for (node in nodes) {
                            val next = when (node) {
                                is RenderPlanDrawable -> if (node.id in drawsById) {
                                    bounds(geometry.worldPositions.getValue(node.id), viewport)
                                } else null
                                is RenderPlanComposite -> groupBounds(node.children)
                            } ?: continue
                            val current = region
                            region = if (current == null) next else Rect.makeLTRB(
                                minOf(current.left, next.left), minOf(current.top, next.top),
                                maxOf(current.right, next.right), maxOf(current.bottom, next.bottom),
                            )
                        }
                        return region
                    }
                    fun drawPlan(nodes: List<RenderPlanNode>) {
                        for (node in nodes) when (node) {
                            is RenderPlanDrawable -> drawsById[node.id]?.let(::drawMesh)
                            is RenderPlanComposite -> {
                                val composite = node.composite
                                val state = geometry.partCompositeStates[node.partId]
                                val opacity = (state?.opacity ?: composite.opacity).coerceIn(0f, 1f)
                                if (opacity <= 0.001f) continue
                                val region = groupBounds(node.children) ?: continue
                                val multiply = state?.multiplyColor ?: composite.multiplyColor
                                val screen = state?.screenColor ?: composite.screenColor
                                val tint = if (multiply != ColorRgb.MultiplyIdentity || screen != ColorRgb.ScreenIdentity) {
                                    tintFilter(multiply, screen)
                                } else null
                                try {
                                    Paint().use { groupPaint ->
                                        groupPaint.colorFilter = tint
                                        groupPaint.setAlphaf(opacity)
                                        if (composite.blendMode != RigBlendMode.Normal || composite.alphaBlendMode != AlphaBlendMode.Over) {
                                            groupPaint.blender = blender(packedColorModeOf(composite.blendMode), packedAlphaModeOf(composite.alphaBlendMode))
                                        }
                                        canvas.saveLayer(region, groupPaint)
                                        try {
                                            drawPlan(node.children)
                                            applyMask(composite.maskedBy, { _, id ->
                                                pageOf(id).takeIf { byId[id]?.isVisible == true }
                                            }, composite.invertMask)
                                        } finally { canvas.restore() }
                                    }
                                } finally { tint?.close() }
                            }
                        }
                    }
                    drawPlan(plan)
                }
            } finally { maskPaint.close(); blendPaint.close(); coverPaint.close() }
        }
    }

    /**
     * The multiply then screen colours on the straight colour, as the preview's shader applies them:
     * c' = c·m·(1 − s) + s per channel, alpha untouched.
     */
    private fun tintFilter(m: ColorRgb, s: ColorRgb): ColorFilter {
        return ColorFilter.makeMatrix(ColorMatrix(
            m.red * (1f - s.red), 0f, 0f, 0f, s.red,
            0f, m.green * (1f - s.green), 0f, 0f, s.green,
            0f, 0f, m.blue * (1f - s.blue), 0f, s.blue,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    private val blenders = HashMap<Int, Blender>()

    /** The preview's composite for one pair of packed modes, as a Skia blender. */
    private fun blender(colorMode: Int, alphaMode: Int): Blender = blenders.getOrPut(colorMode * 8 + alphaMode) {
        val uniforms = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(colorMode).putInt(alphaMode).array()
        Data.makeFromBytes(uniforms).use { compositeEffect.makeBlender(it) }
    }

    private companion object {
        val compositeEffect: RuntimeEffect by lazy { RuntimeEffect.makeForBlender(skslCompositeBlender()) }
    }

    private fun bounds(positions: FloatArray, viewport: CanvasViewport? = null): Rect {
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (i in positions.indices step 2) {
            val x = viewport?.x(positions[i])?.toFloat() ?: positions[i]
            val y = viewport?.yFromWorld(positions[i + 1])?.toFloat() ?: positions[i + 1]
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        return if (left > right) Rect.makeWH(0f, 0f) else Rect.makeLTRB(left - 1f, top - 1f, right + 1f, bottom + 1f)
    }

    override fun close() {
        blenders.values.forEach { it.close() }
        blenders.clear()
        val pages = pages ?: return
        this.pages = null
        pages.shaders.forEach { it.close() }
        pages.images.forEach { it.close() }
    }
}

/**
 * Each textured layer's own raster as a Skia image, for edit canvases that show source pixels instead of the
 * atlas. A mesh maps to its layer's raster through the layer's atlas placement, so the UVs need no rebuild; a
 * mesh whose layer has no placement on the page it samples keeps the atlas. Images are made on first use.
 */
internal class SourcePixelImages(private val model: RigPreviewModel, sharedTextures: SourcePixelTextures? = null) : AutoCloseable {
    class Source(val placement: io.github.psd2live.core.AtlasPlacement, val shader: Shader)

    private val textures = sharedTextures ?: SourcePixelTextures()
    private val ownsTextures = sharedTextures == null
    private val rasters = model.analysis.layers.associate { it.source.id.raw to it.source.textureLayer.raster }
    private val byLayer = HashMap<String, Source?>()

    fun forDrawable(drawableId: String, page: Int): Source? {
        val layerId = model.rig.layerIdByDrawableId[drawableId] ?: return null
        val placement = model.atlas.placementByLayerId[layerId]?.takeIf { it.page == page && it.scaleX > 0f && it.scaleY > 0f } ?: return null
        return byLayer.getOrPut(layerId) {
            val raster = rasters[layerId]?.takeIf { it.width > 0 && it.height > 0 } ?: return@getOrPut null
            Source(placement, textures.shader(raster))
        }
    }

    override fun close() {
        byLayer.clear()
        if (ownsTextures) textures.close()
    }
}

/** Drawables from several live previews can share the same source rasters even with different UV placements. */
internal class SourcePixelTextures : AutoCloseable {
    private class Texture(val image: Image, val shader: Shader)
    private val textures = HashMap<LayerRaster, Texture>()

    fun shader(raster: LayerRaster): Shader = textures.getOrPut(raster) {
        val image = Image.makeRaster(ImageInfo(raster.width, raster.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
            raster.rgba, raster.width * 4)
        Texture(image, image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
    }.shader

    override fun close() {
        textures.values.forEach { source -> source.shader.close(); source.image.close() }
        textures.clear()
    }
}

/**
 * The camera translation a cached pass was drawn at, and everything else it depends on. While the user pans,
 * a pass whose [stableKey] still matches is drawn shifted instead of being drawn again.
 */
internal class PanShift(val stableKey: List<Any?>, val offsetX: Double, val offsetY: Double, val panning: Boolean)

/**
 * Keeps the unchanged texture pass for this one viewport.
 *
 * A new key is recorded as Skia draw commands and replayed while it keeps changing (a drag, a scrub).
 * Replaying is not free: every textured mesh is sampled from the atlas again and every masked part composites
 * a layer trimmed by its masks, on each frame the canvas draws, which includes every hover
 * and overlay change. So once the same key is drawn twice it is rasterized, and later frames draw one image.
 */
internal class CachedSkiaPicture : AutoCloseable {
    private var key: List<Any?>? = null
    private var picture: Picture? = null
    private var raster: Image? = null
    private var shift: PanShift? = null

    fun draw(
        canvas: Canvas, key: List<Any?>, width: Int, height: Int, pan: PanShift? = null, record: (Canvas) -> Unit,
    ) {
        val cached = shift
        if (pan != null && pan.panning && cached != null && (picture != null || raster != null) && cached.stableKey == pan.stableKey &&
            (cached.offsetX != pan.offsetX || cached.offsetY != pan.offsetY)) {
            val saved = canvas.save()
            try {
                canvas.translate((pan.offsetX - cached.offsetX).toFloat(), (pan.offsetY - cached.offsetY).toFloat())
                drawCached(canvas)
            } finally { canvas.restoreToCount(saved) }
            return
        }
        if (picture == null || this.key != key) {
            val next = PictureRecorder().use { recorder ->
                record(recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat())))
                recorder.finishRecordingAsPicture()
            }
            picture?.close()
            raster?.close()
            raster = null
            picture = next
            this.key = key
            shift = pan
        } else if (raster == null && width > 0 && height > 0) {
            raster = Surface.makeRasterN32Premul(width, height).use { surface ->
                picture?.let { surface.canvas.drawPicture(it) }
                surface.makeImageSnapshot()
            }
        }
        drawCached(canvas)
    }

    private fun drawCached(canvas: Canvas) {
        val image = raster
        if (image != null) canvas.drawImage(image, 0f, 0f) else picture?.let { canvas.drawPicture(it) }
    }

    override fun close() {
        picture?.close()
        picture = null
        raster?.close()
        raster = null
        key = null
        shift = null
    }
}
