package io.github.psd2live.core

import kotlin.math.abs

/** The affine map that takes one point set onto another, when one does. */
internal object AffineFit {
    /**
     * `[a, b, c, d, e, f]` with `x' = a·x + c·y + e`, `y' = b·x + d·y + f` taking each point of [before] to the same point
     * of [after] (pairs of x, y), by least squares; null when the points span no area or no affine map fits them within
     * [tolerance] (the points moved apart).
     *
     * The simplest map that explains the move to within [exact] wins: a plain move (`a = d = 1`, `b = c = 0`), then a
     * move and scale along the axes (`b = c = 0`), and only then a general one. Points are floats carried through their
     * parents' spaces, so a least-squares fit of a plain drag comes out with a turn of a few millionths; taken as it is,
     * that would leave a layer the user never turned recorded as turned.
     */
    fun fit(before: FloatArray, after: FloatArray, tolerance: Float = 0.05f, exact: Float = 0.01f): List<Float>? {
        val general = general(before, after, tolerance) ?: return null
        val n = before.size / 2
        var mx = 0.0; var my = 0.0; var mu = 0.0; var mv = 0.0
        for (i in 0 until n) { mx += before[i * 2]; my += before[i * 2 + 1]; mu += after[i * 2]; mv += after[i * 2 + 1] }
        mx /= n; my /= n; mu /= n; mv /= n
        val moved = listOf(1f, 0f, 0f, 1f, (mu - mx).toFloat(), (mv - my).toFloat())
        if (within(moved, before, after, exact)) return moved
        // Each axis on its own: x' = a·x + e, y' = d·y + f.
        var sxx = 0.0; var syy = 0.0; var sxu = 0.0; var syv = 0.0
        for (i in 0 until n) {
            val x = before[i * 2] - mx; val y = before[i * 2 + 1] - my
            sxx += x * x; syy += y * y; sxu += x * (after[i * 2] - mu); syv += y * (after[i * 2 + 1] - mv)
        }
        if (sxx > 1e-9 && syy > 1e-9) {
            val a = sxu / sxx; val d = syv / syy
            val scaled = listOf(a.toFloat(), 0f, 0f, d.toFloat(), (mu - a * mx).toFloat(), (mv - d * my).toFloat())
            if (within(scaled, before, after, exact)) return scaled
        }
        return general
    }

    private fun within(map: List<Float>, before: FloatArray, after: FloatArray, tolerance: Float): Boolean =
        (0 until before.size / 2).all { i ->
            val x = before[i * 2]; val y = before[i * 2 + 1]
            abs(map[0] * x + map[2] * y + map[4] - after[i * 2]) <= tolerance && abs(map[1] * x + map[3] * y + map[5] - after[i * 2 + 1]) <= tolerance
        }

    private fun general(before: FloatArray, after: FloatArray, tolerance: Float): List<Float>? {
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
        return result.takeIf { within(it, before, after, tolerance) }
    }
}
