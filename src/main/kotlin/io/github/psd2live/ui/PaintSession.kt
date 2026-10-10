package io.github.psd2live.ui

import io.github.psd2live.ui.utils.toImageBitmapFast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.github.psd2live.application.WorkspacePaintSession
import io.github.psd2live.core.RasterPaintEngine
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.min

internal data class PaintStrokeRecord(val id: String, val name: String)

/** Compose projection of the application-owned raster draft and stroke history. */
class PaintSession(val handle: WorkspacePaintSession) {
    val layerId get() = handle.layerId
    val layerName get() = handle.layerName
    /** The session raster, in raster pixels: the canvas for a layer at one pixel per canvas unit. */
    val docWidth get() = handle.width
    val docHeight get() = handle.height
    /** The document canvas, in canvas units: where the pointer may paint and sample. */
    val canvasWidth get() = handle.canvasWidth
    val canvasHeight get() = handle.canvasHeight
    /** Canvas units of the raster's top-left corner, and raster pixels per canvas unit. */
    val originX get() = handle.originX
    val originY get() = handle.originY
    val scaleX get() = handle.scaleX
    val scaleY get() = handle.scaleY
    /**
     * Where the canvas shows the layer's own frame - the space the session paints in - after the layer was moved or
     * scaled as a whole ([io.github.psd2live.project.LayerTransform]); axis-aligned, identity for a layer never moved.
     */
    var frame: io.github.psd2live.project.LayerTransform = io.github.psd2live.project.LayerTransform.IDENTITY
    /** The session raster's rectangle on the canvas as shown, in canvas units. */
    val shownLeft get() = frame.x(originX, originY)
    val shownTop get() = frame.y(originX, originY)
    val shownWidth get() = docWidth / scaleX * frame.a
    val shownHeight get() = docHeight / scaleY * frame.d
    /** A canvas point in the layer's frame, where the session paints and samples. */
    fun toFrame(x: Float, y: Float): Pair<Float, Float> = frame.inverse().let { it.x(x, y) to it.y(x, y) }
    /** Detached observation; writes must use the shared session gestures. */
    val workingImage: BufferedImage get() = handle.image()
    var isDirty by mutableStateOf(false)
        private set
    internal val strokeRecords = mutableStateListOf<PaintStrokeRecord>()
    var currentStrokeIndex by mutableStateOf(0)
        private set
    val strokeCount get() = strokeRecords.size - 1
    var previewTiles by mutableStateOf<List<PreviewTile>>(emptyList())
        private set
    class PreviewTile(val x: Int, val y: Int, val width: Int, val height: Int, val image: ImageBitmap)

