package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.RasterDigest
import io.github.psd2live.core.recipe.EyePartOverride
import java.security.MessageDigest
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

/**
 * Reads alpha components and their internal branches before binding them to the eye. Uses a bounded working raster
 * for observation only; neither raster resampling nor component extraction changes the source artwork.
 */
object EyeArtworkFitting {
	private const val MAX_SIDE = 512
	private const val STATIONS = 65
	private data class Piece(val layer: ClassifiedLayer, val key: String, val raster: Raster, val pixels: IntArray,
	                         val samples: List<EyePoint>, val side: Side, val hint: EyePartOverride? = null)
	private class Raster(val w: Int, val h: Int, val step: Int, val mask: BooleanArray)

	fun fit(layers: List<ClassifiedLayer>, fits: Map<Side, EyeFit>,
	        overrides: Map<String, Map<String, EyePartOverride>> = emptyMap(),
	        toRig: (Float, Float) -> Pair<Float, Float>): Map<Side, EyeArtwork> {
		if (fits.isEmpty()) return emptyMap()
		val pieces = ArrayList<Piece>()
		for (layer in layers.filter { it.semantic.tag == SemanticTag.EYELASH && it.opaquePixels > 0 }) {
			interrupted()
			if (layer.semantic.side != Side.NONE && layer.semantic.side !in fits) continue
			val raster = raster(layer)
			val src = layer.source
			val stamp = "eye-artwork-v1:${src.raster.width}:${src.raster.height}:${src.bounds}:${RasterDigest.of(src.raster.rgba)}"
			val digest = MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
			for ((index, pixels) in sourceIslands(layer, raster).withIndex()) {
				if (pixels.isEmpty()) continue
				val canvas = pixels.map { p ->
					toRig(layer.source.bounds.left + (p % raster.w + 0.5f) * raster.step,
						layer.source.bounds.top + (p / raster.w + 0.5f) * raster.step)
				}
				val assignments = canvas.indices.groupBy { i ->
					layer.semantic.side.takeIf { it != Side.NONE } ?: fits.minBy { (_, eye) ->
						val (u, v) = eye.toEye(canvas[i].first, canvas[i].second)
						hypot(u - u.coerceIn(0f, 1f), v - (EyeFit.sample(eye.upper, u) + EyeFit.sample(eye.lower, u)) / 2f) * eye.width
					}.key
				}
				for ((assigned, indices) in assignments) {
					val key = "c$index-${assigned.name}-$digest"
					val correction = overrides[layer.source.id.raw]?.get(key)?.takeIf { it.sourceStamp == digest }
					val side = correction?.side ?: assigned
					if (side !in fits) continue
					val eye = fits.getValue(side)
					pieces += Piece(layer, key, raster, indices.map { pixels[it] }.toIntArray(),
						indices.map { i -> eye.toEye(canvas[i].first, canvas[i].second).let { p -> EyePoint(p.first, p.second) } }, side, correction)
				}
			}
		}
		return fits.mapValues { (side, eye) ->
			val members = pieces.filter { it.side == side }
			val ink = members.filter { it.layer.semantic.role !in setOf(PartRole.LID, PartRole.SHADOW) && it.hint?.primitive != EyePrimitive.SKIN }
			fun correctedStroke(region: EyeRegion): EyeStroke {
				val observed = stroke(eye, region, ink)
				val correction = members.asSequence().mapNotNull { piece -> overrides[piece.layer.source.id.raw]?.get(piece.key)
					?.takeIf { it.sourceStamp == piece.key.substringAfterLast('-') && it.region == region && it.path != null } }.lastOrNull()
				val path = correction?.path ?: return observed
				return EyeStroke(region, path, path.map { observed.halfWidth(it.x) }, List(path.size) { true })
			}
			val upper = correctedStroke(EyeRegion.UPPER)
			val lower = correctedStroke(EyeRegion.LOWER)
			val components = members.map { piece ->
				val c = component(eye, piece, upper, lower, toRig)
				val correction = overrides[c.layerId]?.get(c.id)?.takeIf { it.sourceStamp == c.sourceStamp }
				if (correction == null) c else c.copy(region = correction.region ?: c.region,
					primitive = correction.primitive ?: c.primitive, contacts = correction.contacts ?: c.contacts,
					contact = correction.contacts?.firstOrNull() ?: c.contact,
					parts = c.parts.map { p -> p.copy(region = correction.region ?: p.region,
						primitive = correction.primitive ?: p.primitive, contacts = correction.contacts ?: p.contacts) })
			}
			EyeArtwork(side, upper, lower, components, eye.openingLayers)
		}
	}

