package io.github.psd2live.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.hypot

/** Numerical evidence only; no aesthetic pass/fail score. */
internal object RigGeometryDiagnostics {
    internal const val DEGENERATE_AREA_EPSILON = 1e-12
    internal const val COLLAPSE_AREA_RATIO = 0.01

    internal data class TriangleStatus(
        val flippedTriangles: Set<Int>,
        val collapsedTriangles: Set<Int>,
        val degenerateTriangles: Set<Int>,
        val degenerateReferenceTriangles: Set<Int>,
        val minSignedAreaRatio: Double?,
        val maxSignedAreaRatio: Double?,
    )

    internal fun inspect(reference: FloatArray, points: FloatArray, triangles: IntArray): TriangleStatus {
        require(reference.size == points.size && reference.size % 2 == 0)
        require(reference.all(Float::isFinite) && points.all(Float::isFinite))
        require(triangles.size % 3 == 0 && triangles.all { it in 0 until reference.size / 2 })
        fun area(p: FloatArray, a: Int, b: Int, c: Int): Double =
            (p[b*2].toDouble()-p[a*2])*(p[c*2+1]-p[a*2+1]) - (p[b*2+1].toDouble()-p[a*2+1])*(p[c*2]-p[a*2])
        val flipped = mutableSetOf<Int>()
        val collapsed = mutableSetOf<Int>()
        val degenerate = mutableSetOf<Int>()
        val degenerateReference = mutableSetOf<Int>()
        var minimum: Double? = null
        var maximum: Double? = null
        for (i in triangles.indices step 3) {
            val triangle = i / 3
            val a = area(reference, triangles[i], triangles[i + 1], triangles[i + 2])
            val b = area(points, triangles[i], triangles[i + 1], triangles[i + 2])
            if (abs(a) < DEGENERATE_AREA_EPSILON) {
                degenerateReference += triangle
                continue
            }
            val ratio = b / a
            minimum = minimum?.let { minOf(it, ratio) } ?: ratio
            maximum = maximum?.let { maxOf(it, ratio) } ?: ratio
            if (ratio < 0) flipped += triangle
            if (abs(b) < DEGENERATE_AREA_EPSILON) degenerate += triangle
            else if (abs(ratio) < COLLAPSE_AREA_RATIO) collapsed += triangle
        }
        return TriangleStatus(flipped, collapsed, degenerate, degenerateReference, minimum, maximum)
    }

    fun compare(before: FloatArray, after: FloatArray, triangles: IntArray): JsonObject {
        val status = inspect(before, after, triangles)
        var maxDistance=0f; var changed=0
        for(i in before.indices step 2) {
            val d=hypot(after[i]-before[i],after[i+1]-before[i+1])
            maxDistance=maxOf(maxDistance,d); if(d>1e-7f)changed++
        }
        return buildJsonObject {
            put("changedPointCount",changed); put("maxLocalDisplacement",maxDistance)
            put("flippedTriangleCount",status.flippedTriangles.size); put("collapsedTriangleCount",status.collapsedTriangles.size)
            put("degenerateReferenceTriangleCount",status.degenerateReferenceTriangles.size)
            put("flippedTriangles",JsonArray(status.flippedTriangles.take(32).map(::JsonPrimitive)))
            put("collapsedTriangles",JsonArray(status.collapsedTriangles.take(32).map(::JsonPrimitive)))
            status.minSignedAreaRatio?.let { put("minSignedAreaRatio",it) }; status.maxSignedAreaRatio?.let { put("maxSignedAreaRatio",it) }
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
