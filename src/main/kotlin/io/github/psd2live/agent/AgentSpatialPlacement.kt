package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import kotlin.math.abs

/** Canvas placement retained independently of however many pixels an Agent-generated PNG contains. */
data class AgentCanvasPlacement(
	val coordinateSpace: String,
	val canvasRect: Bounds,
	val imagePixelWidth: Int,
	val imagePixelHeight: Int,
	val canvasUnitsPerPixelX: Float,
	val canvasUnitsPerPixelY: Float,
	val sourceViewId: String,
)

/** Edge-based pixel rectangle inside the source View PNG. */
data class AgentPixelRect(
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
)

/**
 * Resolve a generated PNG back into the exact area represented by a View.
 *
 * Different pixel resolutions are valid; a different aspect ratio is not silently stretched. An import
 * tool may offer explicit crop/contain modes later, but its default full-image placement must use this
 * strict path.
 */
fun AgentViewSpatialMetadata.placementForGeneratedPng(
	sourceViewId: String,
	imagePixelWidth: Int,
	imagePixelHeight: Int,
	sourcePixelRect: AgentPixelRect? = null,
	aspectTolerance: Float = 0.005f,
): AgentCanvasPlacement {
	require(sourceViewId.isNotBlank()) { "sourceViewId must not be blank" }
	require(imagePixelWidth > 0 && imagePixelHeight > 0) { "Generated PNG dimensions must be positive" }
	require(aspectTolerance.isFinite() && aspectTolerance in 0f..0.05f) { "aspectTolerance must be between 0 and 0.05" }
	val placementRect = sourcePixelRect?.let { pixelRect ->
		require(pixelRect.width > 0 && pixelRect.height > 0) { "Source pixel rectangle dimensions must be positive" }
		require(pixelRect.left >= 0 && pixelRect.top >= 0) { "Source pixel rectangle must start inside the View PNG" }
		require(pixelRect.left + pixelRect.width <= pixelWidth && pixelRect.top + pixelRect.height <= pixelHeight) {
			"Source pixel rectangle must stay inside the View PNG"
		}
		Bounds(
			viewRect.left + pixelRect.left * canvasUnitsPerPixelX,
			viewRect.top + pixelRect.top * canvasUnitsPerPixelY,
			viewRect.left + (pixelRect.left + pixelRect.width) * canvasUnitsPerPixelX,
			viewRect.top + (pixelRect.top + pixelRect.height) * canvasUnitsPerPixelY,
		)
	} ?: viewRect
	val imageAspect = imagePixelWidth.toFloat() / imagePixelHeight.toFloat()
	val canvasAspect = placementRect.width / placementRect.height
	val relativeError = abs(imageAspect - canvasAspect) / canvasAspect.coerceAtLeast(1e-6f)
	require(relativeError <= aspectTolerance) {
		"Generated PNG aspect $imageAspect does not match source View aspect $canvasAspect; explicit crop or contain placement is required"
	}
	return AgentCanvasPlacement(
		coordinateSpace = coordinateSpace,
		canvasRect = placementRect,
		imagePixelWidth = imagePixelWidth,
		imagePixelHeight = imagePixelHeight,
		canvasUnitsPerPixelX = placementRect.width / imagePixelWidth.toFloat(),
		canvasUnitsPerPixelY = placementRect.height / imagePixelHeight.toFloat(),
		sourceViewId = sourceViewId,
	)
}
