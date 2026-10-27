package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A feature point of the head read off the art, in the rig's aligned space: [id] names it (`face.chin`, `eye.L.outer`),
 * [part] the feature it belongs to. A [later] point is not used by the head's turn yet; it is kept for what will need it
 * (a closing eye's lashes, an opening mouth's skin).
 */
data class Landmark(val id: String, val part: String, val x: Float, val y: Float, val later: Boolean = false)

/**
 * The head's feature points ([points]), the face skin's outline in order round it ([contour], rig space, empty when the
 * art has no face layer) and distances that are not points ([measures], rig units).
 */
class FaceLandmarks(val points: List<Landmark>, val contour: List<Pair<Float, Float>>, val measures: Map<String, Float>) {
	private val byId = points.associateBy { it.id }
	operator fun get(id: String): Landmark? = byId[id]
}

/**
 * Reads [FaceLandmarks] off the classified layers and the eyes', brows' and mouth's fits. Every point is found on the
 * art itself - a layer's opaque pixels, the face's outline and its curvature - never at a template's place, so a square
 * face's jaw angles stay where its corners are and a pointed chin's tip where its point is.
 *
 * The face's outline is the skin layer's farthest opaque pixel along rays from a point between the eyes and the mouth,
 * so holes cut in the skin (the eyes, the mouth) do not count. On it, by curvature over a window of its length:
 * - the chin: its lowest point; a flat chin's bottom edge gives two chin corners and its middle;
 * - each side's cheekbone: its widest point between the eye line and the mouth;
 * - each jaw angle: the sharpest outward bend between the cheekbone and the chin;
 * - each cheek-to-jaw turn: the flattest or most inward bend between the cheekbone and the jaw angle;
 * - each temple: the sharpest outward bend between the forehead's top and the cheekbone;
 * - the forehead's top: its highest point.
 */
object FaceLandmarking {
	private const val ALPHA = 96

	private class Pixels(val xs: FloatArray, val ys: FloatArray) {
		val size get() = xs.size
		fun isEmpty() = xs.isEmpty()
	}

