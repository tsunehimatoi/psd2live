package org.umamo.render

import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import kotlin.math.max

/**
 * The viewport's offscreen surface pair: a supersampled draw target the renderer draws into, and a
 * display-size resolve target the draw is box-downscaled into for read-back.
 *
 * Backend-neutral - both targets and the resolve come from the [device], so every backend's viewport
 * host shares this class instead of re-owning two framebuffers apiece.  The draw target is write-only
 * (`sampled = false`, a renderbuffer on GL); the resolve target is sampled so it can be read back.
 *
 * Allocation is GROW-ONLY: the targets are kept at a per-axis high-water capacity and each render
 * uses only its bottom-left (render-origin) used region, resolved and read back at the used size.
 * A gutter drag resizes the viewport every frame, and one surface is shared by every area, so
 * differently-sized areas alternate sizes render-to-render - a recreate-on-any-change policy paid a
 * framebuffer destroy + create per frame in both cases.  The cost is that the session retains the
 * high-water allocation (a 2x draw target at 4K is about 130 MB).
 *
 * All calls run on the render thread with the device's context current; this class never crosses
 * threads.
 *
 * @property RenderDevice device The backend the targets live on.
 * @property Int          scale  The maximum framebuffer pixels per display pixel (the full
 *   supersample factor).  It bounds ensure's activeScale; capacity itself follows the high-water of
 *   the pixel sizes actually requested, so a session that never renders above 1x never pays a 2x
 *   allocation, and the first higher-scale render grows the targets once.
 *
 * @note Shrink-on-idle (trimming the high-water allocation after a quiet period) is the named
 *   future refinement if the retained memory ever matters in practice.
 */
class SupersampledSurface(
	private val device: RenderDevice,
	val scale: Int,
) {
	private var draw: RenderTarget? = null
	private var resolve: RenderTarget? = null
	private var drawCapacityWidth = 0
	private var drawCapacityHeight = 0
	private var resolveCapacityWidth = 0
	private var resolveCapacityHeight = 0

	/** The display-pixel width of the most recent [ensure] - the used extent of both targets. */
	var usedWidth: Int = 0
		private set

	/** The display-pixel height of the most recent [ensure] - the used extent of both targets. */
	var usedHeight: Int = 0
		private set

	/** The supersample factor of the most recent [ensure] (1 up to [scale]). */
	var usedScale: Int = scale
		private set

	/** The supersampled draw target (capacity-sized; render its used region). Call [ensure] first. */
	val drawTarget: RenderTarget
		get() = draw ?: error("ensure(width, height) before drawTarget")

	/** The resolve target the read-back reads (capacity-sized; used region only). Call [ensure] first. */
	val resolveTarget: RenderTarget
		get() = resolve ?: error("ensure(width, height) before resolveTarget")

	/**
	 * Ensures both targets can hold a [width] x [height] render (the draw target at [activeScale] x),
	 * growing a target only when a requested axis exceeds its capacity, and returns the draw target.
	 * Always records the used size and scale; a smaller or equal request never reallocates.
	 *
	 * @param Int width  The display width in pixels.
	 * @param Int height The display height in pixels.
	 * @param Int activeScale The supersample factor for this render (defaults to the full [scale]).
	 * @return RenderTarget The supersampled draw target.
	 */
	fun ensure(width: Int, height: Int, activeScale: Int = scale): RenderTarget {
		require(width > 0 && height > 0) { "surface size must be positive, got ${width}x$height" }
		require(activeScale in 1..scale) { "activeScale must be in 1..$scale, got $activeScale" }
		val drawWidth = width * activeScale
		val drawHeight = height * activeScale
		if (drawWidth > drawCapacityWidth || drawHeight > drawCapacityHeight) {
			draw?.let { existing -> device.destroyRenderTarget(existing) }
			drawCapacityWidth = max(drawWidth, drawCapacityWidth)
			drawCapacityHeight = max(drawHeight, drawCapacityHeight)
			draw =
				device.createRenderTarget(
					RenderTargetSpec(drawCapacityWidth, drawCapacityHeight, TextureFormat.Rgba8, sampled = false),
				)
		}
		if (width > resolveCapacityWidth || height > resolveCapacityHeight) {
			resolve?.let { existing -> device.destroyRenderTarget(existing) }
			resolveCapacityWidth = max(width, resolveCapacityWidth)
			resolveCapacityHeight = max(height, resolveCapacityHeight)
			resolve =
				device.createRenderTarget(
					RenderTargetSpec(resolveCapacityWidth, resolveCapacityHeight, TextureFormat.Rgba8, sampled = true),
				)
		}
		usedWidth = width
		usedHeight = height
		usedScale = activeScale
		return drawTarget
	}

	/** Box-downscales the draw target's used region into the resolve target's used region. */
	fun resolve() {
		device.resolveUsed(drawTarget, usedWidth * usedScale, usedHeight * usedScale, resolveTarget, usedWidth, usedHeight)
	}

	/** Frees both targets and resets the capacities. Safe to call before the first [ensure] and idempotent. */
	fun dispose() {
		draw?.let { device.destroyRenderTarget(it) }
		resolve?.let { device.destroyRenderTarget(it) }
		draw = null
		resolve = null
		drawCapacityWidth = 0
		drawCapacityHeight = 0
		resolveCapacityWidth = 0
		resolveCapacityHeight = 0
		usedWidth = 0
		usedHeight = 0
		usedScale = scale
	}
}