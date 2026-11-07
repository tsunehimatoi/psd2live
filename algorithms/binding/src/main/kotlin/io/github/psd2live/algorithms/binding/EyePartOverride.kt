package io.github.psd2live.algorithms.binding

import kotlinx.serialization.Serializable

/** A source-bound structural correction. Stale corrections remain stored so restoring the source restores the edit. */
@Serializable
data class EyePartOverride(
	val sourceStamp: String,
	val side: Side? = null,
	val region: EyeRegion? = null,
	val primitive: EyePrimitive? = null,
	val contacts: List<EyeContact>? = null,
	/** Ordered source stroke samples in the eye's frame, from inner to outer. */
	val path: List<EyePoint>? = null,
) {
	init {
		require(sourceStamp.isNotBlank())
		require(side != Side.NONE)
		require(contacts == null || contacts.size <= 64 && contacts.all {
			it.from.isFinite() && it.to.isFinite() && it.from <= it.to && it.centre.x.isFinite() && it.centre.y.isFinite()
		})
		require(path == null || path.size in 2..1024 && path.all { it.x.isFinite() && it.y.isFinite() } &&
			path.zipWithNext().all { (a, b) -> a.x < b.x })
	}
}
