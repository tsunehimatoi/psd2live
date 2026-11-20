package io.github.psd2live.core

import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The scale mesh lengths are measured at.
 *
 * Mesh settings, and every pixel tolerance inside the generator, are pixel lengths. Measured in source
 * pixels they ask a 6000-pixel document for nine times the vertices of a 2000-pixel one, and trace
 * contours nine times as long, for the same drawing.
 * In [MeshUnits.DOCUMENT] one mesh unit is one pixel of the document scaled to [REFERENCE_SIDE], and the
 * generator works on the layer's alpha reduced to that scale, so both the mesh and its cost stay the
 * same however large the source is.
 *
 * Under [MeshTrace.TEXTURE] the outline is traced finer than the mesh unit wherever the layer has the pixels
 * for it ([detail]): a texture denser than its canvas rectangle is read through its own pixels rather than its
 * averaged canvas view ([input]), so a stroke thinner than a canvas unit stays inside the mesh.
 */
object MeshResolution {
	/** The longer side, in pixels, of the document one mesh unit is a pixel of. */
	const val REFERENCE_SIDE = 2048

	/** Below this scale the reduction would save nothing and only blur the contour. */
	private const val MIN_REDUCTION = 1.05f

	/** The finest a contour is traced: working pixels per mesh unit. */
	const val MAX_DETAIL = 4f

	/** Working pixels a trace finer than the mesh unit may use; a larger layer is traced coarser, down to the mesh unit. */
	const val MAX_WORKING_PIXELS = 1 shl 20

	/** Source pixels per mesh unit for a [width] by [height] document; never below 1. */
	fun unitScale(units: MeshUnits, width: Int, height: Int): Float = when (units) {
		MeshUnits.PIXELS -> 1f
		MeshUnits.DOCUMENT -> {
			val scale = max(width, height).toFloat() / REFERENCE_SIDE
			if (scale.isFinite() && scale >= MIN_REDUCTION) scale else 1f
		}
	}

	fun unitScale(config: PipelineConfig, source: SourceArt): Float =
		unitScale(config.meshUnits, source.widthPx, source.heightPx)

	/**
	 * Working pixels per mesh unit to trace a [width] x [height] raster of [unitScale] pixels per mesh unit at:
	 * as fine as its pixels allow, at most [MAX_DETAIL], within [MAX_WORKING_PIXELS]; never below one.
	 */
	fun detail(width: Int, height: Int, unitScale: Float): Float {
		if (!(unitScale > 1f) || !unitScale.isFinite() || width <= 0 || height <= 0) return 1f
		val meshPixels = width / unitScale.toDouble() * (height / unitScale.toDouble())
		val budget = sqrt(MAX_WORKING_PIXELS / meshPixels)
		return min(min(MAX_DETAIL.toDouble(), unitScale.toDouble()), budget).toFloat().coerceAtLeast(1f)
	}

	/**
	 * The raster a layer is meshed from and how its pixels sit on the layer. The generator meshes [rgba] at
	 * [unitScale] of its pixels per mesh unit, traced at [detail]; [toLayer] maps the result's raster pixels to
	 * canvas units from the layer's integer bounds, the offsets every consumer of a layer mesh reads.
	 */
	internal class Input(
		val width: Int, val height: Int, val rgba: ByteArray, val unitScale: Float, val detail: Float,
		private val scaleX: Float = 1f, private val scaleY: Float = 1f, private val offsetX: Float = 0f, private val offsetY: Float = 0f,
		val coverAllAlpha: Boolean = false,
	) {
		/** Everything the mapped mesh depends on besides the mesh settings and alpha threshold. */
		val key: List<Any> get() = listOf(width, height, RasterDigest.of(rgba), unitScale, detail, scaleX, scaleY, offsetX, offsetY, coverAllAlpha)

		fun toLayer(result: AdaptiveMeshGenerator.Result): AdaptiveMeshGenerator.Result {
			if (scaleX == 1f && scaleY == 1f && offsetX == 0f && offsetY == 0f) return result
			val p = result.positions
			return result.copy(positions = FloatArray(p.size) { i -> if (i % 2 == 0) p[i] / scaleX + offsetX else p[i] / scaleY + offsetY })
		}
	}

