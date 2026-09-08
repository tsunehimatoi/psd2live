package org.umamo.format.art

/**
 * A growable Int stack for the iterative Douglas-Peucker range walk.
 *
 * Exists because contours can carry 100k+ vertices and a recursive formulation would gamble
 * on Kotlin/Native stack depth in commonMain; java.util.ArrayDeque is unavailable here.
 */
private class IntStack {
	private var storage = IntArray(INITIAL_CAPACITY)
	private var size = 0

	/**
	 * Pushes one value.
	 *
	 * @param Int value The value to push.
	 */
	fun push(value: Int) {
		if (size == storage.size) {
			storage = storage.copyOf(storage.size * 2)
		}
		storage[size] = value
		size++
	}

	/** Removes and returns the top value; the caller guarantees non-emptiness. */
	fun pop(): Int {
		size--
		return storage[size]
	}

	/** Whether any values remain. */
	fun isNotEmpty(): Boolean = size > 0

	private companion object {
		const val INITIAL_CAPACITY = 64
	}
}

/**
 * Simplifies a closed lattice ring with Douglas-Peucker, returning a subset of its vertices.
 *
 * Closed rings get the standard two-anchor treatment: anchor A is ring index 0 (the tracer's
 * deterministic topmost-then-leftmost start vertex) and anchor B is the vertex farthest from A
 * (ties resolve to the lowest index); the two open halves A->B and B->A are simplified
 * independently, so both anchors always survive.  The walk is iterative over an explicit range
 * stack.  Distances are exact until the final comparison: Long integer dot and cross products,
 * squared distance to the clamped segment (robust for halves that double back), and a strict
 * greater-than against epsilon squared, so epsilon 0 drops exactly-collinear vertices and
 * nothing else.
 *
 * Guarantees: every kept vertex is an original lattice vertex on the exact mask boundary, and
 * every dropped vertex lies within epsilon pixels of a segment of the simplified ring (the
 * Douglas-Peucker construction), so the result deviates from the exact boundary by at most
 * epsilon.  If simplification would leave fewer than 3 points (a 1xN sliver's rectangle at
 * epsilon 1 collapses to its two anchors), the unsimplified ring is returned instead.  Output
 * is deterministic.
 *
 * Known limitation, accepted for v1: on adversarial shapes Douglas-Peucker can produce a
 * self-intersecting simplified ring.  Callers needing a guaranteed-simple polygon should pass
 * a smaller epsilon (or 0) until a topology-preserving variant exists.
 *
 * @param IntArray points Flattened x0, y0, x1, y1, ... closed ring; last point connects to the first.
 * @param Float epsilon Maximum allowed deviation from the input ring, in pixels; non-negative.
 * @return IntArray The simplified ring, or the input array itself when nothing can be dropped.
 */