	/** [layer]'s opaque pixels' canvas centres. */
	private fun pixels(layer: ClassifiedLayer): Pixels {
		val raster = layer.source.raster
		val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
		for (y in 0 until raster.height) for (x in 0 until raster.width) {
			if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < ALPHA) continue
			xs += layer.source.bounds.left + x + 0.5f; ys += layer.source.bounds.top + y + 0.5f
		}
		return Pixels(xs.toFloatArray(), ys.toFloatArray())
	}

	private fun alpha(layer: ClassifiedLayer, x: Float, y: Float): Boolean {
		val raster = layer.source.raster
		val px = (x - layer.source.bounds.left).toInt(); val py = (y - layer.source.bounds.top).toInt()
		if (px < 0 || py < 0 || px >= raster.width || py >= raster.height) return false
		return (raster.rgba[(py * raster.width + px) * 4 + 3].toInt() and 0xff) >= ALPHA
	}

	/**
	 * The feature points of [layers]' head. [toRig] carries canvas points into the rig's aligned space; [eyes], [brows]
	 * and [mouth] are the fits already read (any may be missing); [faceCenterX] is the canvas middle of the face.
	 */
	fun read(layers: List<ClassifiedLayer>, eyes: EyeFitting.Result?, brows: Map<Side, BrowFit>, mouth: MouthFit?, faceCenterX: Float,
	         toRig: (Float, Float) -> Pair<Float, Float>): FaceLandmarks {
		val out = ArrayList<Landmark>()
		val measures = LinkedHashMap<String, Float>()
		fun add(id: String, part: String, p: Pair<Float, Float>, later: Boolean = false) { out += Landmark(id, part, p.first, p.second, later) }
		fun addCanvas(id: String, part: String, x: Float, y: Float, later: Boolean = false) = add(id, part, toRig(x, y), later)
		fun shown(tag: SemanticTag, role: PartRole? = null) = layers.filter { it.opaquePixels > 0 && it.semantic.tag == tag && it.semantic.role == role }
		fun sideName(side: Side) = if (side == Side.LEFT) "L" else "R"
		// Side.LEFT is the character's left, on the canvas's right.
		fun sideOf(x: Float) = if (x >= faceCenterX) Side.LEFT else Side.RIGHT

		// Eyes, from their fits and their layers.
		val eyeFits = eyes?.fits.orEmpty()
		for ((side, fit) in eyeFits) {
			val s = "eye.${sideName(side)}"
			add("$s.inner", s, fit.toCanvas(0f, 0f))
			add("$s.outer", s, fit.toCanvas(1f, fit.cornerV))
			val apex = fit.upper.indices.minBy { fit.upper[it] - fit.baseline(EyeFit.u(it)) }
			add("$s.apex", s, fit.toCanvas(EyeFit.u(apex), fit.upper[apex]))
			val low = fit.lower.indices.maxBy { fit.lower[it] - fit.baseline(EyeFit.u(it)) }
			add("$s.lowLid", s, fit.toCanvas(EyeFit.u(low), fit.lower[low]))
			for (q in listOf(0.25f, 0.5f, 0.75f)) add("$s.upper${(q * 100).toInt()}", s, fit.toCanvas(q, EyeFit.sample(fit.upper, q)), later = true)
			fit.closed?.let { closed -> add("$s.closedMid", s, fit.toCanvas(0.5f, EyeFit.sample(closed, 0.5f)), later = true) }
			fit.iris?.let { iris ->
				add("$s.iris", s, iris.centerX to iris.centerY)
				add("$s.irisTop", s, iris.centerX to iris.top); add("$s.irisBottom", s, iris.centerX to iris.bottom)
				add("$s.irisInner", s, (if (fit.axisX >= 0f) iris.left else iris.right) to iris.centerY)
				add("$s.irisOuter", s, (if (fit.axisX >= 0f) iris.right else iris.left) to iris.centerY)
			}
			fun eyeLayers(tag: SemanticTag, role: PartRole?) = shown(tag, role).filter { it.semantic.side == side || it.semantic.side == Side.NONE && sideOf(it.centroidX) == side }
			fun inEye(p: Pixels): List<Pair<Float, Float>> = (0 until p.size).map { i -> toRig(p.xs[i], p.ys[i]).let { r -> fit.toEye(r.first, r.second) } }
			eyeLayers(SemanticTag.IRIDES, PartRole.PUPIL).firstOrNull()?.let { addCanvas("$s.pupil", s, it.centroidX, it.centroidY) }
			for ((k, h) in eyeLayers(SemanticTag.IRIDES, PartRole.HIGHLIGHT).withIndex()) addCanvas("$s.highlight$k", s, h.centroidX, h.centroidY)
			// The upper lash line: its tail beyond the outer corner and its inner end.
			eyeLayers(SemanticTag.EYELASH, null).maxByOrNull { it.opaquePixels }?.let { lash ->
				val pts = inEye(pixels(lash))
				if (pts.isNotEmpty()) {
					val tail = pts.maxBy { it.first }; val start = pts.minBy { it.first }
					add("$s.lashTail", s, fit.toCanvas(tail.first, tail.second))
					add("$s.lashInner", s, fit.toCanvas(start.first, start.second))
				}
			}
			eyeLayers(SemanticTag.EYELASH, PartRole.LID).maxByOrNull { it.opaquePixels }?.let { lid ->
				val pts = inEye(pixels(lid))
				if (pts.isNotEmpty()) {
					val a = pts.minBy { it.first }; val b = pts.maxBy { it.first }; val top = pts.minBy { it.second }
					add("$s.lidInner", s, fit.toCanvas(a.first, a.second)); add("$s.lidOuter", s, fit.toCanvas(b.first, b.second)); add("$s.lidTop", s, fit.toCanvas(top.first, top.second))
				}
			}
			eyeLayers(SemanticTag.EYELASH, PartRole.LOWER).maxByOrNull { it.opaquePixels }?.let { lower ->
				val pts = inEye(pixels(lower))
				if (pts.isNotEmpty()) {
					val a = pts.minBy { it.first }; val b = pts.maxBy { it.first }
					add("$s.lowerLashInner", s, fit.toCanvas(a.first, a.second)); add("$s.lowerLashOuter", s, fit.toCanvas(b.first, b.second))
				}
			}
			eyeLayers(SemanticTag.EYELASH, PartRole.SHADOW).maxByOrNull { it.opaquePixels }?.let { addCanvas("$s.shadow", s, it.centroidX, it.centroidY, later = true) }
		}

		// Brows, from their fits.
		for ((side, fit) in brows) {
			val s = "brow.${sideName(side)}"
			add("$s.head", s, fit.toRig(0f, fit.centre.first()))
			add("$s.tail", s, fit.toRig(1f, fit.centre.last()))
			val n = fit.centre.size
			val peak = fit.centre.indices.minBy { fit.centre[it] }
			add("$s.peak", s, fit.toRig(peak.toFloat() / (n - 1), fit.centre[peak]))
		}

		// The mouth, from its fit; a mouth the fit could not read (a closed line) from its layer's ends and middle.
		if (mouth != null) {
			add("mouth.cornerA", "mouth", mouth.toCanvas(0f, 0f)); add("mouth.cornerB", "mouth", mouth.toCanvas(1f, mouth.cornerV))
			add("mouth.upperMid", "mouth", mouth.toCanvas(0.5f, MouthFit.sample(mouth.upper, 0.5f)))
			add("mouth.lowerMid", "mouth", mouth.toCanvas(0.5f, MouthFit.sample(mouth.lower, 0.5f)))
		} else layers.filter { it.opaquePixels > 0 && it.semantic.role == null && (it.semantic.tag == SemanticTag.MOUTH || it.semantic.tag == SemanticTag.MOUTH_CLOSE) }
			.maxByOrNull { it.opaquePixels }?.let { pixels(it) }?.takeIf { !it.isEmpty() }?.let { p ->
				val a = (0 until p.size).minBy { p.xs[it] }; val b = (0 until p.size).maxBy { p.xs[it] }
				addCanvas("mouth.cornerA", "mouth", p.xs[a], p.ys[a]); addCanvas("mouth.cornerB", "mouth", p.xs[b], p.ys[b])
				val midX = (p.xs[a] + p.xs[b]) / 2
				val column = (0 until p.size).filter { abs(p.xs[it] - midX) < 1.5f }
				if (column.isNotEmpty()) {
					addCanvas("mouth.upperMid", "mouth", midX, column.minOf { p.ys[it] }); addCanvas("mouth.lowerMid", "mouth", midX, column.maxOf { p.ys[it] })
				}
			}

		// The eyes' centres and the mouth's, on the canvas, set the face's frame.
		val eyeCentres = layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.EYEWHITE }.map { it.centroidX to it.centroidY }
		val eyeY = eyeCentres.map { it.second }.average().toFloat().takeIf { !it.isNaN() }
		val eyeGap = eyeCentres.takeIf { it.size >= 2 }?.let { c -> c.maxOf { it.first } - c.minOf { it.first } }
		val mouthLayer = layers.filter { it.opaquePixels > 0 && (it.semantic.tag == SemanticTag.MOUTH || it.semantic.tag == SemanticTag.MOUTH_OPEN || it.semantic.tag == SemanticTag.MOUTH_CLOSE) }
		val mouthY = mouthLayer.map { it.centroidY }.average().toFloat().takeIf { !it.isNaN() }

		val noseY = layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.NOSE }.map { it.bounds.bottom }.maxOrNull()

		// The face's outline.
		val face = layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.FACE && it.semantic.role == null }.maxByOrNull { it.opaquePixels }
		val contour = ArrayList<Pair<Float, Float>>()
		if (face != null && eyeY != null) {
			val cy = mouthY?.let { (eyeY + it) / 2 } ?: (eyeY + face.bounds.height * 0.15f)
			val cx = eyeCentres.takeIf { it.size >= 2 }?.map { it.first }?.average()?.toFloat() ?: faceCenterX
			val reach = hypot(face.bounds.width, face.bounds.height)
			val rays = 360
			val canvas = ArrayList<Pair<Float, Float>>()
			for (k in 0 until rays) {
				val a = 2.0 * Math.PI * k / rays
				val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
				var last = -1f; var t = 0f
				while (t < reach) { if (alpha(face, cx + dx * t, cy + dy * t)) last = t; t += 1f }
				if (last > 0f) canvas += (cx + dx * last) to (cy + dy * last)
			}
			// The outline is read in the head's own upright frame, so a head drawn tilted keeps its chin at the bottom.
			if (canvas.size > 40) {
				fun rigY(x: Float, y: Float) = toRig(x, y).second
				val eyesRig = eyeCentres.map { toRig(it.first, it.second) }
				val mouthRig = mouthY?.let { m -> mouthLayer.map { toRig(it.centroidX, it.centroidY) }.let { ms -> ms.map { it.second }.average().toFloat() } }
				val noseRig = noseY?.let { layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.NOSE }.maxOf { rigY(it.bounds.centerX, it.bounds.bottom) } }
				contourPoints(canvas.map { toRig(it.first, it.second) }, toRig(cx, cy).first, eyesRig.map { it.second }.average().toFloat(), mouthRig, noseRig,
					eyeGap ?: face.bounds.width * 0.4f, eyeFits, { x, y -> x to y }, contour, ::add, measures)
			}
		}

		// The face's middle: the brows' meeting, the nose.
		val browHeads = listOfNotNull(out.firstOrNull { it.id == "brow.L.head" }, out.firstOrNull { it.id == "brow.R.head" })
		val innerCorners = listOfNotNull(out.firstOrNull { it.id == "eye.L.inner" }, out.firstOrNull { it.id == "eye.R.inner" })
		val browCentre = when {
			browHeads.size == 2 -> browHeads.map { it.x }.average().toFloat() to browHeads.map { it.y }.average().toFloat()
			innerCorners.size == 2 && eyeGap != null -> innerCorners.map { it.x }.average().toFloat() to (innerCorners.map { it.y }.average().toFloat() - eyeGap * 0.3f)
			else -> null
		}
		browCentre?.let { add("face.browCentre", "face", it) }
		val nose = layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.NOSE }
		if (nose.isNotEmpty()) {
			val all = nose.map(::pixels).filter { !it.isEmpty() }
			val xs = all.flatMap { it.xs.toList() }; val ys = all.flatMap { it.ys.toList() }
			if (xs.isNotEmpty()) {
				val minY = ys.min(); val maxY = ys.max()
				val small = eyeGap == null || maxY - minY < eyeGap * 0.08f
				val tip = if (small) xs.average().toFloat() to ys.average().toFloat()
					else ys.indices.filter { ys[it] > maxY - 2.5f }.let { b -> b.map { xs[it] }.average().toFloat() to maxY }
				addCanvas("nose.tip", "nose", tip.first, tip.second)
				addCanvas("nose.top", "nose", xs[ys.indexOf(minY)], minY)
				val l = xs.indices.minBy { xs[it] }; val r = xs.indices.maxBy { xs[it] }
				addCanvas("nose.sideA", "nose", xs[l], ys[l]); addCanvas("nose.sideB", "nose", xs[r], ys[r])
				addCanvas("nose.bottom", "nose", xs[ys.indexOf(maxY)], maxY)
				measures["nose.drawn"] = 1f
			}
		} else if (eyeY != null && mouthY != null) {
			// Not drawn: the middle between the eyes and the mouth, a little toward the mouth.
			addCanvas("nose.tip", "nose", faceCenterX, eyeY + (mouthY - eyeY) * 0.55f)
			measures["nose.drawn"] = 0f
		}
		browCentre?.let { add("nose.root", "nose", it) }
		if (eyeY != null && mouthY != null && eyeGap != null) {
			val chin = out.firstOrNull { it.id == "face.chin" }
			for (side in listOf(Side.LEFT, Side.RIGHT)) {
				val e = eyeFits[side] ?: continue
				val (ox, oy) = e.toCanvas(0.5f, 0f)
				add("face.cheekTop.${sideName(side)}", "face", ox to (oy + eyeGap * 0.35f), later = true)
			}
			chin?.let { c -> out.firstOrNull { it.id == "mouth.lowerMid" }?.let { m -> add("face.chinCentre", "face", (c.x + m.x) / 2 to (c.y * 2 + m.y) / 3, later = true) } }
		}

		// Ears.
		for (ear in layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.EARS }) {
			val p = pixels(ear); if (p.isEmpty()) continue
			val side = if (ear.semantic.side != Side.NONE) ear.semantic.side else sideOf(ear.centroidX)
			// An ear on top of the head (an animal's) stands on its base; one at the side of the face hangs from its root by the cheek.
			val onTop = ear.semantic.role == PartRole.ANIMAL || eyeY != null && eyeGap != null && ear.centroidY < eyeY - eyeGap
			val s = "ear.${sideName(side)}${if (onTop) ".animal" else ""}"
			val top = (0 until p.size).minBy { p.ys[it] }; val bottom = (0 until p.size).maxBy { p.ys[it] }
			val inner = (0 until p.size).minBy { abs(p.xs[it] - faceCenterX) }; val outer = (0 until p.size).maxBy { abs(p.xs[it] - faceCenterX) }
			if (onTop) {
				val base = (0 until p.size).filter { p.ys[it] > p.ys[bottom] - 3f }
				addCanvas("$s.base", s, base.map { p.xs[it] }.average().toFloat(), p.ys[bottom])
				addCanvas("$s.tip", s, p.xs[top], p.ys[top])
				addCanvas("$s.inner", s, p.xs[inner], p.ys[inner]); addCanvas("$s.outer", s, p.xs[outer], p.ys[outer])
			} else {
				addCanvas("$s.root", s, p.xs[inner], p.ys[inner]); addCanvas("$s.outer", s, p.xs[outer], p.ys[outer])
				addCanvas("$s.top", s, p.xs[top], p.ys[top]); addCanvas("$s.lobe", s, p.xs[bottom], p.ys[bottom])
			}
		}

		// Hair.
		val unit = eyeGap ?: 40f
		for ((k, fringe) in shown(SemanticTag.FRONT_HAIR, null).withIndex()) hairPoints("hair.front$k", fringe, unit, faceCenterX, ::addCanvas, tips = true, below = eyeY?.let { it - unit } ?: Float.NEGATIVE_INFINITY)
		for ((k, lock) in shown(SemanticTag.FRONT_HAIR, PartRole.SIDE).withIndex()) {
			val p = pixels(lock); if (p.isEmpty()) continue
			val s = "hair.side$k"
			val top = (0 until p.size).minBy { p.ys[it] }; val bottom = (0 until p.size).maxBy { p.ys[it] }
			val midY = (p.ys[top] + p.ys[bottom]) / 2
			val edge = (0 until p.size).filter { abs(p.ys[it] - midY) < 2f }.minByOrNull { abs(p.xs[it] - faceCenterX) }
			addCanvas("$s.root", s, p.xs[top], p.ys[top]); addCanvas("$s.tip", s, p.xs[bottom], p.ys[bottom])
			edge?.let { addCanvas("$s.faceEdge", s, p.xs[it], p.ys[it]) }
		}
		for ((k, back) in shown(SemanticTag.BACK_HAIR, null).withIndex()) hairPoints("hair.back$k", back, unit, faceCenterX, ::addCanvas, tips = true, below = eyeY ?: Float.NEGATIVE_INFINITY)
		return FaceLandmarks(out, contour, measures)
	}

	/** A hair layer's points: its top in the middle, its outermost points each side, and the tips and gaps of its lower edge. */
	private fun hairPoints(s: String, layer: ClassifiedLayer, unit: Float, faceCenterX: Float, add: (String, String, Float, Float, Boolean) -> Unit, tips: Boolean,
	                       below: Float = Float.NEGATIVE_INFINITY) {
		val raster = layer.source.raster
		val left = layer.source.bounds.left; val top = layer.source.bounds.top
		// The lowest opaque pixel of each column: the lower edge.
		val bottom = IntArray(raster.width) { -1 }
		val upper = IntArray(raster.width) { -1 }
		for (x in 0 until raster.width) for (y in 0 until raster.height) if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) >= ALPHA) { if (upper[x] < 0) upper[x] = y; bottom[x] = y }
		val cols = (0 until raster.width).filter { bottom[it] >= 0 }
		if (cols.isEmpty()) return
		val mid = cols.minBy { abs(left + it - faceCenterX) }
		add("$s.top", s, left + mid + 0.5f, top + upper[mid] + 0.5f, false)
		add("$s.outerA", s, left + cols.first() + 0.5f, top + bottom[cols.first()] + 0.5f, false)
		add("$s.outerB", s, left + cols.last() + 0.5f, top + bottom[cols.last()] + 0.5f, false)
		if (!tips) return
		// Tips: the edge's lowest points that stand out by a tenth of the eyes' distance from both sides; gaps: its highest between tips.
		val prominence = unit * 0.1f
		val window = (unit * 0.15f).toInt().coerceAtLeast(3)
		val found = ArrayList<Int>()
		for (i in cols.indices) {
			val x = cols[i]; val y = bottom[x]
			if (top + y < below) continue
			val near = cols.filter { abs(it - x) <= window }
			if (near.any { bottom[it] > y }) continue
			val leftUp = cols.filter { it < x && it >= x - window * 4 }.minOfOrNull { bottom[it] } ?: continue
			val rightUp = cols.filter { it > x && it <= x + window * 4 }.minOfOrNull { bottom[it] } ?: continue
			if (y - max(leftUp, rightUp) < prominence) continue
			if (found.isNotEmpty() && x - found.last() < window) continue
			found += x
		}
		for ((k, x) in found.withIndex()) add("$s.tip$k", s, left + x + 0.5f, top + bottom[x] + 0.5f, false)
		for (k in 0 until found.size - 1) {
			val gx = cols.filter { it > found[k] && it < found[k + 1] }.minByOrNull { bottom[it] } ?: continue
			add("$s.gap$k", s, left + gx + 0.5f, top + bottom[gx] + 0.5f, false)
		}
	}

	/** The outline's points (see the object's doc), from its canvas samples in order round it. */
	private fun contourPoints(canvas: List<Pair<Float, Float>>, cx: Float, eyeY: Float, mouthY: Float?, noseY: Float?, eyeGap: Float, eyeFits: Map<Side, EyeFit>,
	                          toRig: (Float, Float) -> Pair<Float, Float>, contour: MutableList<Pair<Float, Float>>,
	                          add: (String, String, Pair<Float, Float>, Boolean) -> Unit, measures: MutableMap<String, Float>) {
		// Even steps along the outline.
		val n = 240
		val len = FloatArray(canvas.size + 1)
		for (i in canvas.indices) { val a = canvas[i]; val b = canvas[(i + 1) % canvas.size]; len[i + 1] = len[i] + hypot(b.first - a.first, b.second - a.second) }
		val pts = (0 until n).map { k ->
			val t = len.last() * k / n
			val i = (len.indexOfFirst { it > t } - 1).coerceIn(0, canvas.size - 1)
			val a = canvas[i]; val b = canvas[(i + 1) % canvas.size]
			val f = ((t - len[i]) / (len[i + 1] - len[i]).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
			(a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
		}
		for (p in pts) contour += toRig(p.first, p.second)
		// Turning at each point over a window: + bends outward (the outline is convex there), - inward.
		val w = n / 40
		val turn = FloatArray(n) { i ->
			val a = pts[(i - w + n) % n]; val b = pts[i]; val c = pts[(i + w) % n]
			val a1 = atan2(b.second - a.second, b.first - a.first); val a2 = atan2(c.second - b.second, c.first - b.first)
			var d = a2 - a1
			while (d > Math.PI) d -= (2 * Math.PI).toFloat(); while (d < -Math.PI) d += (2 * Math.PI).toFloat()
			d
		}
		// The rays go clockwise on the canvas (y down), so an outward bend turns positive.
		fun at(i: Int) = pts[(i % n + n) % n]
		fun put(id: String, i: Int, later: Boolean = false) = add(id, "face", toRig(at(i).first, at(i).second), later)
		val chin = pts.indices.maxBy { pts[it].second }
		val top = pts.indices.minBy { pts[it].second }
		val faceW = pts.maxOf { it.first } - pts.minOf { it.first }
		// A flat chin: its bottom edge's run within a sliver of the lowest point.
		val lowest = pts[chin].second
		val run = pts.indices.filter { pts[it].second > lowest - faceW * 0.012f && abs(pts[it].first - pts[chin].first) < faceW * 0.3f }
		val runW = (run.maxOfOrNull { pts[it].first } ?: 0f) - (run.minOfOrNull { pts[it].first } ?: 0f)
		if (runW > faceW * 0.18f) {
			put("face.chinCornerA", run.minBy { pts[it].first }); put("face.chinCornerB", run.maxBy { pts[it].first })
			val mid = run.minBy { abs(pts[it].first - (pts[run.minBy { i -> pts[i].first }].first + pts[run.maxBy { i -> pts[i].first }].first) / 2) }
			put("face.chin", mid)
			measures["face.chinFlat"] = runW
		} else put("face.chin", chin)
		put("face.top", top)
		// Each side: from the chin up to the top. The canvas's right is the character's left.
		for (side in listOf(Side.LEFT, Side.RIGHT)) {
			val name = if (side == Side.LEFT) "L" else "R"
			val sign = if (side == Side.LEFT) 1f else -1f
			// The indices from the chin to the top along this side.
			fun walk(): List<Int> {
				val fwd = ArrayList<Int>(); var i = chin
				while (i != top) { fwd += i; i = (i + 1) % n }
				val back = ArrayList<Int>(); i = chin
				while (i != top) { back += i; i = (i - 1 + n) % n }
				val f = fwd.map { pts[it].first - cx }.average(); return if ((f * sign) > 0) fwd else back
			}
			val path = walk()
			if (path.size < 8) continue
			// The cheekbone: the side's widest point between the eye line and the nose.
			val band = path.filter { pts[it].second in eyeY..(noseY ?: mouthY?.let { m -> eyeY + (m - eyeY) * 0.55f } ?: (eyeY + eyeGap * 0.6f)) }
			val cheek = (band.ifEmpty { path }).maxBy { (pts[it].first - cx) * sign }
			put("face.cheekbone.$name", cheek)
			val ci = path.indexOf(cheek)
			// The jaw angle: the sharpest outward bend between the cheekbone and the chin, away from the chin itself.
			val lower = path.subList(0, ci).filter { abs(pts[it].first - pts[chin].first) > faceW * 0.12f }
			val jaw = lower.maxByOrNull { turn[it] }
			jaw?.let { put("face.jaw.$name", it) }
			// The cheek-to-jaw turn: the flattest or most inward bend between the cheekbone and the jaw angle.
			jaw?.let { j ->
				val ji = path.indexOf(j)
				val between = path.subList(min(ji, ci), max(ji, ci) + 1).drop(2).dropLast(2)
				between.minByOrNull { turn[it] }?.let { put("face.cheekJaw.$name", it) }
			}
			// The temple: the sharpest outward bend between the cheekbone and the top, above the eye line.
			val reach = abs(pts[cheek].first - cx)
			val upperPath = path.subList(ci, path.size).filter { pts[it].second < eyeY - eyeGap * 0.4f && abs(pts[it].first - cx) > reach * 0.6f }
			upperPath.maxByOrNull { turn[it] }?.let { put("face.temple.$name", it) }
			// Where the eye line meets this side, and how far the eye's outer corner is from it.
			path.minByOrNull { abs(pts[it].second - eyeY) }?.let { i ->
				put("face.eyeLine.$name", i)
				eyeFits[side]?.let { e ->
					val outer = e.toCanvas(1f, e.cornerV)
					val edge = toRig(pts[i].first, pts[i].second)
					measures["eye.$name.outerToContour"] = abs(edge.first - outer.first)
				}
			}
		}
		measures["face.width"] = faceW
	}
}
