package io.github.psd2live.core

import kotlinx.serialization.json.*

/** Lossless transport of one gesture. Chunks never apply the brush separately or create undo steps. */
internal object CanvasWeightStroke {
    const val POINTS_PER_BATCH = 4096
    const val MAX_BATCHES = 256

    fun encode(points: List<CanvasBrushPoint>): Pair<String, JsonArray> {
        require(points.isNotEmpty()) { "A stroke needs points" }
        fun point(p: CanvasBrushPoint) = JsonArray(listOf(JsonPrimitive(p.x), JsonPrimitive(p.y)))
        return if (points.size <= POINTS_PER_BATCH) "points" to JsonArray(points.map(::point))
        else "point_batches" to JsonArray(points.chunked(POINTS_PER_BATCH).map { batch -> JsonArray(batch.map(::point)) })
    }

    /** Connect chunk boundaries exactly as within a chunk, including a one-point dab. */
    fun segments(request: JsonObject): Sequence<Pair<CanvasBrushPoint, CanvasBrushPoint>> = sequence {
        val batches = request["points"]?.let { sequenceOf(it.jsonArray) }
            ?: request.getValue("point_batches").jsonArray.asSequence().map { it.jsonArray }
        var previous: CanvasBrushPoint? = null
        var hasSegment = false
        for (batch in batches) for (raw in batch) {
            val values = raw.jsonArray
            val next = CanvasBrushPoint(values[0].jsonPrimitive.float, values[1].jsonPrimitive.float)
            require(next.x.isFinite() && next.y.isFinite()) { "Stroke coordinates must be finite canvas pixels" }
            if (previous != null) {
                yield(previous to next)
                hasSegment = true
            }
            previous = next
        }
        if (!hasSegment && previous != null) yield(previous to previous)
    }
}
