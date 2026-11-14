package io.github.psd2live.render

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.SkikoRenderDelegate
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A view the window draws itself under its Compose picture, every frame: a playing preview. Its canvas leaves a
 * transparent hole in the picture where it sits, and [paint] fills it with the view's latest frame. [left] and
 * [top] are in the Compose scene's pixels, [width] × [height] the hole.
 */
class CompositedView(
	val left: Float,
	val top: Float,
	val width: Float,
	val height: Float,
	/** Views paint from the lowest [order] up: an edit canvas's overlay over its artwork. */
	val order: Int = 0,
	/**
	 * Views of one [layer] (the edit canvas's artwork and overlay) paint together into a texture multisampled like
	 * the window's picture, drawn again only when one of them is [stale]: their shapes antialias as they would in the
	 * picture, an overlay blends with the artwork under it, and an unchanged frame is one texture. Null paints onto the
	 * window every frame.
	 */
	val layer: String? = null,
	val stale: () -> Boolean = { true },
	/** Draws the view at the origin on [canvas] with [frame], the view's latest texture (null before its first). */
	val paint: (canvas: Canvas, frame: GpuFrame?) -> Unit,
)

/**
 * Draws a window from its Compose picture cached on the GPU and the [CompositedView]s under it.
 *
 * Compose Desktop redraws the whole window for any change, and replaying every panel's drawing costs most of a
 * frame. A preview playing at the display's rate (240 Hz and more) would make the window pay that every refresh
 * for a change in one canvas. Here Compose still runs every frame (recomposition, layout, recording: cheap when
 * little changed), but its recording is replayed into [surface] only when something in it asked to be drawn or laid
 * out again: every such request passes through the scene's `SnapshotInvalidationTracker`, whose callback this
 * counts. A frame where only previews moved is the cached picture over their textures.
 *
 * Compose's internals are reached by reflection; when that fails, or the window's context is not the current one,
 * the window draws as it always did ([active] is false and canvases draw their frames themselves).
 */
internal class WindowCompositor(private val gpu: WindowGpu, private val inner: SkikoRenderDelegate) {
	private val views = ConcurrentHashMap<String, CompositedView>()
	@Volatile private var painted: List<Map.Entry<String, CompositedView>> = emptyList()
	private val invalidated = AtomicBoolean(true)
	private var hooked: Boolean? = null
	private var sceneLazy: Lazy<*>? = null
	private var hookedScene: Any? = null
	private var sceneOffset: () -> Pair<Float, Float> = { 0f to 0f }
	private val recorder = PictureRecorder()
	private var surface: Surface? = null
	private var image: Image? = null
	private var width = 0
	private var height = 0
	/** The antialiasing samples [surface] and the layers were made with; a change of setting makes them again. */
	private var samples = 0
	private val layers = HashMap<String, Layer>()

	private class Layer(val surface: Surface, val width: Int, val height: Int) {
		var image: Image? = null
		fun close() { image?.close(); surface.close() }
	}

	/** Frames whose Compose picture was replayed, and all frames: how often the cache held, for the tools. Kept by
	 * the window, so a compositor made again for a wrapped delegate goes on counting. */
	private val pictureFrames get() = gpu.pictureFrames
	private val frames get() = gpu.composedFrames

	/** Whether this frame composites: canvases leave holes for their views only then. */
	@Volatile var active = false
		private set

	fun show(viewId: String, view: CompositedView) { views[viewId] = view; sortViews() }

	fun hide(viewId: String) { if (views.remove(viewId) != null) sortViews() }

	/** Root coordinates to the window surface; only queried by opt-in pixel diagnostics when not compositing. */
	internal fun presentationOffset(): Pair<Float, Float>? = if (hook()) sceneOffset() else null

