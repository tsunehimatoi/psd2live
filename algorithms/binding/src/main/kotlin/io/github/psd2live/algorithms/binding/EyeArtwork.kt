package io.github.psd2live.algorithms.binding

import kotlin.math.abs
import kotlin.math.hypot

/** Position and drawing function are separate: a lower component may itself contain a line and several tufts. */
enum class EyeRegion { UPPER, LOWER }
enum class EyePrimitive { LINE, TUFT, SIDE, SKIN, AUTO }

data class EyePoint(val x: Float, val y: Float) {
	operator fun plus(p: EyePoint) = EyePoint(x + p.x, y + p.y)
	operator fun minus(p: EyePoint) = EyePoint(x - p.x, y - p.y)
	operator fun times(s: Float) = EyePoint(x * s, y * s)
	fun distance(p: EyePoint) = hypot(x - p.x, y - p.y)
}

/** An observed stroke in eye-local coordinates. Missing stations are reported, not silently labelled as observed. */
data class EyeStroke(
	val region: EyeRegion, val points: List<EyePoint>, val halfWidths: List<Float>, val observed: List<Boolean>,
) {
	init { require(points.size >= 2 && points.size == halfWidths.size && points.size == observed.size) }
	fun at(x: Float): EyePoint {
		val i = points.indexOfFirst { it.x >= x }
		if (i == 0) return points.first()
		if (i < 0) return points.last()
		val a = points[i - 1]; val b = points[i]
		return a + (b - a) * ((x - a.x) / (b.x - a.x).coerceAtLeast(1e-8f))
	}
	fun halfWidth(x: Float): Float {
		val i = points.indexOfFirst { it.x >= x }
		if (i == 0) return halfWidths.first()
		if (i < 0) return halfWidths.last()
		val t = ((x - points[i - 1].x) / (points[i].x - points[i - 1].x).coerceAtLeast(1e-8f)).coerceIn(0f, 1f)
		return halfWidths[i - 1] + (halfWidths[i] - halfWidths[i - 1]) * t
	}
	fun tangent(x: Float): EyePoint {
		val i = points.indexOfFirst { it.x >= x }.let { if (it < 1) if (it < 0) points.lastIndex else 1 else it }
		val d = points[i] - points[i - 1]
		return d * (1f / hypot(d.x, d.y).coerceAtLeast(1e-8f))
	}
	fun isObserved(x: Float): Boolean {
		if (x < points.first().x || x > points.last().x) return false
		return observed[points.indices.minBy { abs(points[it].x - x) }]
	}
}

/** An overlap interval, rather than a pin to one material point of another layer. */
data class EyeContact(val region: EyeRegion, val from: Float, val to: Float, val centre: EyePoint)
data class EyeBranch(val root: EyePoint, val tip: EyePoint, val points: List<EyePoint>)
data class EyeMaterialPart(
	val id: String, val region: EyeRegion, val primitive: EyePrimitive, val samples: List<EyePoint>,
	val centre: EyePoint, val contacts: List<EyeContact>, val branches: List<EyeBranch>,
)

/** A connected piece of source material; branches retain their own geometry even when drawn on the same layer. */
data class EyeComponent(
	val id: String, val layerId: String, val side: Side, val region: EyeRegion, val primitive: EyePrimitive,
	val samples: List<EyePoint>, val centre: EyePoint, val contact: EyeContact,
	val branches: List<EyeBranch>, val confidence: Float,
	val sourceStamp: String = "",
	val contacts: List<EyeContact> = listOf(contact),
	val parts: List<EyeMaterialPart> = emptyList(),
)

/** Pure, rebuildable observation. Original layers, masks, meshes and pixels remain separate. */
data class EyeArtwork(
	val side: Side, val upper: EyeStroke, val lower: EyeStroke,
	val components: List<EyeComponent>, val openingLayers: Set<String>,
) {
	fun stroke(region: EyeRegion) = if (region == EyeRegion.UPPER) upper else lower
	fun components(layerId: String) = components.filter { it.layerId == layerId }
	// Source stamps account for the pixel samples. Geometry-stage cache keys need not walk every opaque pixel again.
	private val signature by lazy { listOf(side, upper, lower, openingLayers, components.map { c ->
		listOf(c.id, c.layerId, c.side, c.region, c.primitive, c.contacts, c.branches, c.confidence,
			c.parts.map { p -> listOf(p.id, p.region, p.primitive, p.contacts, p.branches) })
	}) }
	override fun equals(other: Any?) = other === this || other is EyeArtwork && signature == other.signature
	override fun hashCode() = signature.hashCode()
}
