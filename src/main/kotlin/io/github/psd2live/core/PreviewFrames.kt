package io.github.psd2live.core

import io.github.psd2live.render.WindowGpu
import org.umamo.runtime.model.ParameterId

/** What draws the preview canvas: the official Cubism runtime on the exported moc3, or PSD2Live's own runtime on the exported `.p2lrt`. */
enum class PreviewBackend { CUBISM, P2LRT }

/**
 * A frame the preview runtime evaluated and rendered, with the pose it rendered. The pixels stay on the GPU, in
 * the view's texture in its window's Skia context ([io.github.psd2live.render.GpuResources.frame]).
 */
data class PreviewFrame(
	val width: Int,
	val height: Int,
	val parameters: Map<ParameterId, Float>,
	val animationEnabled: Boolean = true,
	val viewId: String = "",
	val cameraScale: Float = 1f,
	val cameraOffsetX: Float = 0f,
	val cameraOffsetY: Float = 0f,
	val backend: PreviewBackend = PreviewBackend.CUBISM,
	/** Drawn with the runtime's advanced mode (skinning, exact links, live simulation). */
	val advanced: Boolean = false,
	/** Features carried by the compiled model (skin 1, exact links 2, simulation 4, collision 8). */
	val advancedAvailable: Int = 0,
	/** The exact loaded candidate and texture draw, shared with GpuFrame. */
	val presentation: io.github.psd2live.util.PresentationFrame? = null,
)

/** One frame a preview canvas asks for. Camera values are Cubism's: [scale] fits the canvas height, offsets in clip units. */
data class PreviewRenderRequest(
	val width: Int,
	val height: Int,
	val scale: Float,
	val offsetX: Float,
	val offsetY: Float,
	val deltaTime: Float,
	val pointerX: Float,
	val pointerY: Float,
	val animationEnabled: Boolean = true,
	/** Cubism advances its own motion, drag and physics; false renders [parameterOverrides] as the whole pose. */
	val nativeClock: Boolean = animationEnabled,
	val parameterOverrides: Map<ParameterId, Float>,
	val parameterDefinitions: List<org.umamo.runtime.model.Parameter> = emptyList(),
	val pointerTrackingEnabled: Boolean = pointerX != 0f || pointerY != 0f,
	val lockedParameters: Set<ParameterId> = emptySet(),
	val frameTimeNanos: Long = System.nanoTime(),
	val viewId: String = "",
	/** The window the view draws in; its Skia context holds the frame. */
	val gpu: WindowGpu? = null,
	/** The p2lrt runtime's advanced mode; Cubism ignores it. */
	val advanced: Boolean = false,
	/**
	 * The preview's physics switch. In p2lrt's advanced mode it controls live simulation independently of playback:
	 * on, simulation steps with the frame clock even while paused; off, the baked swing shows. Physics is
	 * already present in the workspace pose and is never evaluated a second time by the preview.
	 */
	val physics: Boolean = true,
)
