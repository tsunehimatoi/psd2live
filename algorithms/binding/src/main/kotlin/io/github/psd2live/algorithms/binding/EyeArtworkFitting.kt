package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceInputs
import io.github.psd2live.core.modifier.ModifierSourceLayer
import java.util.BitSet
import kotlin.math.*

/**
 * Reads alpha components and their internal branches before binding them to the eye. Uses a bounded working raster
 * for observation only; neither raster resampling nor component extraction changes the source artwork.
 */
object EyeArtworkFitting {
	private const val MAX_SIDE = 512
	private const val STATIONS = 65
	private data class Piece(val layer: ModifierSourceLayer, val key: String, val raster: Raster, val pixels: IntArray,
	                         val samples: List<EyePoint>, val side: Side, val hint: EyePartOverride? = null)
	private class Raster(val w: Int, val h: Int, val step: Int, val mask: BooleanArray)

	fun fit(inputs: ModifierSourceInputs, sourceStamps: Map<String, String>, fits: Map<Side, EyeFit>,
	        overrides: Map<String, Map<String, EyePartOverride>> = emptyMap(),
	        toRig: (Float, Float) -> Pair<Float, Float>): Map<Side, EyeArtwork> {
		if (fits.isEmpty()) return emptyMap()
		val pieces = ArrayList<Piece>()
		for (layer in inputs.layers.filter { it.tag == "EYELASH" && it.opaquePixels > 0 }) {
			interrupted()
			if (Side.valueOf(layer.side) != Side.NONE && Side.valueOf(layer.side) !in fits) continue
			val raster = raster(inputs.raster(layer))
			val digest = sourceStamps.getValue(layer.id)
			for ((index, pixels) in sourceIslands(inputs.raster(layer), raster).withIndex()) {
				if (pixels.isEmpty()) continue
				val canvas = pixels.map { p ->
					toRig(layer.left + (p % raster.w + 0.5f) * raster.step,
						layer.top + (p / raster.w + 0.5f) * raster.step)
				}
				val assignments = canvas.indices.groupBy { i ->
					Side.valueOf(layer.side).takeIf { it != Side.NONE } ?: fits.minBy { (_, eye) ->
						val (u, v) = eye.toEye(canvas[i].first, canvas[i].second)
						hypot(u - u.coerceIn(0f, 1f), v - (EyeFit.sample(eye.upper, u) + EyeFit.sample(eye.lower, u)) / 2f) * eye.width
					}.key
				}
				for ((assigned, indices) in assignments) {
					val key = "c$index-${assigned.name}-$digest"
					val correction = overrides[layer.id]?.get(key)?.takeIf { it.sourceStamp == digest }
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
			val ink = members.filter { it.layer.role !in setOf("LID", "SHADOW") && it.hint?.primitive != EyePrimitive.SKIN }
			fun correctedStroke(region: EyeRegion): EyeStroke {
				val observed = stroke(eye, region, ink)
				val correction = members.asSequence().mapNotNull { piece -> overrides[piece.layer.id]?.get(piece.key)
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

	private fun raster(src: ModifierSourceInputs.Raster): Raster {
		val step = ceil(max(src.width, src.height) / MAX_SIDE.toFloat()).toInt().coerceAtLeast(1)
		val w = (src.width + step - 1) / step; val h = (src.height + step - 1) / step
		val mask = BooleanArray(w * h)
		for (y in 0 until h) {
			interrupted()
			for (x in 0 until w) {
				var opaque = false
				for (sy in y * step until min(src.height, (y + 1) * step)) for (sx in x * step until min(src.width, (x + 1) * step)) {
					if (src.alpha(sx, sy) >= 96) opaque = true
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
	private fun sourceIslands(src: ModifierSourceInputs.Raster, r: Raster): List<IntArray> {
		if (r.step == 1) return islands(r)
		val visited = BitSet(src.width * src.height)
		val queue = IntArray(src.width * src.height)
		val result = ArrayList<IntArray>()
		fun opaque(p: Int) = src.alpha(p % src.width, p / src.width) >= 96
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
			(region != EyeRegion.UPPER || piece.layer.role != "LOWER") &&
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
		val region = if (piece.layer.role == "LOWER") EyeRegion.LOWER else
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
			piece.layer.role == "LID" || piece.layer.role == "SHADOW" -> EyePrimitive.SKIN
			contactPoints.isEmpty() -> EyePrimitive.AUTO
			span > 0.45f && contact.to - contact.from > span * 0.65f -> EyePrimitive.LINE
			centre.x > 0.78f && p.maxOf { it.y } - p.minOf { it.y } > span -> EyePrimitive.SIDE
			else -> EyePrimitive.TUFT
		}
		val branches = if (primitive == EyePrimitive.SKIN) emptyList() else branches(piece, eye, stroke, contact, toRig)
		val observation = EyeComponent(piece.key, piece.layer.id, piece.side, region, primitive, p, centre, contact, branches,
			if (contactPoints.isEmpty() || !stroke.observed.any { it }) 0.3f else 0.9f,
			piece.key.substringAfterLast('-'), contacts)
		fun EyeComponent.part() = EyeMaterialPart(id, this.region, this.primitive, samples, this.centre, this.contacts, this.branches)
		if (!split || primitive == EyePrimitive.SKIN || piece.layer.role == "LOWER")
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
		fun point(p: Int): EyePoint = toRig(piece.layer.left + (p % r.w + 0.5f) * r.step,
			piece.layer.top + (p / r.w + 0.5f) * r.step).let { eye.toEye(it.first, it.second).let { q -> EyePoint(q.first, q.second) } }
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
