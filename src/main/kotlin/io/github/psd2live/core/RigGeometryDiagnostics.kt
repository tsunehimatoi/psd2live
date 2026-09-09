package io.github.psd2live.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.hypot

/** Numerical evidence only; no aesthetic pass/fail score. */
internal object RigGeometryDiagnostics {
    fun compare(before: FloatArray, after: FloatArray, triangles: IntArray): JsonObject {
        require(before.size == after.size && before.size % 2 == 0)
        require(before.all(Float::isFinite) && after.all(Float::isFinite))
        require(triangles.size % 3 == 0 && triangles.all { it in 0 until before.size / 2 })
        fun area(p: FloatArray, a: Int, b: Int, c: Int): Double =
            (p[b*2].toDouble()-p[a*2])*(p[c*2+1]-p[a*2+1]) - (p[b*2+1].toDouble()-p[a*2+1])*(p[c*2]-p[a*2])
        val flipped = mutableListOf<Int>(); val collapsed = mutableListOf<Int>()
        var degenerate = 0; var minimum: Double? = null; var maximum: Double? = null
        for(i in triangles.indices step 3) {
            val a=area(before,triangles[i],triangles[i+1],triangles[i+2])
            val b=area(after,triangles[i],triangles[i+1],triangles[i+2])
            if(abs(a) < 1e-12) { degenerate++; continue }
            val ratio=b/a
            minimum=minimum?.let { minOf(it, ratio) } ?: ratio
            maximum=maximum?.let { maxOf(it, ratio) } ?: ratio
            if(ratio < 0) flipped.add(i/3)
            if(abs(ratio) < 0.01) collapsed.add(i/3)
        }
        var maxDistance=0f; var changed=0
        for(i in before.indices step 2) {
            val d=hypot(after[i]-before[i],after[i+1]-before[i+1])
            maxDistance=maxOf(maxDistance,d); if(d>1e-7f)changed++
        }
        return buildJsonObject {
            put("changedPointCount",changed); put("maxLocalDisplacement",maxDistance)
            put("flippedTriangleCount",flipped.size); put("collapsedTriangleCount",collapsed.size)
            put("degenerateReferenceTriangleCount",degenerate)
            put("flippedTriangles",JsonArray(flipped.take(32).map(::JsonPrimitive)))
            put("collapsedTriangles",JsonArray(collapsed.take(32).map(::JsonPrimitive)))
            minimum?.let { put("minSignedAreaRatio",it) }; maximum?.let { put("maxSignedAreaRatio",it) }
            put("scope","Parent-local sampled geometry. Collapse means <1% of reference triangle area. Does not evaluate parent deformation, masks, painted coverage, physics or aesthetics.")
        }
    }
    fun lattice(rows: Int, columns: Int): IntArray = buildList {
        for(r in 0 until rows) for(c in 0 until columns) {
            val a=r*(columns+1)+c; val b=a+1; val d=a+columns+1; val e=d+1
            addAll(listOf(a,b,e,a,e,d))
        }
    }.toIntArray()
}
