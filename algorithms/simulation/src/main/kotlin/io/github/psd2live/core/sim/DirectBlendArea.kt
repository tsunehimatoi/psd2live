package io.github.psd2live.core.sim

/** Finds the least signed area of a triangle over a pair of linear keyform intervals. */
internal object DirectBlendArea {
    class Minimum {
        var u = 0f
            private set
        var v = 0f
            private set
        val value: Float get() = best.toFloat()

        private var best = 0.0

        internal fun reset(value: Double) {
            u = 0f
            v = 0f
            best = value
        }

        internal fun consider(u: Double, v: Double, value: Double) {
            if (value >= best) return
            this.u = u.toFloat()
            this.v = v.toFloat()
            best = value
        }
    }

    /** Minimizes c00 + c10*u + c01*v + c20*u² + c11*u*v + c02*v² on [0, 1] × [0, 1]. */
    fun minimum(
        c00: Float,
        c10: Float,
        c01: Float,
        c20: Float,
        c11: Float,
        c02: Float,
        result: Minimum = Minimum(),
    ): Minimum {
        val a = c00.toDouble()
        val b = c10.toDouble()
        val c = c01.toDouble()
        val d = c20.toDouble()
        val e = c11.toDouble()
        val f = c02.toDouble()
        result.reset(a)
        result.consider(1.0, 0.0, a + b + d)
        result.consider(0.0, 1.0, a + c + f)
        result.consider(1.0, 1.0, a + b + c + d + e + f)

        // A convex edge can dip between its two corners.
        if (d > 0.0) {
            val bottom = -b / (2.0 * d)
            if (bottom > 0.0 && bottom < 1.0) result.consider(bottom, 0.0, a + (b + d * bottom) * bottom)
            val top = -(b + e) / (2.0 * d)
            if (top > 0.0 && top < 1.0) result.consider(top, 1.0, a + c + f + (b + e + d * top) * top)
        }
        if (f > 0.0) {
            val left = -c / (2.0 * f)
            if (left > 0.0 && left < 1.0) result.consider(0.0, left, a + (c + f * left) * left)
            val right = -(c + e) / (2.0 * f)
            if (right > 0.0 && right < 1.0) result.consider(1.0, right, a + b + d + (c + e + f * right) * right)
        }

        // A singular valley reaches the boundary, and an indefinite Hessian has no interior minimum.
        val determinant = 4.0 * d * f - e * e
        if (d > 0.0 && determinant > 0.0) {
            val u = (e * c - 2.0 * f * b) / determinant
            val v = (e * b - 2.0 * d * c) / determinant
            if (u > 0.0 && u < 1.0 && v > 0.0 && v < 1.0) {
                result.consider(u, v, a + (b + d * u + e * v) * u + (c + f * v) * v)
            }
        }
        return result
    }
}