	private fun raster(layer: ClassifiedLayer): Raster {
		val src = layer.source.raster
		val step = ceil(max(src.width, src.height) / MAX_SIDE.toFloat()).toInt().coerceAtLeast(1)
		val w = (src.width + step - 1) / step; val h = (src.height + step - 1) / step
		val mask = BooleanArray(w * h)
		for (y in 0 until h) {
			interrupted()
			for (x in 0 until w) {
				var opaque = false
				for (sy in y * step until min(src.height, (y + 1) * step)) for (sx in x * step until min(src.width, (x + 1) * step)) {
					if ((src.rgba[(sy * src.width + sx) * 4 + 3].toInt() and 255) >= 96) opaque = true
				}
				mask[y * w + x] = opaque
			}
		}
		return Raster(w, h, step, mask)
	}

	private fun islands(r: Raster): List<IntArray> {
		val visited = BooleanArray(r.mask.size); val queue = IntArray(r.mask.size); val result = ArrayList<IntArray>()
		for (start in r.mask.indices) {
			if (!r.mask[start] || visited[start]) continue
			var head = 0; var tail = 1; queue[0] = start; visited[start] = true
			while (head < tail) {
				val p = queue[head++]; val x = p % r.w; val y = p / r.w
				for (dy in -1..1) for (dx in -1..1) {
					val xx = x + dx; val yy = y + dy
					if (xx !in 0 until r.w || yy !in 0 until r.h) continue
					val q = yy * r.w + xx
					if (r.mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q }
				}
			}
			result += queue.copyOf(tail)
		}
		return result
	}

	/** Connectivity is determined before observation is reduced; neighbouring thin strands must not merge in a cell. */
	private fun sourceIslands(layer: ClassifiedLayer, r: Raster): List<IntArray> {
		if (r.step == 1) return islands(r)
		val src = layer.source.raster
		val visited = BitSet(src.width * src.height)
		val queue = IntArray(src.width * src.height)
		val result = ArrayList<IntArray>()
		fun opaque(p: Int) = (src.rgba[p * 4 + 3].toInt() and 255) >= 96
		for (start in 0 until src.width * src.height) {
			if (start % src.width == 0) interrupted()
			if (visited[start] || !opaque(start)) continue
			var head = 0; var tail = 1; queue[0] = start; visited.set(start)
			val reduced = HashSet<Int>()
			while (head < tail) {
				if (head % 4096 == 0) interrupted()
				val p = queue[head++]; val x = p % src.width; val y = p / src.width
				reduced += (y / r.step) * r.w + x / r.step
				for (dy in -1..1) for (dx in -1..1) {
					val xx = x + dx; val yy = y + dy
					if (xx !in 0 until src.width || yy !in 0 until src.height) continue
					val q = yy * src.width + xx
					if (!visited[q] && opaque(q)) { visited.set(q); queue[tail++] = q }
				}
			}
			if (tail >= 2) result += reduced.sorted().toIntArray()
		}
		return result
	}

