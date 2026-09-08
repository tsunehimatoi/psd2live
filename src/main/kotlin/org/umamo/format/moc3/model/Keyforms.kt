package org.umamo.format.moc3.model

/** An RGB color (each channel typically 0..1); the alpha is always 1 in moc3 color tables. */
public data class Rgb(val r: Float, val g: Float, val b: Float)

/**
 * One controlling axis of a [KeyformBinding]: a parameter and the key positions sampled along it.
 *
 * @property parameterIndex Index into [org.umamo.format.moc3.MocDocument.parameters] of the driving parameter.
 * @property keyPositions   The parameter values at which keyforms exist, ascending.
 */
public data class KeyformAxis(
	val parameterIndex: Int,
	val keyPositions: FloatArray,
) {
	/** Number of keys on this axis. */
	val keyCount: Int get() = keyPositions.size

	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is KeyformAxis &&
					parameterIndex == other.parameterIndex &&
					keyPositions.contentEquals(
						other.keyPositions,
					)
			)

	override fun hashCode(): Int = 31 * parameterIndex + keyPositions.contentHashCode()
}

/**
 * The interpolation grid an object's keyforms are laid out on: the controlling parameter [axes]
 * (in stride order, the first the fastest-varying). The number of keyforms is [gridSize]; a grid
 * index decomposes to per-axis key indices via [keyIndices].
 *
 * EN: A static object (no controlling parameters) has no axes and a single keyform.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
public data class KeyformBinding(
	val index: Int,
	val axes: List<KeyformAxis>,
) {
	/** Total number of keyforms = product of the axes' key counts (1 when there are no axes). */
	val gridSize: Int get() = axes.fold(1) { acc, axis -> acc * axis.keyCount }

	/**
	 * Decomposes a grid index into a per-axis key index (mixed-radix, first axis = stride 1).
	 *
	 * @param Int gridIndex A keyform index in `0 until gridSize`.
	 * @return IntArray The key index along each axis, in axis order.
	 */
	public fun keyIndices(gridIndex: Int): IntArray {
		var stride = 1
		return IntArray(axes.size) { axisIndex ->
			val keyIndex = (gridIndex / stride) % axes[axisIndex].keyCount
			stride *= axes[axisIndex].keyCount
			keyIndex
		}
	}
}