package io.github.psd2live.core

/** Coordinates use mouth width for X and aperture height for Y; positive Y points down. */
data class MouthCurvePoint(val x: Float, val y: Float)

/** Two cubic Bezier segments with one shared anchor. Ordered X keeps the curve single-valued. */
data class MouthCurve(val points: List<MouthCurvePoint>) {
    init {
        require(points.size == 7)
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in -0.5f..0.5f })
        require(points.first().x == 0f && points.last().x == 1f)
        require(points.zipWithNext().all { (a, b) -> b.x - a.x >= 0.005f })
    }

    fun move(index: Int, x: Float, y: Float): MouthCurve {
        require(index in points.indices)
        val boundedX = when (index) {
            0 -> 0f
            6 -> 1f
            else -> {
                val low = points[index - 1].x + 0.006f
                val high = points[index + 1].x - 0.006f
                if (low <= high) x.coerceIn(low, high) else points[index].x
            }
        }
        return MouthCurve(points.mapIndexed { i, p -> if (i == index) MouthCurvePoint(boundedX, y.coerceIn(-0.5f, 0.5f)) else p })
    }

    fun yAt(x: Float): Float {
        val target = x.coerceIn(0f, 1f)
        val start = if (target <= points[3].x) 0 else 3
        fun component(t: Float, horizontal: Boolean): Float {
            val u = 1f - t
            fun value(i: Int) = points[start + i].let { if (horizontal) it.x else it.y }
            return u * u * u * value(0) + 3f * u * u * t * value(1) +
                3f * u * t * t * value(2) + t * t * t * value(3)
        }
        var low = 0f
        var high = 1f
        repeat(20) {
            val t = (low + high) * 0.5f
            if (component(t, true) < target) low = t else high = t
        }
        return component((low + high) * 0.5f, false)
    }

    companion object {
        val presets = listOf("flat", "smile", "w")
        private val curves = presets.associateWith { name ->
            val ys = when (name) {
                "flat" -> listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)
                "w" -> listOf(0f, 0.23f, 0.13f, 0f, 0.13f, 0.23f, 0f)
                else -> listOf(0f, 0.10f, 0.14f, 0.14f, 0.14f, 0.10f, 0f)
            }
            MouthCurve(ys.mapIndexed { i, y -> MouthCurvePoint(i / 6f, y) })
        }
        fun preset(name: String): MouthCurve = curves[name] ?: curves.getValue("smile")
    }
}
