package org.umamo.format.moc3.model

/**
 * One keyform of an [Offscreen]: its opacity plus multiply/screen color. Offscreen keyforms ride
 * the owner part's keyform grid (there is no offscreen keyform-binding section; Σ owner-part grid
 * sizes == CountInfo 36), and their rows form the PREFIX of the shared color tables on moc 6.
 *
 * @property opacity       The offscreen's opacity at this keyform (MOC3 §5.6 section 161).
 * @property multiplyColor The multiply color row, or null when color tables are absent.
 * @property screenColor   The screen color row, or null when color tables are absent.
 */
public data class OffscreenKeyform(
	val opacity: Float,
	val multiplyColor: Rgb? = null,
	val screenColor: Rgb? = null,
)

/** An offscreen render target (moc 6 / Cubism 5.3): composites its owner part's subtree. */
public data class Offscreen(
	val ownerPartIndex: Int,
	/** [org.umamo.format.moc3.moc.ConstantFlag]-style bitmask (`u8`). */
	val constantFlags: Int,
	/** Packed blend-mode value as stored in the file (the runtime unpacks it into color/alpha modes). */
	val blendMode: Int,
	val maskCount: Int,
	/** Per-keyform opacity/color, parallel to the owner part's keyform grid (empty pre-extraction). */
	val keyforms: List<OffscreenKeyform> = emptyList(),
	/** Masking drawable indices from the offscreen suffix of MASK_INDEX_DATA (§5.6 section 80). */
	val maskIndices: IntArray = IntArray(0),
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is Offscreen &&
					ownerPartIndex == other.ownerPartIndex &&
					constantFlags == other.constantFlags &&
					blendMode == other.blendMode &&
					maskCount == other.maskCount &&
					keyforms == other.keyforms &&
					maskIndices.contentEquals(other.maskIndices)
			)

	override fun hashCode(): Int {
		var hash = ownerPartIndex
		hash = 31 * hash + constantFlags
		hash = 31 * hash + blendMode
		hash = 31 * hash + maskCount
		hash = 31 * hash + keyforms.hashCode()
		hash = 31 * hash + maskIndices.contentHashCode()
		return hash
	}
}

/** Which kind of object a [BlendShape] deltas (warps/meshes moc 4+; rotations/parts moc 5+). */
public enum class BlendShapeTarget { WARP, ART_MESH, ROTATION, PART }

/**
 * One key's extracted delta payload of a [BlendShape] record, per target kind.
 *
 * Values are the raw per-key delta rows lifted from the shared keyform value tables (MOC3.md
 * §5.6): relative to the object's grid form at the default pose, with the neutral key's row
 * all-zero. Geometry stays in the space the base keyforms use (parent-relative); no coordinate
 * conversion happens at the format layer. The reused keyform types carry the per-kind channels
 * (positions/control points/affine plus opacity, draw order, and color), all as deltas - so an
 * unauthored channel reads 0, not the channel's identity value.
 */
public sealed class BlendShapeKeyform {
	/** An art-mesh record's per-key deltas (vertex positions, opacity, draw order, colors). */
	public data class Mesh(val form: ArtMeshKeyform) : BlendShapeKeyform()

	/** A warp record's per-key deltas (lattice control points, opacity, colors). */
	public data class Warp(val form: WarpKeyform) : BlendShapeKeyform()

	/** A rotation record's per-key deltas (origin/angle/scale, opacity, colors; reflects unused). */
	public data class Rotation(val form: RotationKeyform) : BlendShapeKeyform()

	/** A part record's per-key delta (draw order only - MOC3 §5.6 section 58 delta rows). */
	public data class Part(val drawOrderDelta: Float) : BlendShapeKeyform()
}

/**
 * A blend-weight limit ("blend shape limit") on a [BlendShape] record: an end-clamped
 * piecewise-linear (key, weight) curve over ANOTHER parameter's value that caps the record's
 * weight. A record's effective multiplier is the MINIMUM over its limits (1 when it has none).
 *
 * On disk (MOC3 v4+ §5.6, sections 123/124 + 131-136) records reference a DEDUPLICATED pool of
 * sub-bindings; the decoder expands the pool per record, so identical curves shared by many
 * records appear on each. A future lowering re-dedups by value when synthesizing the tables.
 *
 * @property parameterIndex The gating parameter (index into [org.umamo.format.moc3.MocDocument.parameters]).
 * @property keyPositions   The curve's parameter values, ascending.
 * @property weights        The weight cap at each key, parallel to [keyPositions].
 */
public data class BlendShapeLimit(
	val parameterIndex: Int,
	val keyPositions: FloatArray,
	val weights: FloatArray,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is BlendShapeLimit &&
					parameterIndex == other.parameterIndex &&
					keyPositions.contentEquals(other.keyPositions) &&
					weights.contentEquals(other.weights)
			)

	override fun hashCode(): Int {
		var hash = parameterIndex
		hash = 31 * hash + keyPositions.contentHashCode()
		hash = 31 * hash + weights.contentHashCode()
		return hash
	}
}

/**
 * A blend-shape record (moc 4+ for meshes/warps, moc 5+ for rotations): an additive deformation
 * delta driven by one parameter. The deltas live in the shared keyform value tables at
 * [recordBase] (per key) and are lifted into [keyforms]; each is relative to the object's grid
 * form at the default pose, with the [neutralKeyIndex] row all-zero.
 *
 * @property target        Kind of deformed object.
 * @property targetIndex   For [BlendShapeTarget.WARP]/[BlendShapeTarget.ROTATION], the deformer index
 *                         in [org.umamo.format.moc3.MocDocument.deformers]; for
 *                         [BlendShapeTarget.ART_MESH], the drawable index; for
 *                         [BlendShapeTarget.PART], the part index.
 * @property parameterIndex The driving parameter (index into [org.umamo.format.moc3.MocDocument.parameters]).
 * @property keyPositions   Parameter values at which deltas are keyed.
 * @property neutralKeyIndex The key whose delta is zero (the rest shape).
 * @property recordBase      Base index into the keyform value tables for this record's per-key deltas.
 * @property limits          Blend-weight limits capping this record's weight (min-combined; empty = uncapped).
 * @property keyforms        Per-key delta payloads, parallel to [keyPositions] (one per key, kind
 *                           matching [target]); see [BlendShapeKeyform] for the value conventions.
 */
public data class BlendShape(
	val target: BlendShapeTarget,
	val targetIndex: Int,
	val parameterIndex: Int,
	val keyPositions: FloatArray,
	val neutralKeyIndex: Int,
	val recordBase: Int,
	val limits: List<BlendShapeLimit> = emptyList(),
	val keyforms: List<BlendShapeKeyform> = emptyList(),
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is BlendShape &&
					target == other.target &&
					targetIndex == other.targetIndex &&
					parameterIndex == other.parameterIndex &&
					keyPositions.contentEquals(other.keyPositions) &&
					neutralKeyIndex == other.neutralKeyIndex &&
					recordBase == other.recordBase &&
					limits == other.limits &&
					keyforms == other.keyforms
			)

	override fun hashCode(): Int {
		var hash = target.hashCode()
		hash = 31 * hash + targetIndex
		hash = 31 * hash + parameterIndex
		hash = 31 * hash + keyPositions.contentHashCode()
		hash = 31 * hash + neutralKeyIndex
		hash = 31 * hash + recordBase
		hash = 31 * hash + limits.hashCode()
		hash = 31 * hash + keyforms.hashCode()
		return hash
	}
}