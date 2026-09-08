package org.umamo.format.moc3.decode

import org.umamo.format.moc3.moc.MocSections
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.Rgb

/**
 * The shared per-keyform color tables (MOC3 §5.6 sections 108-113) and their row addressing.
 *
 * Every consumer reaches the same six tables by a different route - a base keyform as
 * `colorBase + gridIndex`, an offscreen as one of the block's prefix rows, a blend-shape delta as
 * a delta row - so the addressing is expressed once as [multiplyAtRow] / [screenAtRow] and the
 * keyform form layers on top.  An absent color section yields a null color from every accessor,
 * which is what keeps the presence check off the call sites.
 */
internal class ColorTables(sections: MocSections) {
	/** Whether the model carries color tables at all; absent on a model with no color animation. */
	val isPresent: Boolean = sections.isPresent(Section.COLOR_MULTIPLY_R)

	private val multiplyR: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_R)
	private val multiplyG: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_G)
	private val multiplyB: FloatArray = sections.floatArray(Section.COLOR_MULTIPLY_B)
	private val screenR: FloatArray = sections.floatArray(Section.COLOR_SCREEN_R)
	private val screenG: FloatArray = sections.floatArray(Section.COLOR_SCREEN_G)
	private val screenB: FloatArray = sections.floatArray(Section.COLOR_SCREEN_B)

	/**
	 * Rows the tables hold, which the blend path probes against to decide whether the delta region
	 * was baked at all (a 4.2-era bake carries the base rows only).
	 */
	val rowCount: Int get() = multiplyR.size

	/**
	 * Multiply-color at absolute table row [row].
	 *
	 * @param Int row The absolute row index; negative means "this object has no color row".
	 * @return Rgb? The multiply color, or null when the tables are absent or [row] is negative.
	 */
	fun multiplyAtRow(row: Int): Rgb? =
		if (isPresent && row >= 0) {
			Rgb(multiplyR[row], multiplyG[row], multiplyB[row])
		} else {
			null
		}

	/**
	 * Screen-color at absolute table row [row].
	 *
	 * @param Int row The absolute row index; negative means "this object has no color row".
	 * @return Rgb? The screen color, or null when the tables are absent or [row] is negative.
	 */
	fun screenAtRow(row: Int): Rgb? =
		if (isPresent && row >= 0) {
			Rgb(screenR[row], screenG[row], screenB[row])
		} else {
			null
		}

	/**
	 * Multiply-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
	 *
	 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
	 * @param Int  gridIndex The keyform's grid index.
	 * @return Rgb? The multiply color, or null when color is absent.
	 */
	fun multiplyForKeyform(colorBase: Int?, gridIndex: Int): Rgb? = multiplyAtRow(keyformRow(colorBase, gridIndex))

	/**
	 * Screen-color at keyform [gridIndex] of an object whose color table starts at [colorBase].
	 *
	 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
	 * @param Int  gridIndex The keyform's grid index.
	 * @return Rgb? The screen color, or null when color is absent.
	 */
	fun screenForKeyform(colorBase: Int?, gridIndex: Int): Rgb? = screenAtRow(keyformRow(colorBase, gridIndex))

	/**
	 * Resolves an object's keyform row, collapsing "uncolored" to the negative sentinel the
	 * row accessors already treat as absent.
	 *
	 * @param Int? colorBase The object's base row (null or -1 when the object is uncolored).
	 * @param Int  gridIndex The keyform's grid index.
	 * @return Int The absolute row, or -1 when the object carries no color.
	 */
	private fun keyformRow(colorBase: Int?, gridIndex: Int): Int =
		if (colorBase == null || colorBase < 0) {
			-1
		} else {
			colorBase + gridIndex
		}
}

/**
 * The per-keyform value tables shared by the base keyforms and the blend-shape delta rows.
 *
 * MOC3 §5.6 appends a record's delta rows AFTER the base rows of these same tables, so a base
 * keyform and a delta differ only in which row they address.  That is why they are read once here
 * and handed to both paths.  Per-type structural tables (a warp's rows/columns, a drawable's UVs,
 * any id or flag column) are NOT shared and stay with their own decoder.
 */
internal class KeyformValueTables(sections: MocSections) {
	/** Art-mesh keyform -> packed-position offset (§5.6 section 70, indexing into 71). */
	val positionIndex: IntArray = sections.intArray(Section.KEYFORM_POSITION_INDEX)

	/** Warp keyform -> packed-position offset; a table distinct from [positionIndex] (section 60). */
	val warpPositionIndex: IntArray = sections.intArray(Section.WARP_KEYFORM_INDEX)

	/** The packed position blocks both index tables point into (section 71). */
	val positionValues: FloatArray = sections.floatArray(Section.KEYFORM_POSITION_VALUES)

	/** Part draw-order rows (section 58). */
	val partDrawOrder: FloatArray = sections.floatArray(Section.PART_DRAW_ORDER)

	/** Warp opacity rows (section 59). */
	val warpOpacity: FloatArray = sections.floatArray(Section.WARP_OPACITY)

	/** Art-mesh opacity rows (section 68). */
	val artMeshOpacity: FloatArray = sections.floatArray(Section.ARTMESH_OPACITY)

	/** Art-mesh draw-order rows (section 69). */
	val artMeshDrawOrder: FloatArray = sections.floatArray(Section.ARTMESH_DRAW_ORDER)

	/** Rotation opacity rows (section 61); a rotation delta indexes the affine tables directly. */
	val rotationOpacity: FloatArray = sections.floatArray(Section.ROTATION_OPACITY)

	/** Rotation angle rows (section 62). */
	val rotationAngle: FloatArray = sections.floatArray(Section.ROTATION_ANGLE)

	/** Rotation pivot X rows (section 63). */
	val rotationOriginX: FloatArray = sections.floatArray(Section.ROTATION_ORIGIN_X)

	/** Rotation pivot Y rows (section 64). */
	val rotationOriginY: FloatArray = sections.floatArray(Section.ROTATION_ORIGIN_Y)

	/** Rotation scale rows (section 65). */
	val rotationScale: FloatArray = sections.floatArray(Section.ROTATION_SCALE)

	/** Rotation X-reflection flags (section 66). */
	val rotationReflectX: IntArray = sections.intArray(Section.ROTATION_REFLECT_X)

	/** Rotation Y-reflection flags (section 67). */
	val rotationReflectY: IntArray = sections.intArray(Section.ROTATION_REFLECT_Y)
}