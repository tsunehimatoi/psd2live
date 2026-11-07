package org.umamo.format.art

/** Simplifies one exact lattice ring, preserving the hole classification of the original trace. */
public fun AlphaContour.simplified(epsilon: Float): AlphaContour {
	if (!(epsilon > 0f)) return this
	val simplified = simplifyClosedRing(points, epsilon)
	return if (simplified === points) this else AlphaContour(simplified, isHole)
}
