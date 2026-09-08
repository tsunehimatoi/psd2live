package org.umamo.render.puppet

/**
 * A stable, readable flat color for an untextured drawable, derived from its id.
 *
 * Only reached when a drawable resolves to no atlas page, so it is a development affordance rather than
 * anything a rigger sees in a textured model - it exists so an untextured mesh is visible and tellable
 * apart from its neighbours instead of rendering invisible.  Hashing the id (rather than cycling a
 * palette by index) keeps a given drawable the same color across reorders and reloads.
 *
 * The channel floor of 0.35 and the 0.6 span keep every result mid-bright: dark enough to read against
 * the light grid, light enough against the dark one.
 *
 * @param String id The drawable's raw id.
 * @return FloatArray RGBA, each 0..1, at a fixed 0.85 alpha.
 */
internal fun fallbackColorFor(id: String): FloatArray {
	val hash = id.hashCode()
	val red = 0.35f + ((hash shr 16) and 0xFF) / 255f * 0.6f
	val green = 0.35f + ((hash shr 8) and 0xFF) / 255f * 0.6f
	val blue = 0.35f + (hash and 0xFF) / 255f * 0.6f
	return floatArrayOf(red, green, blue, 0.85f)
}