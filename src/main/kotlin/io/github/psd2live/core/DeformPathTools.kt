package io.github.psd2live.core

import org.umamo.runtime.model.*
import kotlin.math.*

/** Triangle binding and smooth rigid moving-least-squares deformation, in parent-local units. */
object DeformPathTools {
    fun bind(vertices: FloatArray, indices: IntArray, x: Float, y: Float, corner: Boolean = false): DeformPathPoint {
        require(x.isFinite() && y.isFinite())
        var best: DeformPathPoint? = null
        var score = Float.POSITIVE_INFINITY
        for (i in indices.indices step 3) {
            val a=indices[i]; val b=indices[i+1]; val c=indices[i+2]
            val ax=vertices[a*2]; val ay=vertices[a*2+1]
            val bx=vertices[b*2]; val by=vertices[b*2+1]
            val cx=vertices[c*2]; val cy=vertices[c*2+1]
            val det=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
            if (abs(det)<1e-12f) continue
            val wa=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/det
            val wb=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/det
            val wc=1f-wa-wb
            val outside=max(0f,-wa)+max(0f,-wb)+max(0f,-wc)
            if (outside<score) { score=outside; best=DeformPathPoint(a,b,c,wa,wb,wc,corner) }
            if (outside == 0f) break
        }
        return requireNotNull(best) { "A non-degenerate triangle is required to attach a path" }
    }

    fun positions(path: DeformPath, vertices: FloatArray): List<Pair<Float, Float>> = path.points.map { it.position(vertices) }

    /** Catmull-Rom interpolation passes through handles; corner handles use linear segments. */
    fun curve(points: List<Pair<Float, Float>>, corners: List<Boolean>, closed: Boolean): List<Pair<Float, Float>> {
        require(points.size >= 2 && corners.size == points.size)
        fun p(i: Int) = points[if (closed) (i+points.size)%points.size else i.coerceIn(points.indices)]
        return buildList {
            for (i in 0 until if (closed) points.size else points.size-1) {
                val a=p(i-1); val b=p(i); val c=p(i+1); val d=p(i+2)
                for (j in 0 until 8) {
                    val t=j/8f
                    fun axis(a: Float,b: Float,c: Float,d: Float): Float =
                        if(corners[i] || corners[(i+1)%points.size]) b+(c-b)*t
                        else 0.5f*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t)
                    add(axis(a.first,b.first,c.first,d.first) to axis(a.second,b.second,c.second,d.second))
                }
            }
            add(if(closed) points.first() else points.last())
        }
    }

    fun deform(vertices: FloatArray, paths: List<DeformPath>, pathId: String, moved: List<Pair<Float, Float>>): FloatArray =
        deformAll(vertices, paths, mapOf(pathId to moved))

    fun deformAll(vertices: FloatArray, paths: List<DeformPath>, movedByPathId: Map<String, List<Pair<Float, Float>>>): FloatArray {
        if (movedByPathId.isEmpty()) return vertices.copyOf()
        val active = paths.firstOrNull { it.id in movedByPathId } ?: return vertices.copyOf()
        for ((pId, moved) in movedByPathId) {
            val p = paths.firstOrNull { it.id == pId } ?: continue
            require(moved.size == p.points.size && moved.all { it.first.isFinite() && it.second.isFinite() })
        }
        val source = ArrayList<Pair<Float, Float>>(); val dest = ArrayList<Pair<Float, Float>>()
        val widths = ArrayList<Float>(); val hardness = ArrayList<Float>()
        for (path in paths.filter { it.drawableId == active.drawableId && it.editLevel == active.editLevel }) {
            val original = positions(path, vertices)
            val corners = path.points.map { it.corner }
            val moved = movedByPathId[path.id]
            val a = curve(original, corners, path.closed)
            val b = curve(moved ?: original, corners, path.closed)
            source.addAll(a); dest.addAll(b)
            repeat(a.size) { widths.add(path.width); hardness.add(path.hardness) }
        }
        if (source == dest) return vertices.copyOf()
        val result = vertices.copyOf()
        for (v in vertices.indices step 2) {
            val x = vertices[v].toDouble(); val y = vertices[v + 1].toDouble()
            val exact = source.indexOfFirst { hypot(x - it.first, y - it.second) < 1e-9 }
            if (exact >= 0) { result[v] = dest[exact].first; result[v + 1] = dest[exact].second; continue }
            val w = DoubleArray(source.size) { i ->
                val distance = hypot(x - source[i].first, y - source[i].second)
                val radius = widths[i].toDouble().coerceAtLeast(1e-9)
                1.0 / (distance * distance + (radius * (0.02 + hardness[i])).pow(2) * 0.05 + 1e-20)
            }
            val sum = w.sum()
            var px = 0.0; var py = 0.0; var qx = 0.0; var qy = 0.0
            for (i in w.indices) { val f = w[i] / sum; px += source[i].first * f; py += source[i].second * f; qx += dest[i].first * f; qy += dest[i].second * f }
            var dot = 0.0; var cross = 0.0
            for (i in w.indices) {
                val ax = source[i].first - px; val ay = source[i].second - py
                val bx = dest[i].first - qx; val by = dest[i].second - qy
                dot += w[i] * (ax * bx + ay * by); cross += w[i] * (ax * by - ay * bx)
            }
            val norm = hypot(dot, cross)
            val co = if (norm < 1e-20) 1.0 else dot / norm; val si = if (norm < 1e-20) 0.0 else cross / norm
            result[v] = (qx + co * (x - px) - si * (y - py)).toFloat()
            result[v + 1] = (qy + si * (x - px) + co * (y - py)).toFloat()
        }
        // Handles live inside triangles, not necessarily at mesh vertices. Project the mesh back
        // onto those barycentric constraints so handles do not jump when the drag is committed.
        val rawConstraints = paths.filter { it.drawableId == active.drawableId && it.editLevel == active.editLevel }.flatMap { path ->
            val targets = movedByPathId[path.id] ?: positions(path, vertices)
            path.points.zip(targets)
        }
        val constraints = rawConstraints.distinctBy { it.first }
        val magnitude = vertices.maxOrNull()!!.toDouble() - vertices.minOrNull()!!.toDouble()
        val tolerance = max(1e-7, magnitude * 1e-5)
        repeat(100) {
            var error = 0.0
            for ((p, target) in constraints) {
                val actual = p.position(result)
                val dx = (target.first - actual.first).toDouble(); val dy = (target.second - actual.second).toDouble()
                error = max(error, hypot(dx, dy))
                val sum = (p.wa * p.wa + p.wb * p.wb + p.wc * p.wc).toDouble()
                for ((i, w) in listOf(p.a to p.wa, p.b to p.wb, p.c to p.wc)) {
                    result[i * 2] += (dx * w / sum).toFloat(); result[i * 2 + 1] += (dy * w / sum).toFloat()
                }
            }
            if (error < tolerance) {
                require(result.all(Float::isFinite)) { "Deformation overflow" }
                return result
            }
        }
        if (constraints.all { (p, target) -> val actual = p.position(result); hypot((actual.first - target.first).toDouble(), (actual.second - target.second).toDouble()) < tolerance * 16 }) {
            require(result.all(Float::isFinite)) { "Deformation overflow" }
            return result
        }
        require(result.all(Float::isFinite)) { "Deformation overflow" }
        return result
    }
}
