package org.umamo.edit

/**
 * A change of texture-coordinate frame: how the stored uvs relate to the space an edit is authored in.
 *
 * The UV editor shows a drawable's mapping over two different surfaces - the packed atlas page it is
 * stored against, and the source artwork it was authored against - and an operation expressed in
 * normalized coordinates (Mirror is the only one) means something different in each.  Handing the
 * operation this pair lets it work in whichever frame the user is looking at while still committing
 * the stored form.
 *
 * Both members are 2x3 affines (m00, m01, m02, m10, m11, m12) over normalized coordinates, mutual
 * inverses.  A page-view edit passes no frame at all rather than an identity pair, so that path stays
 * bit-identical to having no frame concept.
 *
 * A plain class, not a data class: the fields are arrays, whose identity-based equals would make a
 * generated structural equals quietly wrong.  Never use one as a snapshot or effect key - key on the
 * scalars it was built from.
 */
class UvFrame(private val toFrameAffine: FloatArray, private val fromFrameAffine: FloatArray) {
	/**
	 * Maps stored coordinates into the authoring frame.
	 *
	 * @param FloatArray uvs The stored coordinates, interleaved (u, v).
	 * @return FloatArray The frame coordinates, a fresh array.
	 */
	fun toFrame(uvs: FloatArray): FloatArray = applyAffine(uvs, toFrameAffine)

	/**
	 * Maps authoring-frame coordinates back to the stored form.
	 *
	 * @param FloatArray uvs The frame coordinates, interleaved (u, v).
	 * @return FloatArray The stored coordinates, a fresh array.
	 */
	fun fromFrame(uvs: FloatArray): FloatArray = applyAffine(uvs, fromFrameAffine)

	/**
	 * Maps one stored point into the authoring frame.
	 *
	 * @param Float u The stored u.
	 * @param Float v The stored v.
	 * @return Pair<Float, Float> The point in frame coordinates.
	 */
	fun pointToFrame(u: Float, v: Float): Pair<Float, Float> =
		(toFrameAffine[0] * u + toFrameAffine[1] * v + toFrameAffine[2]) to
			(toFrameAffine[3] * u + toFrameAffine[4] * v + toFrameAffine[5])

	/**
	 * Maps one authoring-frame point back to the stored form.
	 *
	 * @param Float u The frame u.
	 * @param Float v The frame v.
	 * @return Pair<Float, Float> The point in stored coordinates.
	 */
	fun pointFromFrame(u: Float, v: Float): Pair<Float, Float> =
		(fromFrameAffine[0] * u + fromFrameAffine[1] * v + fromFrameAffine[2]) to
			(fromFrameAffine[3] * u + fromFrameAffine[4] * v + fromFrameAffine[5])

	/**
	 * Maps a whole interleaved uv array through a 2x3 row-major affine, into a fresh array.
	 *
	 * A trailing odd component is left out rather than half-mapped: the arrays are interleaved pairs, so
	 * an odd length is malformed input, and the callers' own guards reject it upstream.
	 *
	 * @param FloatArray uvs The interleaved (u, v) pairs to map.
	 * @param FloatArray affine The 2x3 row-major affine (m00, m01, m02, m10, m11, m12).
	 * @return FloatArray The mapped pairs, in a new array of the same length.
	 */
	private fun applyAffine(uvs: FloatArray, affine: FloatArray): FloatArray {
		val mapped = FloatArray(uvs.size)
		var componentIndex = 0
		while (componentIndex + 1 < uvs.size) {
			val u = uvs[componentIndex]
			val v = uvs[componentIndex + 1]
			mapped[componentIndex] = affine[0] * u + affine[1] * v + affine[2]
			mapped[componentIndex + 1] = affine[3] * u + affine[4] * v + affine[5]
			componentIndex += 2
		}
		return mapped
	}
}