	private fun stroke(eye: EyeFit, region: EyeRegion, pieces: List<Piece>): EyeStroke {
		val rim = if (region == EyeRegion.UPPER) eye.upper else eye.lower
		val points = pieces.filter { piece ->
			val p = piece.samples
			val width = p.maxOf { it.x } - p.minOf { it.x }
			val height = p.maxOf { it.y } - p.minOf { it.y }
			(region != EyeRegion.UPPER || piece.layer.semantic.role != PartRole.LOWER) &&
				(piece.hint?.region == null || piece.hint.region == region) &&
				(piece.hint?.primitive == EyePrimitive.LINE || width > 0.4f || width > height * 2f)
		}.flatMap { it.samples }.filter { p ->
			val top = abs(p.y - EyeFit.sample(eye.upper, p.x)); val bottom = abs(p.y - EyeFit.sample(eye.lower, p.x))
			if (region == EyeRegion.UPPER) top <= bottom else bottom <= top
		}
		val aperture = (eye.lower[EyeFit.SAMPLES / 2] - eye.upper[EyeFit.SAMPLES / 2]).coerceAtLeast(0.01f)
		val lo = min(0f, points.minOfOrNull { it.x } ?: 0f).coerceAtLeast(-0.5f)
		val hi = max(1f, points.maxOfOrNull { it.x } ?: 1f).coerceAtMost(1.5f)
		val step = (hi - lo) / (STATIONS - 1)
		val raw = List(STATIONS) { k ->
			val u = lo + k * step; val edge = EyeFit.sample(rim, u)
			val column = points.filter { abs(it.x - u) <= step * 0.6f && abs(it.y - edge) <= aperture * 0.55f }
			if (column.isEmpty()) null else {
				val ys = column.map { it.y }.sorted()
				val runs = ArrayList<List<Float>>(); var run = ArrayList<Float>()
				val gap = max(1.5f / eye.width, step * 0.6f)
				for (v in ys) {
					if (run.isNotEmpty() && v - run.last() > gap) { runs += run; run = ArrayList() }
					run += v
				}
				if (run.isNotEmpty()) runs += run
				runs.minByOrNull { min(abs(it.first() - edge), abs(it.last() - edge)) }?.let {
					EyePoint(u, (it.first() + it.last()) / 2f) to max(0.5f / eye.width, (it.last() - it.first()) / 2f)
				}
			}
		}
		val known = raw.indices.filter { raw[it] != null }
		fun sample(k: Int): Pair<EyePoint, Float> {
			val x = lo + k * step
			if (known.isEmpty()) return EyePoint(x, EyeFit.sample(rim, x)) to 0f
			val a = known.lastOrNull { it <= k } ?: known.first(); val b = known.firstOrNull { it >= k } ?: known.last()
			if ((b - a > 4 && k != a && k != b) || abs(k - a) > 4 || abs(k - b) > 4)
				return EyePoint(x, EyeFit.sample(rim, x)) to 0f
			val t = if (a == b) 0f else (k - a).toFloat() / (b - a)
			val pa = raw[a]!!; val pb = raw[b]!!
			return EyePoint(x, pa.first.y + (pb.first.y - pa.first.y) * t) to (pa.second + (pb.second - pa.second) * t)
		}
		val sampled = raw.indices.map(::sample)
		return EyeStroke(region, sampled.map { it.first }, sampled.map { it.second }, raw.map { it != null })
	}

