package io.github.psd2live.algorithms.binding

/** Choose an aperture that covers the component, rather than a nearby lip stroke. */
object MouthMaskAssociation {
    fun <T> select(component: Bounds, candidates: List<T>, bounds: (T) -> Bounds): T? {
        fun overlap(candidate: T): Float {
            val b = bounds(candidate)
            return (minOf(component.right, b.right) - maxOf(component.left, b.left)).coerceAtLeast(0f) *
                (minOf(component.bottom, b.bottom) - maxOf(component.top, b.top)).coerceAtLeast(0f)
        }
        fun distance(candidate: T): Float {
            val b = bounds(candidate)
            val dx = b.centerX - component.centerX; val dy = b.centerY - component.centerY
            return dx * dx + dy * dy
        }
        val covering = candidates.filter { overlap(it) > 0f }
        if (covering.isEmpty()) return candidates.minByOrNull(::distance)
        return covering.maxWithOrNull(compareBy<T> { overlap(it) }
            .thenBy { bounds(it).height > component.height * 1.5f }
            .thenBy { -(bounds(it).width * bounds(it).height) }
            .thenBy { -distance(it) })
    }
}
