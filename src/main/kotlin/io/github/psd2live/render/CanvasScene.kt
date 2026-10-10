package io.github.psd2live.render

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.CanvasViewport
import org.jetbrains.skia.Bitmap
import org.umamo.render.eval.DeformedGeometry

/** One frame for the GPU renderer to draw: its size in device pixels and the camera it is drawn with. */
internal sealed interface GpuScene {
	val width: Int
	val height: Int
	val viewport: CanvasViewport

	/** This scene carrying what [earlier], a scene it replaces before the GL thread drew it, still had to upload. */
	fun after(earlier: GpuScene): GpuScene = this
}

/**
 * Everything one frame of a canvas shows, as an immutable snapshot the UI hands to the render thread.
 *
 * It holds references only: the model, the geometry the UI already evaluated and the draw list. The render
 * thread re-uploads a mesh only when one of its arrays is a different instance from the last frame's, which
 * the copy-on-write edits guarantee for exactly the meshes an edit touched.
 *
 * @property width    Target width in device pixels.
 * @property height   Target height in device pixels.
 * @property viewport The camera, in the same device pixels.
 */
internal class CanvasScene(
	override val width: Int,
	override val height: Int,
	override val viewport: CanvasViewport,
	val model: RigPreviewModel,
	val geometry: DeformedGeometry,
	val draws: List<ArtworkDraw>,
	val overlay: OverlayScene = OverlayScene.EMPTY,
	val paint: PaintScene? = null,
) : GpuScene {
	/** This scene carrying the uploads of [earlier], a scene it replaces before the GL thread drew it. */
	override fun after(earlier: GpuScene): GpuScene {
		if (earlier !is CanvasScene) return this
		val merged = paint?.after(earlier.paint) ?: return this
		return if (merged === paint) this else CanvasScene(width, height, viewport, model, geometry, draws, overlay, merged)
	}
}

/**
 * A texture atlas page: its tiles as textured rectangles and its guides, all in [overlay] in paint order, in world
 * units where page pixel (x, y) is world (x, -y), as the paint session's document is.
 */
internal class AtlasScene(
	override val width: Int,
	override val height: Int,
	override val viewport: CanvasViewport,
	val overlay: OverlayScene,
) : GpuScene

/** Premultiplied RGBA pixels for the [width] x [height] area at ([x], [y]) of a paint session's raster. */
internal class PaintUpload(val x: Int, val y: Int, val width: Int, val height: Int, val rgba: ByteArray)

/**
 * The paint session's raster, drawn over the frame as one texture at its place on the document: [width] x [height]
 * raster pixels stretched over the canvas quad [corners] - top-left, top-right, bottom-left, bottom-right, as x, y
 * pairs in canvas units - which is the whole canvas, pixel for pixel, for a layer at one pixel per canvas unit, and
 * turns with a layer that was rotated.
 *
 * @property session  The session the texture belongs to; another session starts a new texture.
 * @property uploads  The areas to write into the texture before drawing, oldest first.
 */
internal class PaintScene(val session: Any, val width: Int, val height: Int, val uploads: List<PaintUpload>,
	val corners: FloatArray = floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat(), width.toFloat(), height.toFloat())) {
	/** This scene with [earlier]'s uploads first, when it is the same session; for a scene that replaces an undrawn one. */
	fun after(earlier: PaintScene?): PaintScene =
		if (earlier == null || earlier.session !== session || earlier.uploads.isEmpty()) this
		else PaintScene(session, width, height, earlier.uploads + uploads, corners)
}