internal fun simplifyClosedRing(points: IntArray, epsilon: Float): IntArray {
	val pointCount = points.size / 2
	if (pointCount < 4) {
		// A triangle is already minimal: dropping any vertex would degenerate the ring.
		return points
	}

	var anchorB = 0
	var anchorBSquaredDistance = -1L
	for (pointIndex in 1 until pointCount) {
		val deltaX = (points[pointIndex * 2] - points[0]).toLong()
		val deltaY = (points[pointIndex * 2 + 1] - points[1]).toLong()
		val squaredDistance = deltaX * deltaX + deltaY * deltaY
		if (squaredDistance > anchorBSquaredDistance) {
			anchorBSquaredDistance = squaredDistance
			anchorB = pointIndex
		}
	}

	val keep = BooleanArray(pointCount)
	keep[0] = true
	keep[anchorB] = true
	val epsilonSquared = epsilon.toDouble() * epsilon.toDouble()

	// Ranges use virtual indices 0..pointCount so the wrap-around half (anchorB back to A) is
	// a plain ascending range; a virtual index maps to ring index (virtual % pointCount).
	val rangeStack = IntStack()
	rangeStack.push(0)
	rangeStack.push(anchorB)
	rangeStack.push(anchorB)
	rangeStack.push(pointCount)
	while (rangeStack.isNotEmpty()) {
		val lastVirtual = rangeStack.pop()
		val firstVirtual = rangeStack.pop()
		if (lastVirtual - firstVirtual < 2) {
			continue
		}
		val segmentStartIndex = (firstVirtual % pointCount) * 2
		val segmentEndIndex = (lastVirtual % pointCount) * 2
		var farthestVirtual = -1
		var farthestSquaredDistance = -1.0
		for (interiorVirtual in firstVirtual + 1 until lastVirtual) {
			val interiorIndex = (interiorVirtual % pointCount) * 2
			val squaredDistance =
				squaredDistanceToSegment(
					pointX = points[interiorIndex],
					pointY = points[interiorIndex + 1],
					segmentStartX = points[segmentStartIndex],
					segmentStartY = points[segmentStartIndex + 1],
					segmentEndX = points[segmentEndIndex],
					segmentEndY = points[segmentEndIndex + 1],
				)
			if (squaredDistance > farthestSquaredDistance) {
				farthestSquaredDistance = squaredDistance
				farthestVirtual = interiorVirtual
			}
		}
		if (farthestSquaredDistance > epsilonSquared) {
			keep[farthestVirtual % pointCount] = true
			rangeStack.push(firstVirtual)
			rangeStack.push(farthestVirtual)
			rangeStack.push(farthestVirtual)
			rangeStack.push(lastVirtual)
		}
	}

	var keptCount = 0
	for (pointIndex in 0 until pointCount) {
		if (keep[pointIndex]) {
			keptCount++
		}
	}
	if (keptCount < 3) {
		return points
	}
	if (keptCount == pointCount) {
		return points
	}
	val simplified = IntArray(keptCount * 2)
	var writeIndex = 0
	for (pointIndex in 0 until pointCount) {
		if (keep[pointIndex]) {
			simplified[writeIndex] = points[pointIndex * 2]
			simplified[writeIndex + 1] = points[pointIndex * 2 + 1]
			writeIndex += 2
		}
	}
	return simplified
}

/**
 * Squared distance from a point to a segment, clamped to the segment's endpoints.
 *
 * Dot, cross, and length products are exact Longs from Int coordinates; only the final
 * quotient goes through Double.  A zero-length segment (possible between weakly simple ring
 * vertices that coincide) degrades to point distance.
 *
 * @param Int pointX The point's x.
 * @param Int pointY The point's y.
 * @param Int segmentStartX Segment start x.
 * @param Int segmentStartY Segment start y.
 * @param Int segmentEndX Segment end x.
 * @param Int segmentEndY Segment end y.
 * @return Double The squared distance in pixels squared.
 */
private fun squaredDistanceToSegment(
	pointX: Int,
	pointY: Int,
	segmentStartX: Int,
	segmentStartY: Int,
	segmentEndX: Int,
	segmentEndY: Int,
): Double {
	val segmentDeltaX = (segmentEndX - segmentStartX).toLong()
	val segmentDeltaY = (segmentEndY - segmentStartY).toLong()
	val toPointX = (pointX - segmentStartX).toLong()
	val toPointY = (pointY - segmentStartY).toLong()
	val segmentLengthSquared = segmentDeltaX * segmentDeltaX + segmentDeltaY * segmentDeltaY
	if (segmentLengthSquared == 0L) {
		return (toPointX * toPointX + toPointY * toPointY).toDouble()
	}
	val projection = toPointX * segmentDeltaX + toPointY * segmentDeltaY
	if (projection <= 0L) {
		return (toPointX * toPointX + toPointY * toPointY).toDouble()
	}
	if (projection >= segmentLengthSquared) {
		val fromEndX = (pointX - segmentEndX).toLong()
		val fromEndY = (pointY - segmentEndY).toLong()
		return (fromEndX * fromEndX + fromEndY * fromEndY).toDouble()
	}
	val cross = toPointX * segmentDeltaY - toPointY * segmentDeltaX
	return cross.toDouble() * cross.toDouble() / segmentLengthSquared.toDouble()
}