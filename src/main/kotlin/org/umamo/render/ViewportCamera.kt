package org.umamo.render

import kotlin.math.roundToInt

/**
 * The visible content extent in world space (the renderer's post-deform, Y-up pixel space). It frames
 * the model for an initial fit; width and height are clamped by the producer to at least 1 so a
 * degenerate model never divides by zero downstream.
 *
 * 内容の存在範囲（ワールド空間、ピクセル単位）。モデルの初期フレーミングに使う。
 *
 * @property Float minX   Left edge in world units.
 * @property Float minY   Bottom edge in world units.
 * @property Float width  Extent along X in world units.
 * @property Float height Extent along Y in world units.
 */
data class ContentBounds(val minX: Float, val minY: Float, val width: Float, val height: Float)

/**
 * A 2D viewport camera over the puppet's world space: an orthographic pan/zoom, no rotation. The model
 * renders 1:1 (one world unit = one model pixel = one screen pixel) at [zoom] == 1. Deformation never
 * reframes the view - the camera is set once (fit on open) and thereafter moved only by the user.
 *
 * Every edit helper is PURE - it returns a new camera and touches no GL - so the navigation maths
 * unit-tests without a context, the same discipline as the workspace splitter's pure ratio function.
 * The render backend consumes only [worldToNdc].
 *
 * Coordinate conventions: screen pixels measure +x right, +y DOWN from the top-left; world space is
 * +y UP (the deform shader flips Cubism's native +y-down before projection). The screen-to-world maths
 * below therefore carries the Y sign explicitly.
 *
 * 2D ビューポートカメラ（平行投影のパン／ズーム、回転なし）。zoom==1 で 1 ワールド単位＝1 画面ピクセル。
 * 編集ヘルパは全て純粋関数（新しいカメラを返し GL に触れない）なので、操作の数学を文脈なしでテストできる。
 *
 * @property Float centerX World X shown at the viewport center.
 * @property Float centerY World Y shown at the viewport center.
 * @property Float zoom    Screen pixels per world unit (1 == true 1:1).
 */
data class ViewportCamera(val centerX: Float, val centerY: Float, val zoom: Float) {
	/**
	 * Builds the affine world-to-NDC parameters the vertex shader applies as
	 * `ndc = world * (scaleX, scaleY) + (transformX, transformY)`. A world point at the camera center
	 * maps to NDC origin (the viewport center); a point one unit to the right maps [zoom] pixels right.
	 *
	 * @param Int viewportWidth  Target width in pixels.
	 * @param Int viewportHeight Target height in pixels.
	 * @return FloatArray [scaleX, scaleY, transformX, transformY].
	 */
	fun worldToNdc(viewportWidth: Int, viewportHeight: Int): FloatArray {
		val scaleX = 2f * zoom / viewportWidth
		val scaleY = 2f * zoom / viewportHeight
		return floatArrayOf(scaleX, scaleY, -centerX * scaleX, -centerY * scaleY)
	}

	/**
	 * Pans by a screen-pixel drag delta in grab style - the world point under the pointer stays under
	 * the pointer. Y carries the screen-down vs world-up sign flip.
	 *
	 * @param Float deltaXpx Horizontal drag in pixels (+ right).
	 * @param Float deltaYpx Vertical drag in pixels (+ down).
	 * @return ViewportCamera The panned camera.
	 */
	fun panByScreen(deltaXpx: Float, deltaYpx: Float): ViewportCamera =
		copy(centerX = centerX - deltaXpx / zoom, centerY = centerY + deltaYpx / zoom)

	/**
	 * Cursor-anchored zoom by [deltaPercent] percentage points (the wheel zoom): the world point under
	 * the cursor stays pinned to that same pixel, and the result snaps to the [stepPercent] grid so clean
	 * values (… 100%, 50%, 25% …) are reachable instead of drifting off the fit-derived ratio. The recenter
	 * uses the snapped, clamped zoom so the pin holds exactly even at the limits.
	 *
	 * @param Float deltaPercent   Percentage points to add (negative zooms out).
	 * @param Float stepPercent    The grid the result snaps to (e.g. 1 or 5).
	 * @param Float cursorXpx      Cursor X in pixels from the left.
	 * @param Float cursorYpx      Cursor Y in pixels from the top.
	 * @param Int   viewportWidth  Viewport width in pixels.
	 * @param Int   viewportHeight Viewport height in pixels.
	 * @return ViewportCamera The zoomed camera.
	 */
	fun zoomAtCursorByPercent(deltaPercent: Float, stepPercent: Float, cursorXpx: Float, cursorYpx: Float, viewportWidth: Int, viewportHeight: Int): ViewportCamera {
		val newZoom = snappedZoom(deltaPercent, stepPercent)
		val halfWidth = viewportWidth / 2f
		val halfHeight = viewportHeight / 2f
		val worldX = centerX + (cursorXpx - halfWidth) / zoom
		val worldY = centerY + (halfHeight - cursorYpx) / zoom
		return ViewportCamera(
			worldX - (cursorXpx - halfWidth) / newZoom,
			worldY - (halfHeight - cursorYpx) / newZoom,
			newZoom,
		)
	}