    /**
     * The canvas shows this session through the GPU renderer, which keeps the raster as one texture and is
     * handed only the rectangles that changed: no preview tiles are painted or converted then. Set by the canvas
     * that draws the session; switching it off repaints every tile for the software canvas.
     */
    var gpuPreview: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                markDirty(Rectangle(0, 0, docWidth, docHeight))
                refreshPreview()
            }
        }

    /** Bumped whenever the raster changed while [gpuPreview] is on, so a draw scope reading it redraws. */
    var gpuVersion by mutableStateOf(0)
        private set

    /** Changed areas the GPU texture has not been given yet. */
    private val previewLock = Any()
    private val gpuDirty = ArrayList<Rectangle>()

    /**
     * The changes since the last call, as one rectangle of premultiplied RGBA pixels copied off the raster here
     * through the shared session's locked raster snapshot, so GL never reads a half-written stroke. [full] asks for the
     * whole raster, for a texture that has just been made.
     */
    internal fun takeGpuUpload(full: Boolean): io.github.psd2live.render.PaintUpload? {
        // Release this lock before reading the raster: session observers hold the raster lock while publishing.
        val area = synchronized(previewLock) {
            val area = if (full) Rectangle(0, 0, docWidth, docHeight) else {
                if (gpuDirty.isEmpty()) return null
                gpuDirty.reduce { a, b -> a.union(b) }.intersection(Rectangle(0, 0, docWidth, docHeight))
            }
            gpuDirty.clear()
            area
        }
        if (area.isEmpty) return null
        val argb = handle.tile(area.x, area.y, area.width, area.height).getRGB(0, 0, area.width, area.height, null, 0, area.width)
        val bytes = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val c = argb[i]
            val a = c ushr 24
            if (a == 0) continue
            bytes[i * 4] = (((c ushr 16 and 0xff) * a + 127) / 255).toByte()
            bytes[i * 4 + 1] = (((c ushr 8 and 0xff) * a + 127) / 255).toByte()
            bytes[i * 4 + 2] = (((c and 0xff) * a + 127) / 255).toByte()
            bytes[i * 4 + 3] = a.toByte()
        }
        return io.github.psd2live.render.PaintUpload(area.x, area.y, area.width, area.height, bytes)
    }

    private val published = HashMap<Long, PreviewTile>()
    private val stale = HashMap<Long, Long>()
    private var dirtyVersion = 0L
    private val detach: () -> Unit
    init {
        detach = handle.observe { regions ->
            regions.forEach(::markDirty)
            sync()
            if (!handle.activeStroke) refreshPreview()
        }
        sync(); markDirty(Rectangle(0, 0, docWidth, docHeight)); refreshPreview()
    }
    private fun sync() {
        isDirty = handle.isDirty && !handle.finished
        currentStrokeIndex = handle.index
        val next = handle.strokes.map { PaintStrokeRecord(it.id,
            if (it.id == "init") tr("editor.paint.strokeInitial") else it.name) }
        if (next != strokeRecords.toList()) { strokeRecords.clear(); strokeRecords.addAll(next) }
    }
    private fun markDirty(rect: Rectangle) {
        val area = rect.intersection(Rectangle(0, 0, docWidth, docHeight))
        if (area.isEmpty) return
        synchronized(previewLock) {
            gpuDirty += Rectangle(area)
            val version = ++dirtyVersion
            for (ty in area.y / PREVIEW_TILE until (area.y + area.height + PREVIEW_TILE - 1) / PREVIEW_TILE)
                for (tx in area.x / PREVIEW_TILE until (area.x + area.width + PREVIEW_TILE - 1) / PREVIEW_TILE)
                    stale[(tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)] = version
        }
    }
    fun refreshPreview() {
        if (gpuPreview) {
            // The GPU canvas takes the changed areas themselves (see takeGpuUpload); the tiles wait until a
            // software canvas asks for them.
            synchronized(previewLock) { if (gpuDirty.isNotEmpty()) gpuVersion++ }
            return
        }
        val dirty = synchronized(previewLock) { stale.toMap() }
        if (dirty.isEmpty()) return
        for ((key, version) in dirty) {
            val x = (key ushr 32).toInt() * PREVIEW_TILE
            val y = (key and 0xFFFFFFFFL).toInt() * PREVIEW_TILE
            val width = min(PREVIEW_TILE, docWidth - x); val height = min(PREVIEW_TILE, docHeight - y)
            if (width > 0 && height > 0) {
                val tile = PreviewTile(x, y, width, height, handle.tile(x, y, width, height).toImageBitmapFast())
                synchronized(previewLock) {
                    if (stale[key] == version) { published[key] = tile; stale.remove(key) }
                }
            }
        }
        synchronized(previewLock) { previewTiles = published.values.toList() }
    }
    internal fun beginStroke() = handle.beginStroke()
    internal fun segment(x0: Float, y0: Float, x1: Float, y1: Float, tip: RasterPaintEngine.Tip,
        color: Color, opacity: Float, erase: Boolean) = handle.segment(x0, y0, x1, y1, tip, color.toArgb(), opacity, erase)
    internal fun abandonStroke() = handle.abandonStroke()
    fun recordStroke(name: String) = handle.recordStroke(name)
    fun canUndo() = handle.canUndo()
    fun canRedo() = handle.canRedo()
    fun undo() = handle.undo()
    fun redo() = handle.redo()
    fun jumpToStroke(index: Int) = handle.jump(index)
    fun discard() { handle.cancel(); detach() }
    fun dismiss() { handle.dismiss(); detach() }
    fun sample(x: Int, y: Int) = handle.sample(x, y)
    fun clear(name: String) = handle.gesture(buildJsonObject { put("mode", "clear") }, name)
    fun bucket(x: Int, y: Int, color: Color, tolerance: Int, name: String) =
        handle.gesture(buildJsonObject {
            put("mode", "bucket"); put("point", JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))
            put("tolerance", tolerance); put("color", rgba(color))
        }, name)
    fun shape(x0: Int, y0: Int, x1: Int, y1: Int, shape: PaintShape, color: Color, opacity: Float,
        strokeWidth: Float, filled: Boolean, name: String) = handle.gesture(buildJsonObject {
            put("mode", "shape"); put("from", JsonArray(listOf(JsonPrimitive(x0), JsonPrimitive(y0))))
            put("to", JsonArray(listOf(JsonPrimitive(x1), JsonPrimitive(y1)))); put("shape", shape.name.lowercase())
            put("color", rgba(color)); put("opacity", opacity); put("stroke_width", strokeWidth); put("filled", filled)
        }, name)
    private fun rgba(color: Color): JsonArray {
        val argb = color.toArgb()
        return JsonArray(listOf(argb ushr 16 and 255, argb ushr 8 and 255, argb and 255, argb ushr 24 and 255).map(::JsonPrimitive))
    }
    companion object { const val PREVIEW_TILE = 512 }
}