	private fun sortViews() = synchronized(views) {
		// Views of different places do not overlap: only a layer's members need an order, and they stay together.
		painted = views.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it) }
			.sortedWith(compareBy({ it.value.layer ?: it.key }, { it.value.order }))
	}

	/** Draws the next frame of the picture as the window did (no holes, no cache). */
	fun invalidate() = invalidated.set(true)

	/** Renders a frame onto [canvas]; false when the window must draw as before (Compose straight onto it). */
	fun render(canvas: Canvas, width: Int, height: Int, nanoTime: Long, skia: DirectContext): Boolean {
		if (!hook()) return false
		frames.incrementAndGet()
		val sizeChanged = width != this.width || height != this.height
		val samplesNow = MultisampledWindow.effectiveSamples()
		if (samplesNow != samples) {
			layers.values.forEach { it.close() }
			layers.clear()
		}
		if (sizeChanged || surface == null || samplesNow != samples) {
			image?.close(); image = null
			// Cleared before the new one is made: a failed allocation must not leave a closed surface to draw into.
			surface?.close(); surface = null
			// Multisampled like the window's own target ([MultisampledWindow]): the GPU rasterizes antialiased shapes.
			val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
			val props = SurfaceProps(gpu.pixelGeometry ?: PixelGeometry.UNKNOWN)
			// Skiko throws, rather than returning null, when the GPU cannot make the target.
			surface = MultisampledWindow.target(skia, info, props)
				?: runCatching { Surface.makeRenderTarget(skia, false, info, 0, props) }.getOrNull() ?: return false
			this.width = width
			this.height = height
			samples = samplesNow
			invalidated.set(true)
		}
		val target = surface ?: return false
		active = true
		val picture = try {
			val recording = recorder.beginRecording(Rect.makeWH(width.toFloat(), height.toFloat()))
			inner.onRender(recording, width, height, nanoTime)
			recorder.finishRecordingAsPicture()
		} finally {
			active = false
		}
		// Requests made while Compose rendered (the snapshot changes it applied first) are this frame's.
		if (invalidated.getAndSet(false) || image == null) {
			pictureFrames.incrementAndGet()
			image?.close(); image = null
			// Skiko's last frame may still hold the old image: new pixels rather than a copy of them.
			target.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
			target.canvas.clear(0)
			target.canvas.drawPicture(picture)
			image = target.makeImageSnapshot()
		}
		picture.close()
		val (dx, dy) = sceneOffset()
		val shown = painted
		var i = 0
		while (i < shown.size) {
			val view = shown[i].value
			val layer = view.layer
			var end = i + 1
			if (layer != null) while (end < shown.size && shown[end].value.layer == layer) end++
			canvas.save()
			canvas.translate(dx + view.left, dy + view.top)
			canvas.clipRect(Rect.makeWH(view.width, view.height))
			try {
				val image = layer?.let { layerImage(it, shown.subList(i, end), skia) }
				if (image != null) canvas.drawImage(image, 0f, 0f)
				else for (k in i until end) shown[k].value.paint(canvas, gpu.resources.frame(shown[k].key))
			} finally {
				canvas.restore()
			}
			i = end
		}
		if (layers.isNotEmpty()) {
			val kept = shown.mapNotNullTo(HashSet()) { it.value.layer }
			layers.entries.removeIf { (name, layer) -> (name !in kept).also { if (it) layer.close() } }
		}
		image?.let { canvas.drawImage(it, 0f, 0f) }
		return true
	}

	/** The [members] of a layer painted into its texture, again when one is stale; null without such a texture. */
	private fun layerImage(name: String, members: List<Map.Entry<String, CompositedView>>, skia: DirectContext): Image? {
		val first = members.first().value
		val w = kotlin.math.ceil(first.width).toInt().coerceAtLeast(1)
		val h = kotlin.math.ceil(first.height).toInt().coerceAtLeast(1)
		var layer = layers[name]
		var redraw = layer?.image == null || members.any { it.value.stale() }
		if (layer == null || layer.width != w || layer.height != h) {
			layers.remove(name)?.close()
			val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB)
			val props = SurfaceProps(gpu.pixelGeometry ?: PixelGeometry.UNKNOWN)
			val surface = MultisampledWindow.target(skia, info, props)
				?: runCatching { Surface.makeRenderTarget(skia, false, info, 0, props) }.getOrNull() ?: return null
			layer = Layer(surface, w, h).also { layers[name] = it }
			redraw = true
		}
		if (redraw) {
			layer.image?.close(); layer.image = null
			val target = layer.surface
			target.notifyContentWillChange(org.jetbrains.skia.ContentChangeMode.DISCARD)
			target.canvas.clear(0)
			for ((viewId, view) in members) view.paint(target.canvas, gpu.resources.frame(viewId))
			layer.image = target.makeImageSnapshot()
		}
		return layer.image
	}

	companion object {
		/** Called on every draw or layout request the compositor sees; for the development tools. */
		@Volatile var onInvalidate: (() -> Unit)? = null
	}

	/**
	 * Wraps the invalidation callback of [inner]'s scene, so a draw or layout request marks the cached picture
	 * stale; also finds how the mediator places the scene in the window. False when Compose is not as expected.
	 */
	private fun hook(): Boolean {
		// A scene made again (another render API) has a tracker of its own.
		if (hooked == true && sceneLazy?.value !== hookedScene) hooked = null
		hooked?.let { return it }
		val ok = runCatching {
			val mediator = mediatorOf(inner) ?: error("no Compose scene mediator")
			val lazy = field(mediator.javaClass, "scene\$delegate").get(mediator) as Lazy<*>
			val scene = lazy.value ?: error("no scene")
			sceneLazy = lazy
			hookedScene = scene
			val tracker = field(scene.javaClass, "snapshotInvalidationTracker").get(scene) ?: error("no tracker")
			val invalidateField = field(tracker.javaClass, "invalidate")
			@Suppress("UNCHECKED_CAST")
			val original = invalidateField.get(tracker) as () -> Unit
			invalidateField.set(tracker, { invalidated.set(true); onInvalidate?.invoke(); original() })
			sceneOffset = offsetOf(mediator)
			invalidated.set(true)
			true
		}.onFailure { System.err.println("Window compositor unavailable: $it") }.getOrDefault(false)
		hooked = ok
		return ok
	}

	/** Where the mediator translates the scene to (`ComposeSceneMediator.onRender`): its bounds less its component's place. */
	private fun offsetOf(mediator: Any): () -> Pair<Float, Float> {
		val bounds = runCatching { field(mediator.javaClass, "sceneBoundsInPx") }.getOrNull()
		val component = runCatching { mediator.javaClass.getMethod("getContentComponent") }.getOrNull()
		return offset@{
			runCatching {
				val rect = bounds?.get(mediator) as? androidx.compose.ui.geometry.Rect
				val content = component?.invoke(mediator) as? java.awt.Component
				val scale = gpu.contentScale
				val x = (rect?.left ?: 0f) - (content?.x ?: 0) * scale
				val y = (rect?.top ?: 0f) - (content?.y ?: 0) * scale
				-x to -y
			}.getOrDefault(0f to 0f)
		}
	}

	/** The Compose mediator [delegate] is, or wraps (a delegate's captured inner delegate, as this window's own). */
	private fun mediatorOf(delegate: SkikoRenderDelegate, depth: Int = 0): Any? {
		if (delegate.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator") return delegate
		if (depth > 4) return null
		for (f in delegate.javaClass.declaredFields) {
			if (!SkikoRenderDelegate::class.java.isAssignableFrom(f.type)) continue
			val next = runCatching { f.isAccessible = true; f.get(delegate) as? SkikoRenderDelegate }.getOrNull() ?: continue
			mediatorOf(next, depth + 1)?.let { return it }
		}
		return null
	}

	private fun field(type: Class<*>, name: String): Field {
		var current: Class<*>? = type
		while (current != null) {
			runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
			current = current.superclass
		}
		error("No field $name on ${type.name}")
	}
}