	private fun component(eye: EyeFit, piece: Piece, upper: EyeStroke, lower: EyeStroke,
	                      toRig: (Float, Float) -> Pair<Float, Float>, split: Boolean = true): EyeComponent {
		val p = piece.samples
		val centre = EyePoint(p.map { it.x }.average().toFloat(), p.map { it.y }.average().toFloat())
		val topDistance = p.map { abs(it.y - upper.at(it.x).y) }.sorted()
		val lowDistance = p.map { abs(it.y - lower.at(it.x).y) }.sorted()
		val region = if (piece.layer.semantic.role == PartRole.LOWER) EyeRegion.LOWER else
			if (lowDistance[lowDistance.size / 4] < topDistance[topDistance.size / 4]) EyeRegion.LOWER else EyeRegion.UPPER
		val stroke = if (region == EyeRegion.UPPER) upper else lower
		val contactPoints = p.filter { stroke.isObserved(it.x) &&
			abs(it.y - stroke.at(it.x).y) <= max(stroke.halfWidth(it.x), 1.5f / eye.width) }
		val contactSet = contactPoints.ifEmpty { p.sortedBy { abs(it.y - stroke.at(it.x).y) }.take(max(1, p.size / 10)) }
		fun contact(points: List<EyePoint>) = EyeContact(region, points.minOf { it.x }, points.maxOf { it.x },
			EyePoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat()))
		val groups = ArrayList<List<EyePoint>>(); var group = ArrayList<EyePoint>()
		val contactGap = max(3f / eye.width, (stroke.points[1].x - stroke.points[0].x) * 2f)
		for (q in contactPoints.sortedBy { it.x }) {
			if (group.isNotEmpty() && q.x - group.last().x > contactGap) { groups += group; group = ArrayList() }
			group += q
		}
		if (group.isNotEmpty()) groups += group
		val contacts = groups.map(::contact)
		val contact = groups.maxByOrNull { it.size }?.let(::contact) ?: contact(contactSet)
		val span = p.maxOf { it.x } - p.minOf { it.x }
		val primitive = when {
			piece.layer.semantic.role == PartRole.LID || piece.layer.semantic.role == PartRole.SHADOW -> EyePrimitive.SKIN
			contactPoints.isEmpty() -> EyePrimitive.AUTO
			span > 0.45f && contact.to - contact.from > span * 0.65f -> EyePrimitive.LINE
			centre.x > 0.78f && p.maxOf { it.y } - p.minOf { it.y } > span -> EyePrimitive.SIDE
			else -> EyePrimitive.TUFT
		}
		val branches = if (primitive == EyePrimitive.SKIN) emptyList() else branches(piece, eye, stroke, contact, toRig)
		val observation = EyeComponent(piece.key, piece.layer.source.id.raw, piece.side, region, primitive, p, centre, contact, branches,
			if (contactPoints.isEmpty() || !stroke.observed.any { it }) 0.3f else 0.9f,
			piece.key.substringAfterLast('-'), contacts)
		fun EyeComponent.part() = EyeMaterialPart(id, this.region, this.primitive, samples, this.centre, this.contacts, this.branches)
		if (!split || primitive == EyePrimitive.SKIN || piece.layer.semantic.role == PartRole.LOWER)
			return observation.copy(parts = listOf(observation.part()))
		val groupsByRim = p.indices.groupBy { i ->
			val q = p[i]
			if (abs(q.y - upper.at(q.x).y) <= abs(q.y - lower.at(q.x).y)) EyeRegion.UPPER else EyeRegion.LOWER
		}
		// A descending upper corner can lie nearer the white's lower rim. It becomes lower material only when
		// a horizontal lower stroke is actually observed and this island carries a substantial run along it.
		val lowerRun = groupsByRim[EyeRegion.LOWER].orEmpty().map { p[it] }
		val lowerStations = lower.points.indices.count { lower.observed[it] && lower.points[it].x in 0.15f..0.85f }
		val lowerSpan = if (lowerRun.isEmpty()) 0f else lowerRun.maxOf { it.x } - lowerRun.minOf { it.x }
		val hasBoth = groupsByRim.size == 2 && lowerStations >= 6 && lowerSpan > 0.25f &&
			groupsByRim.values.all { it.size >= max(3, p.size / 20) }
		if (!hasBoth) return observation.copy(parts = listOf(observation.part()))
		val parts = groupsByRim.map { (rim, indices) ->
			val sub = piece.copy(key = "${piece.key}:${rim.name.lowercase()}",
				pixels = indices.map { piece.pixels[it] }.toIntArray(), samples = indices.map { p[it] })
			component(eye, sub, upper, lower, toRig, split = false).part().copy(region = rim)
		}
		return observation.copy(parts = parts)
	}

	/** Trace branches inside each material island. Separate layers are never joined merely because they are close. */
	private fun branches(piece: Piece, eye: EyeFit, stroke: EyeStroke, contact: EyeContact,
	                     toRig: (Float, Float) -> Pair<Float, Float>): List<EyeBranch> {
		val r = piece.raster; val skeleton = BooleanArray(r.mask.size)
		for (p in piece.pixels) skeleton[p] = true
		fun on(x: Int, y: Int) = x in 0 until r.w && y in 0 until r.h && skeleton[y * r.w + x]
		val removed = IntArray(piece.pixels.size)
		var changed = true
		while (changed) {
			interrupted(); changed = false
			for (phase in 0..1) {
				var count = 0
				for (p in piece.pixels) {
					if (!skeleton[p]) continue
					val x = p % r.w; val y = p / r.w
					val n = booleanArrayOf(on(x, y - 1), on(x + 1, y - 1), on(x + 1, y), on(x + 1, y + 1),
						on(x, y + 1), on(x - 1, y + 1), on(x - 1, y), on(x - 1, y - 1))
					val neighbours = n.count { it }; val transitions = n.indices.count { !n[it] && n[(it + 1) % 8] }
					val keep = if (phase == 0) n[0] && n[2] && n[4] || n[2] && n[4] && n[6]
						else n[0] && n[2] && n[6] || n[0] && n[4] && n[6]
					if (neighbours in 2..6 && transitions == 1 && !keep) removed[count++] = p
				}
				for (i in 0 until count) skeleton[removed[i]] = false
				if (count > 0) changed = true
			}
		}
		fun neighbours(p: Int) = buildList {
			val x = p % r.w; val y = p / r.w
			for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && on(x + dx, y + dy)) add((y + dy) * r.w + x + dx)
		}
		fun point(p: Int): EyePoint = toRig(piece.layer.source.bounds.left + (p % r.w + 0.5f) * r.step,
			piece.layer.source.bounds.top + (p / r.w + 0.5f) * r.step).let { eye.toEye(it.first, it.second).let { q -> EyePoint(q.first, q.second) } }
		val endpoints = piece.pixels.filter { skeleton[it] && neighbours(it).size == 1 }
		val root = piece.pixels.filter { skeleton[it] }.minByOrNull {
			val q = point(it)
			abs(q.y - stroke.at(q.x).y)
		} ?: return emptyList()
		// A digital junction can occupy several pixels. Trace through that cluster instead of treating its first
		// pixel as the root of every branch; otherwise short fork arms disappear at diagonal neighbours.
		val parents = IntArray(skeleton.size) { -1 }
		val queue = IntArray(piece.pixels.size)
		var head = 0; var tail = 1; queue[0] = root; parents[root] = root
		while (head < tail) {
			val p = queue[head++]
			for (q in neighbours(p)) if (parents[q] < 0) { parents[q] = p; queue[tail++] = q }
		}
		val result = ArrayList<EyeBranch>()
		for (tip in endpoints) {
			if (tip == root || parents[tip] < 0) continue
			val tipPoint = point(tip)
			if (abs(tipPoint.y - stroke.at(tipPoint.x).y) <= stroke.halfWidth(tipPoint.x)) continue
			val chain = ArrayList<Int>(); var cursor = tip
			while (true) {
				chain += cursor
				val q = point(cursor)
				if (abs(q.y - stroke.at(q.x).y) <= stroke.halfWidth(q.x) && chain.size > 1) break
				if (cursor == root) break
				cursor = parents[cursor]
			}
			val q = chain.map(::point)
			if (q.size >= 2 && q.first().distance(q.last()) > 1.5f / eye.width)
				result += EyeBranch(q.last(), q.first(), q.asReversed())
		}
		return result
	}

	private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Eye artwork analysis interrupted") }
}
