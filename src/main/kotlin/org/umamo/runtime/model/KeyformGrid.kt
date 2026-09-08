package org.umamo.runtime.model

/**
 * One axis of a keyform grid: the parameter it keys on and the key values along it (the parameter
 * values where keyforms exist, e.g. `[-1, 0, 1]`).
 */
class KeyformAxis(
	val parameterId: ParameterId,
	val keys: FloatArray,
)

/**
 * A single keyform cell: its N-D [coordinate] (key index per axis, in [KeyformGrid.axes] order) and
 * the deformation [form] captured at that parameter combination.
 */
class KeyformCell<TForm>(
	val coordinate: IntArray,
	val form: TForm,
)

/**
 * The keyform grid for one controllable entity (drawable / deformer): the [axes] (one parameter each)
 * and the keyed [cells]. `cells.size == Π(axis key counts)` - the "interpolation" count the editor
 * reports. Cells are tagged with their coordinate rather than stored row-major, so the evaluator
 * can build whatever lookup the deformation math needs.
 */
class KeyformGrid<TForm>(
	val axes: List<KeyformAxis>,
	val cells: List<KeyformCell<TForm>>,
) {
	/**
	 * This grid's per-axis strides for folding a coordinate into a linear cell index.
	 *
	 * Axis 0 is the FASTEST varying (stride 1).  The resulting index is the shared contract between a
	 * WeightedCell, a stored cell, and the GPU delta texture's column - defined HERE, on the type that
	 * owns it, so the evaluator, the shape queries, and the grid algebra cannot drift apart.
	 *
	 * @return IntArray One stride per axis, in axis order.
	 */
	fun strides(): IntArray {
		val strides = IntArray(axes.size)
		var stride = 1
		for (axisIndex in axes.indices) {
			strides[axisIndex] = stride
			stride *= axes[axisIndex].keys.size
		}
		return strides
	}

	/**
	 * Folds a per-axis key-index [coordinate] into this grid's linear cell index.
	 *
	 * A coordinate shorter than the axis list contributes only the axes it covers, matching the
	 * defensive handling of a malformed imported cell.
	 *
	 * @param IntArray coordinate The key index per axis, in axis order.
	 * @return Int The stride-folded linear index.
	 */
	fun linearIndexOf(coordinate: IntArray): Int {
		val strides = strides()
		var linearIndex = 0
		val axisCount = minOf(coordinate.size, strides.size)
		for (axisIndex in 0 until axisCount) {
			linearIndex += coordinate[axisIndex] * strides[axisIndex]
		}
		return linearIndex
	}

	/**
	 * The cells indexed by their stride-folded linear index, built on first use and cached.
	 *
	 * Cached because the grid is immutable and the per-frame evaluator once rebuilt this map on every
	 * channel sample of every entity - hundreds of transient HashMaps per scrub frame on a corpus rig.
	 * PUBLICATION mode: a racing first read may compute twice but never blocks, and the render thread
	 * must never take a lock.
	 */
	val cellsByLinearIndex: Map<Int, KeyformCell<TForm>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val byIndex = HashMap<Int, KeyformCell<TForm>>(cells.size)
		for (cell in cells) {
			byIndex[linearIndexOf(cell.coordinate)] = cell
		}
		byIndex
	}
}

/**
 * A drawable keyform: per-vertex position deltas (interleaved x,y) relative to the mesh base
 * (`p = base + Σ wᵢ·Δᵢ`, stored as deltas to match the GPU vertex-shader morph), plus the animatable
 * scalars that ride on the same keyform. [drawOrder] (Cubism default 500) is the primary render-order
 * sort key; [opacity] (0..1) scales the drawable's alpha; [multiplyColor] / [screenColor] tint the
 * drawable per the Cubism per-art-mesh color (CMO3 `CArtMeshForm.multiplyColor`/`screenColor`, MOC3
 * color-table rows 108-113), left at their identities on pre-5.3 sources. All blend with the same
 * multilinear weights as the positions.
 */
class MeshForm(
	val positionDeltas: FloatArray,
	// Defaults are Cubism's own (drawOrder 500, fully opaque, identity tints); the CMO3 importer always
	// sets them explicitly, so the defaults only serve geometry-only unit tests that don't exercise these.
	val drawOrder: Float = DEFAULT_DRAW_ORDER.toFloat(),
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A part keyform: the animatable per-cell channels of a part, riding the part's own keyform grid.
 * [drawOrder] positions a grouped part's stacking slot (Cubism `CPartForm.drawOrder`); the
 * remaining channels are the layer composite's keyformed state ([opacity], [multiplyColor],
 * [screenColor] - CMO3 `CPartForm.opacity`/`multiplyColor`/`screenColor`, MOC3 §5.6 section 161 +
 * the color-table offscreen prefix rows), meaningful only when the part's group mode is Isolated
 * and left at their identities otherwise.
 */
class PartForm(
	val drawOrder: Float,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A warp-deformer keyform: the absolute FFD control-point positions (interleaved x,y). Warp
 * sources carry no separate rest lattice, so the forms are kept absolute; the evaluator interpolates
 * them directly.
 *
 * [opacity] / [multiplyColor] / [screenColor] are the deformer's own render channels, which CASCADE
 * down the deformer chain onto every drawable underneath (see `DeformerCascade`). Riggers use the
 * opacity as a parameter-driven subtree show/hide switch, so dropping it renders whole effect
 * subtrees permanently visible. (CMO3 `ACDeformerForm.opacity`/`multiplyColor`/`screenColor`;
 * MOC3 `WarpKeyform`.) A deformer has no draw order - that is a drawable/part concept.
 */
class WarpForm(
	val controlPoints: FloatArray,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)

/**
 * A rotation-deformer keyform: the absolute pivot transform captured at this grid cell, plus the
 * same cascading render channels a [WarpForm] carries.
 */
class RotationForm(
	val originX: Float,
	val originY: Float,
	val angle: Float,
	val scale: Float,
	/** Reflection flags (the Umamo C++ Runtime snaps these per grid cell into the affine's flipX/flipY). */
	val flipX: Boolean,
	val flipY: Boolean,
	val opacity: Float = 1f,
	val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
	val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
)