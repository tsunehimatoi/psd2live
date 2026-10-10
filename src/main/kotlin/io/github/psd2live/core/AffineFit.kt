package io.github.psd2live.core

import kotlin.math.abs

/** The affine map that takes one point set onto another, when one does. */
internal object AffineFit {
    /**
     * `[a, b, c, d, e, f]` with `x' = a·x + c·y + e`, `y' = b·x + d·y + f` taking each point of [before] to the same point
     * of [after] (pairs of x, y), by least squares; null when the points span no area or no affine map fits them within
     * [tolerance] (the points moved apart).
     */
    fun fit(before: FloatArray, after: FloatArray, tolerance: Float = 0.05f): List<Float>? {
        if (before.size != after.size || before.size < 6 || before.size % 2 != 0) return null
        val n = before.size / 2
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += before[i * 2]; my += before[i * 2 + 1] }
        mx /= n; my /= n
        // Normal equations on centred points: [sxx sxy; sxy syy] [p q] = [sx' sy'].
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        var ux = 0.0; var vx = 0.0; var uy = 0.0; var vy = 0.0; var mu = 0.0; var mv = 0.0
        for (i in 0 until n) { mu += after[i * 2]; mv += after[i * 2 + 1] }
        mu /= n; mv /= n
        for (i in 0 until n) {
            val x = before[i * 2] - mx; val y = before[i * 2 + 1] - my
            val u = after[i * 2] - mu; val v = after[i * 2 + 1] - mv
            sxx += x * x; sxy += x * y; syy += y * y
            ux += u * x; uy += u * y; vx += v * x; vy += v * y
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-9 * (sxx + syy).coerceAtLeast(1.0)) return null
        val a = (ux * syy - uy * sxy) / det; val c = (uy * sxx - ux * sxy) / det
        val b = (vx * syy - vy * sxy) / det; val d = (vy * sxx - vx * sxy) / det
        val e = mu - a * mx - c * my; val f = mv - b * mx - d * my
        val result = listOf(a, b, c, d, e, f).map { it.toFloat() }
        for (i in 0 until n) {
            val x = before[i * 2]; val y = before[i * 2 + 1]
            if (abs(result[0] * x + result[2] * y + result[4] - after[i * 2]) > tolerance ||
                abs(result[1] * x + result[3] * y + result[5] - after[i * 2 + 1]) > tolerance) return null
        }
        return result
    }
}