	/**
	 * Centerd zoom by [deltaPercent] percentage points (the keyboard zoom-in/out), snapping to the
	 * [stepPercent] grid and leaving the centerd world point fixed.
	 *
	 * @param Float deltaPercent Percentage points to add (negative zooms out).
	 * @param Float stepPercent  The grid the result snaps to.
	 * @return ViewportCamera The zoomed camera.
	 */
	fun zoomedByPercent(deltaPercent: Float, stepPercent: Float): ViewportCamera = copy(zoom = snappedZoom(deltaPercent, stepPercent))

	/**
	 * The zoom scale after adding [deltaPercent] points to the current zoom-percentage and snapping to the
	 * [stepPercent] grid, clamped to the limits. Snapping to the grid is what makes round percentages
	 * land exactly: from a 41.3% fit, +1%-steps pass through 42, 43, … 100, and +5%-steps through 45, 50, …
	 *
	 * @param Float deltaPercent Percentage points to add.
	 * @param Float stepPercent  The grid to snap to.
	 * @return Float The new zoom scale (screen px per world unit).
	 */
	private fun snappedZoom(deltaPercent: Float, stepPercent: Float): Float {
		val targetPercent = zoom * 100f + deltaPercent
		val snappedPercent = (targetPercent / stepPercent).roundToInt() * stepPercent
		return (snappedPercent / 100f).coerceIn(MIN_ZOOM, MAX_ZOOM)
	}

	/**
	 * Returns the camera at true 1:1 (zoom 1) about the current center - the "actual size" / 100% view.
	 *
	 * @return ViewportCamera The 1:1 camera.
	 */
	fun withActualSize(): ViewportCamera = copy(zoom = 1f)

	/**
	 * Frames a screen-pixel rectangle (Blender's Zoom Region / Shift+B): the dragged box fills the viewport,
	 * centerd on the box center.  The two corners may be given in any order.  The scale uses min() so the
	 * WHOLE box fits inside the viewport (letterboxed on the looser axis) rather than cropping it; a degenerate
	 * (zero-area) box is guarded by a one-pixel floor, and the zoom is clamped to the limits so a tiny box
	 * cannot explode past MAX_ZOOM.  Pure, so the framing maths unit-tests without a context.
	 *
	 * @param Float leftPx One horizontal box edge in screen pixels.
	 * @param Float topPx One vertical box edge in screen pixels.
	 * @param Float rightPx The other horizontal box edge in screen pixels.
	 * @param Float bottomPx The other vertical box edge in screen pixels.
	 * @param Int viewportWidth The viewport width in pixels.
	 * @param Int viewportHeight The viewport height in pixels.
	 * @return ViewportCamera The camera framing the box.
	 */
	fun framingScreenRect(leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float, viewportWidth: Int, viewportHeight: Int): ViewportCamera {
		val minX = minOf(leftPx, rightPx)
		val maxX = maxOf(leftPx, rightPx)
		val minY = minOf(topPx, bottomPx)
		val maxY = maxOf(topPx, bottomPx)
		val rectWidth = (maxX - minX).coerceAtLeast(1f)
		val rectHeight = (maxY - minY).coerceAtLeast(1f)
		// min() so the whole box fits inside the viewport (letterboxed on the looser axis), never cropped.
		val fitScale = minOf(viewportWidth / rectWidth, viewportHeight / rectHeight)
		val newZoom = (zoom * fitScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
		val rectCenterXpx = (minX + maxX) / 2f
		val rectCenterYpx = (minY + maxY) / 2f
		// Unproject the box center through the CURRENT camera (screen->world; Y flips), the inverse of the
		// same affine worldToNdc applies, so the box center lands at the viewport center under the new camera.
		val worldCenterX = centerX + (rectCenterXpx - viewportWidth / 2f) / zoom
		val worldCenterY = centerY + (viewportHeight / 2f - rectCenterYpx) / zoom
		return ViewportCamera(worldCenterX, worldCenterY, newZoom)
	}

	companion object {
		/** Hard zoom floor / ceiling, so a stray wheel spin can't lose or explode the model. */
		const val MIN_ZOOM: Float = 0.02f
		const val MAX_ZOOM: Float = 64f

		/** Fraction of the viewport a fit leaves the content occupying, so it isn't edge-to-edge. */
		const val FIT_MARGIN: Float = 0.9f

		/**
		 * Frames [content] centerd in the viewport with a small margin - the default view on open and the
		 * target of the Fit command.
		 *
		 * @param ContentBounds content        The extent to frame.
		 * @param Int           viewportWidth  Viewport width in pixels.
		 * @param Int           viewportHeight Viewport height in pixels.
		 * @return ViewportCamera The fitted camera.
		 */
		fun fit(content: ContentBounds, viewportWidth: Int, viewportHeight: Int): ViewportCamera {
			val zoom =
				(FIT_MARGIN * minOf(viewportWidth.toFloat() / content.width, viewportHeight.toFloat() / content.height))
					.coerceIn(MIN_ZOOM, MAX_ZOOM)
			return ViewportCamera(content.minX + content.width / 2f, content.minY + content.height / 2f, zoom)
		}
	}
}