	/**
	 * What [layer] - a canvas-resolution analysis layer ([CanvasDensity]) - is meshed from under [trace] at
	 * [unitScale] canvas units per mesh unit. [MeshTrace.CANVAS] meshes its canvas view at the mesh unit, as
	 * before the texture trace. [MeshTrace.TEXTURE] meshes the texture behind a dense layer at its own pixels
	 * when it holds at least one pixel per mesh unit and the same density on both axes, and otherwise the
	 * canvas view; either is traced at [detail]. The texture is read laid over the layer's integer bounds
	 * ([CanvasDensity.alignedRaster]), as a saved generation input holds it.
	 */
	internal fun input(layer: SourceLayer, trace: MeshTrace, unitScale: Float, coverAllAlpha: Boolean = false): Input {
		// A dense layer handed in without its canvas view still meshes in canvas units.
		val view = if (layer !is CanvasDensityLayer && CanvasDensity.dense(layer)) CanvasDensity.canvasLayer(layer) else layer
		val canvas = view.raster
		if (trace == MeshTrace.CANVAS) return Input(canvas.width, canvas.height, canvas.rgba, unitScale, 1f, coverAllAlpha = coverAllAlpha)
		val texture = view.textureLayer
		fun viewInput() = Input(canvas.width, canvas.height, canvas.rgba, unitScale, detail(canvas.width, canvas.height, unitScale), coverAllAlpha = coverAllAlpha)
		if (texture === view) return viewInput()
		val bounds = view.bounds
		if (bounds.width <= 0 || bounds.height <= 0) return viewInput()
		val raster = CanvasDensity.alignedRaster(texture)
		val scaleX = raster.width / bounds.width.toFloat()
		val scaleY = raster.height / bounds.height.toFloat()
		val density = min(scaleX, scaleY)
		if (abs(scaleX - scaleY) > max(scaleX, scaleY) * 0.01f || density * unitScale < 1f) return viewInput()
		val rasterUnit = unitScale * density
		return Input(raster.width, raster.height, raster.rgba, rasterUnit, detail(raster.width, raster.height, rasterUnit), scaleX, scaleY, coverAllAlpha = coverAllAlpha)
	}

	/** The adaptive mesh of [input] in canvas units from the layer's integer bounds, through [cache] when there is one. */
	internal fun mesh(input: Input, alphaThreshold: Int, settings: MeshSettings, cache: PreviewMeshCache?): AdaptiveMeshGenerator.Result? {
		val result = if (cache != null) cache.generate(input.width, input.height, input.rgba, alphaThreshold, settings, input.unitScale, input.detail, input.coverAllAlpha)
			else AdaptiveMeshGenerator.generate(input.width, input.height, input.rgba, alphaThreshold, settings, input.unitScale, input.detail, input.coverAllAlpha)
		return result?.let(input::toLayer)
	}

	/** A layer's alpha reduced by [scale]: working pixel (x, y) covers source [x * scale, (x + 1) * scale). */
	internal class ReducedAlpha(val width: Int, val height: Int, val rgba: ByteArray, val scale: Double)

	/**
	 * Reduces [rgba] by [scale], keeping each working pixel's highest alpha so the reduced mask covers every
	 * pixel the source paints: a hairline stays a line and nothing opaque falls outside the mesh. Colour is
	 * dropped; the generator reads only alpha. Null when [scale] does not reduce.
	 */
	internal fun reduce(width: Int, height: Int, rgba: ByteArray, scale: Float): ReducedAlpha? {
		if (!scale.isFinite() || scale < MIN_REDUCTION || width <= 0 || height <= 0) return null
		val factor = scale.toDouble()
		val reducedWidth = max(1, ceil(width / factor).toInt())
		val reducedHeight = max(1, ceil(height / factor).toInt())
		fun spans(working: Int, source: Int) = IntArray((working + 1) * 2).also { spans ->
			for (i in 0 until working) {
				val first = floor(i * factor).toInt().coerceIn(0, source - 1)
				val end = ceil((i + 1) * factor).toInt().coerceIn(first + 1, source)
				spans[i * 2] = first
				spans[i * 2 + 1] = end
			}
		}
		val columns = spans(reducedWidth, width)
		val rows = spans(reducedHeight, height)
		// Separable maximum: across each source row first, then down each working column.
		val horizontal = ByteArray(reducedWidth * height)
		for (y in 0 until height) {
			val row = y * width
			for (x in 0 until reducedWidth) {
				var peak = 0
				for (sx in columns[x * 2] until columns[x * 2 + 1]) {
					val alpha = rgba[(row + sx) * 4 + 3].toInt() and 0xff
					if (alpha > peak) { peak = alpha; if (peak == 255) break }
				}
				horizontal[y * reducedWidth + x] = peak.toByte()
			}
		}
		val reduced = ByteArray(reducedWidth * reducedHeight * 4)
		for (y in 0 until reducedHeight) {
			val first = rows[y * 2]
			val end = rows[y * 2 + 1]
			for (x in 0 until reducedWidth) {
				var peak = 0
				for (sy in first until end) {
					val alpha = horizontal[sy * reducedWidth + x].toInt() and 0xff
					if (alpha > peak) { peak = alpha; if (peak == 255) break }
				}
				reduced[(y * reducedWidth + x) * 4 + 3] = peak.toByte()
			}
		}
		return ReducedAlpha(reducedWidth, reducedHeight, reduced, factor)
	}
}
