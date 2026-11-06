package io.github.psd2live.core.fit

/**
 * A feature point of the head read off the art, in the rig's aligned space: [id] names it (`face.chin`, `eye.L.outer`),
 * [part] the feature it belongs to. A [later] point is not used by the head's turn yet; it is kept for what will need it
 * (a closing eye's lashes, an opening mouth's skin).
 */
data class Landmark(val id: String, val part: String, val x: Float, val y: Float, val later: Boolean = false)

/**
 * The head's feature points ([points]), the face skin's outline in order round it ([contour], rig space, empty when the
 * art has no face layer) and distances that are not points ([measures], rig units).
 */
class FaceLandmarks(val points: List<Landmark>, val contour: List<Pair<Float, Float>>, val measures: Map<String, Float>) {
	private val byId = points.associateBy { it.id }
	operator fun get(id: String): Landmark? = byId[id]
}
