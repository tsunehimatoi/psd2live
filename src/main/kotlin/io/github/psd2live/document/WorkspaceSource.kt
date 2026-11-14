package io.github.psd2live.document

import org.umamo.format.art.*

/**
 * A layer's rectangle on the canvas in canvas units, possibly fractional. The layer's integer bounds stay the
 * enclosing box; a layer without a rectangle covers exactly its integer bounds.
 */
data class LayerCanvasRect(
	val left: Float,
	val top: Float,
	val width: Float,
	val height: Float,
) {
	init {
		require(left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite()) { "Layer rectangle must be finite" }
		require(width >= 0f && height >= 0f) { "Layer rectangle must not be negative" }
	}

	val right: Float get() = left + width
	val bottom: Float get() = top + height

	/** Whether this rectangle is exactly [bounds], so it need not be stored. */
	fun matches(bounds: LayerBounds): Boolean = left == bounds.left.toFloat() && top == bounds.top.toFloat() &&
		width == bounds.width.toFloat() && height == bounds.height.toFloat()

	/** Whether [bounds] encloses this rectangle, allowing float rounding at the far edges. */
	fun within(bounds: LayerBounds): Boolean = left >= bounds.left && top >= bounds.top &&
		right <= bounds.left + bounds.width + EDGE_TOLERANCE && bottom <= bounds.top + bounds.height + EDGE_TOLERANCE

	companion object {
		private const val EDGE_TOLERANCE = 1e-3f

		fun of(bounds: LayerBounds): LayerCanvasRect =
			LayerCanvasRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.width.toFloat(), bounds.height.toFloat())
	}
}

/**
 * Where a layer sits on the canvas after the user moved, scaled or rotated it: canvas' = A·canvas + t, applied to the
 * layer's own frame (its [LayerCanvasRect]). The frame is what the layer's pixels, texture tile and texture coordinates
 * are anchored to, so a transform never resamples pixels, repacks the atlas or touches texture coordinates; it moves
 * the layer's meshes (the `layer_transform` edit) and where the canvas shows its pixels
 * for painting. Generation reads the frame, not the transform: moving a layer is the user's edit, not a new input.
 *
 * `x' = a·x + c·y + e`, `y' = b·x + d·y + f`, in canvas units with y down.
 */
data class LayerTransform(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
	init {
		require(listOf(a, b, c, d, e, f).all(Float::isFinite)) { "Layer transform must be finite" }
		require(kotlin.math.abs(a * d - b * c) > 1e-8f) { "Layer transform must be invertible" }
	}

	val isIdentity: Boolean get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

	/**
	 * Whether this only moves and scales along the canvas axes (no turn, skew or flip). A turn of a few millionths - float
	 * noise a stored transform may carry - is none: across a 4096-pixel layer it is a hundredth of a pixel.
	 */
	val isAxisAligned: Boolean get() = a > 0f && d > 0f && kotlin.math.abs(b) <= AXIS_NOISE * a && kotlin.math.abs(c) <= AXIS_NOISE * d

	fun x(x: Float, y: Float): Float = a * x + c * y + e
	fun y(x: Float, y: Float): Float = b * x + d * y + f

	/**
	 * This after [first]: the transform that applies [first], then this. Float noise is dropped from the result - a turn
	 * or a scale off one by a few millionths, a move by a few hundred-thousandths - so moving a layer back and forth
	 * leaves it exactly where it began rather than turned by rounding.
	 */
	fun after(first: LayerTransform): LayerTransform {
		val na = a * first.a + c * first.b; val nb = b * first.a + d * first.b
		val nc = a * first.c + c * first.d; val nd = b * first.c + d * first.d
		val ne = a * first.e + c * first.f + e; val nf = b * first.e + d * first.f + f
		val scale = maxOf(kotlin.math.abs(na), kotlin.math.abs(nb), kotlin.math.abs(nc), kotlin.math.abs(nd))
		fun linear(v: Float) = when {
			kotlin.math.abs(v) <= COMPOSE_NOISE * scale -> 0f
			kotlin.math.abs(kotlin.math.abs(v) - 1f) <= COMPOSE_NOISE -> kotlin.math.sign(v)
			else -> v
		}
		// + 0f turns a negative zero into zero, so equal transforms compare equal.
		fun move(v: Float) = if (kotlin.math.abs(v) <= 1e-5f) 0f else v + 0f
		return LayerTransform(linear(na) + 0f, linear(nb) + 0f, linear(nc) + 0f, linear(nd) + 0f, move(ne), move(nf))
	}

	fun inverse(): LayerTransform {
		val det = a * d - b * c
		val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
		return LayerTransform(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
	}

	/** [rect] transformed; only for an axis-aligned transform. */
	fun applyTo(rect: LayerCanvasRect): LayerCanvasRect {
		require(isAxisAligned) { "Only a move or scale keeps a rectangle a rectangle" }
		return LayerCanvasRect(x(rect.left, rect.top), y(rect.left, rect.top), rect.width * a, rect.height * d)
	}

	fun toList(): List<Float> = listOf(a, b, c, d, e, f)

	companion object {
		val IDENTITY = LayerTransform(1f, 0f, 0f, 1f, 0f, 0f)
		/** The relative turn below which a transform still counts as moving and scaling along the axes. */
		private const val AXIS_NOISE = 1e-5f
		/** The relative rounding [after] drops from a composed transform. */
		private const val COMPOSE_NOISE = 1e-6f

		fun of(values: List<Float>): LayerTransform {
			require(values.size == 6) { "A layer transform has six numbers [a, b, c, d, e, f]" }
			return LayerTransform(values[0], values[1], values[2], values[3], values[4], values[5])
		}
	}
}

/** The layer's transform on the canvas; identity for a layer never moved as a whole. */
internal val SourceLayer.transform: LayerTransform
	get() = (this as? WorkspaceSourceMetadata)?.layerTransform ?: LayerTransform.IDENTITY

/**
 * Where the canvas shows the layer's pixels: its frame under its [transform]; null when the transform turns or skews
 * it, so no rectangle describes it.
 */
internal fun SourceLayer.displayedRect(): LayerCanvasRect? = transform.let { t ->
	if (t.isIdentity) canvasRect() else if (t.isAxisAligned) t.applyTo(canvasRect()) else null
}

/** The layer's float canvas rectangle when it differs from its integer bounds; null means exactly the bounds. */
internal val SourceLayer.storedCanvasRect: LayerCanvasRect?
	get() = (this as? WorkspaceSourceMetadata)?.rect?.takeUnless { it.matches(bounds) }

/** The layer's canvas rectangle: its stored float rectangle, or its integer bounds. */
internal fun SourceLayer.canvasRect(): LayerCanvasRect = storedCanvasRect ?: LayerCanvasRect.of(bounds)

/** Marker used to distinguish Agent-created source layers from layers loaded from the artist file. */
internal interface WorkspaceSourceMetadata : SourceLayer {
	val derived: Boolean
	val sourceAssetId: String?
	val sourceSpatialReferenceId: String?
	/** Float canvas rectangle; null (or equal to [bounds]) means the integer bounds. */
	val rect: LayerCanvasRect? get() = null
	/** The layer's [LayerTransform]; null means identity. */
	val layerTransform: LayerTransform? get() = null
}

internal data class WorkspaceSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt

internal data class WorkspaceSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val groupPath: String,
	override val kind: SourceLayerKind,
	override val visible: Boolean,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val channelMask: ChannelMask,
	override val raster: LayerRaster,
	override val sourceAssetId: String?,
	override val sourceSpatialReferenceId: String?,
	override val derived: Boolean,
	override val rect: LayerCanvasRect? = null,
	override val layerTransform: LayerTransform? = null,
) : WorkspaceSourceMetadata {
	init {
		require(rect == null || rect.within(bounds)) { "Layer rectangle must lie within its integer bounds" }
	}

	companion object {
		fun copyOf(layer: SourceLayer, order: Int): SourceLayer = WorkspaceSourceLayer(
			id = layer.id,
			name = layer.name,
			groupPath = layer.groupPath,
			kind = layer.kind,
			visible = layer.visible,
			order = order,
			bounds = layer.bounds,
			opacity = layer.opacity,
			clipped = layer.clipped,
			blend = layer.blend,
			channelMask = layer.channelMask,
			raster = layer.raster,
			sourceAssetId = (layer as? WorkspaceSourceMetadata)?.sourceAssetId,
			sourceSpatialReferenceId = (layer as? WorkspaceSourceMetadata)?.sourceSpatialReferenceId,
			derived = (layer as? WorkspaceSourceMetadata)?.derived == true,
			rect = (layer as? WorkspaceSourceMetadata)?.rect,
			layerTransform = (layer as? WorkspaceSourceMetadata)?.layerTransform?.takeUnless { it.isIdentity },
		)
	}
}
