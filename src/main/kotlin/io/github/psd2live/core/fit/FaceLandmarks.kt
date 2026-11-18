package io.github.psd2live.core.fit

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.PartRoles
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
	private data class HairColumns(val width: Int, val height: Int, val upper: IntArray, val bottom: IntArray)
	private val hairColumns = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ByteArray, HairColumns>())

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
	         coveringHair: List<ClassifiedLayer> = emptyList(),
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
		val eyeCenters = eyeFits.mapValues { (_, fit) -> fit.toCanvas(0.5f, fit.baseline(0.5f)) }
		val leftCenter = eyeCenters[Side.LEFT]
		val rightCenter = eyeCenters[Side.RIGHT]
		val nearestEye: ((Float, Float) -> Side)? = if (leftCenter != null && rightCenter != null && leftCenter != rightCenter) {
			val (lx, ly) = leftCenter
			val (rx, ry) = rightCenter
			val midX = (lx + rx) * 0.5f; val midY = (ly + ry) * 0.5f
			val dx = lx - rx; val dy = ly - ry
			// Split shared artwork along the bisector of the fitted eye centres in the aligned frame.
			val sideAt = { x: Float, y: Float -> if ((x - midX) * dx + (y - midY) * dy >= 0f) Side.LEFT else Side.RIGHT }
			sideAt
		} else null
		val lashPixels = HashMap<ClassifiedLayer, Pixels>()
		for ((side, fit) in eyeFits) {
			val s = "eye.${sideName(side)}"
			add("$s.inner", s, fit.toCanvas(0f, 0f))
			add("$s.outer", s, fit.toCanvas(1f, fit.cornerV))
			val apex = fit.upper.indices.minBy { fit.upper[it] - fit.baseline(EyeFit.u(it)) }
			add("$s.apex", s, fit.toCanvas(EyeFit.u(apex), fit.upper[apex]))
			val low = fit.lower.indices.maxBy { fit.lower[it] - fit.baseline(EyeFit.u(it)) }
			add("$s.lowLid", s, fit.toCanvas(EyeFit.u(low), fit.lower[low]))
			for (q in 25..75 step 25) {
				val u = q / 100f
				add("$s.upper$q", s, fit.toCanvas(u, EyeFit.sample(fit.upper, u)))
				add("$s.lower$q", s, fit.toCanvas(u, EyeFit.sample(fit.lower, u)))
			}
			fit.closed?.let { closed -> add("$s.closedMid", s, fit.toCanvas(0.5f, EyeFit.sample(closed, 0.5f)), later = true) }
			fit.iris?.let { iris ->
				add("$s.iris", s, iris.centerX to iris.centerY)
				add("$s.irisTop", s, iris.centerX to iris.top); add("$s.irisBottom", s, iris.centerX to iris.bottom)
				add("$s.irisInner", s, (if (fit.axisX >= 0f) iris.left else iris.right) to iris.centerY)
				add("$s.irisOuter", s, (if (fit.axisX >= 0f) iris.right else iris.left) to iris.centerY)
			}
			fun eyeLayers(tag: SemanticTag, role: PartRole?) = shown(tag, role).filter { it.semantic.side == side || it.semantic.side == Side.NONE && sideOf(it.centroidX) == side }
			fun lashInk(role: PartRole?): List<Pair<Float, Float>> {
				val candidates = shown(SemanticTag.EYELASH, role).filter {
					it.semantic.side == side || it.semantic.side == Side.NONE && (nearestEye != null || sideOf(it.centroidX) == side)
				}
				val (overlays, primary) = candidates.partition { it.source.groupPath.split('/').any(PartRoles::isThroughHairGroup) }
				fun largestLocalInk(from: List<ClassifiedLayer>): List<Pair<Float, Float>> = from.asSequence().map { layer ->
					val p = lashPixels.getOrPut(layer) { pixels(layer) }
					val sampleSide = nearestEye.takeIf { layer.semantic.side == Side.NONE }
					buildList {
						for (i in 0 until p.size) {
							val (x, y) = toRig(p.xs[i], p.ys[i])
							if (sampleSide == null || sampleSide(x, y) == side) add(fit.toEye(x, y))
						}
					}
				}.maxByOrNull { it.size }.orEmpty()
				// A painted through-hair copy can fill a missing eye landmark, one eye's ink at a time.
				return largestLocalInk(primary).ifEmpty { largestLocalInk(overlays) }
			}
			eyeLayers(SemanticTag.IRIDES, PartRole.PUPIL).firstOrNull()?.let { addCanvas("$s.pupil", s, it.centroidX, it.centroidY) }
			for ((k, h) in eyeLayers(SemanticTag.IRIDES, PartRole.HIGHLIGHT).withIndex()) addCanvas("$s.highlight$k", s, h.centroidX, h.centroidY)
			// The upper lash line: its tail beyond the outer corner and its inner end.
			lashInk(null).takeIf { it.isNotEmpty() }?.let { pts ->
				val tail = pts.maxBy { it.first }; val start = pts.minBy { it.first }
				add("$s.lashTail", s, fit.toCanvas(tail.first, tail.second))
				add("$s.lashInner", s, fit.toCanvas(start.first, start.second))
			}
			lashInk(PartRole.LID).takeIf { it.isNotEmpty() }?.let { pts ->
				val a = pts.minBy { it.first }; val b = pts.maxBy { it.first }; val top = pts.minBy { it.second }
				add("$s.lidInner", s, fit.toCanvas(a.first, a.second)); add("$s.lidOuter", s, fit.toCanvas(b.first, b.second)); add("$s.lidTop", s, fit.toCanvas(top.first, top.second))
			}
			lashInk(PartRole.LOWER).takeIf { it.isNotEmpty() }?.let { pts ->
				val a = pts.minBy { it.first }; val b = pts.maxBy { it.first }
				add("$s.lowerLashInner", s, fit.toCanvas(a.first, a.second)); add("$s.lowerLashOuter", s, fit.toCanvas(b.first, b.second))
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
			for (q in 25..75 step 25) {
				val u = q / 100f
				add("$s.center$q", s, fit.toRig(u, BrowFit.sample(fit.centre, u)))
			}
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
				upperCover(face, canvas, eyeY, coveringHair, measures)
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

		// Each ear may be split into front, fur and back layers. Measure one silhouette per side and kind,
		// not one landmark set per layer (the depth-turn input requires unique semantic IDs).
		val ears = layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.EARS }.groupBy { ear ->
			val side = if (ear.semantic.side != Side.NONE) ear.semantic.side else sideOf(ear.centroidX)
			// An ear on top of the head (an animal's) stands on its base; one at the side of the face hangs from its root by the cheek.
			val onTop = ear.semantic.role == PartRole.ANIMAL || eyeY != null && eyeGap != null && ear.centroidY < eyeY - eyeGap
			side to onTop
		}
		for ((key, parts) in ears) {
			val (side, onTop) = key
			// Count overlapping pixels once, and use canvas scan order to break extrema ties independently
			// of layer stacking order, just as for a single raster.
			val silhouette = parts.flatMap { ear -> pixels(ear).let { p -> (0 until p.size).map { p.xs[it] to p.ys[it] } } }
				.distinct().sortedWith(compareBy<Pair<Float, Float>> { it.second }.thenBy { it.first })
			if (silhouette.isEmpty()) continue
			val p = Pixels(FloatArray(silhouette.size) { silhouette[it].first }, FloatArray(silhouette.size) { silhouette[it].second })
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
				face?.let { skin -> earAttachment(p, skin, p.xs[outer] - p.xs[inner]) }?.let { (x, y) ->
					addCanvas("$s.attachment", s, x, y)
				}
			}
		}

		// Hair.
		val unit = eyeGap ?: 40f
		for ((k, fringe) in shown(SemanticTag.FRONT_HAIR, null).withIndex()) hairPoints("hair.front$k", fringe, unit, faceCenterX, ::addCanvas, below = eyeY?.let { it - unit } ?: Float.NEGATIVE_INFINITY)
		for ((k, lock) in shown(SemanticTag.FRONT_HAIR, PartRole.SIDE).withIndex()) {
			val p = pixels(lock); if (p.isEmpty()) continue
			val s = "hair.side$k"
			val top = (0 until p.size).minBy { p.ys[it] }; val bottom = (0 until p.size).maxBy { p.ys[it] }
			val midY = (p.ys[top] + p.ys[bottom]) / 2
			val edge = (0 until p.size).filter { abs(p.ys[it] - midY) < 2f }.minByOrNull { abs(p.xs[it] - faceCenterX) }
			addCanvas("$s.root", s, p.xs[top], p.ys[top]); addCanvas("$s.tip", s, p.xs[bottom], p.ys[bottom])
			edge?.let { addCanvas("$s.faceEdge", s, p.xs[it], p.ys[it]) }
		}
		for ((k, back) in shown(SemanticTag.BACK_HAIR, null).withIndex()) hairPoints("hair.back$k", back, unit, faceCenterX, ::addCanvas, below = eyeY ?: Float.NEGATIVE_INFINITY)
		return FaceLandmarks(out, contour, measures)
	}

	/** Measure how much opaque front hair extends beyond the face edge at the drawn forehead. */
	private fun upperCover(face: ClassifiedLayer, outline: List<Pair<Float, Float>>, eyeY: Float,
	                       hair: List<ClassifiedLayer>, measures: MutableMap<String, Float>) {
		if (hair.isEmpty() || face.bounds.width <= 1f) return
		val top = outline.minOf { it.second }
		val height = eyeY - top
		if (height <= 1f) return
		val width = face.bounds.width
		val step = max(1f, width / 250f)
		val left = ArrayList<Float>()
		val right = ArrayList<Float>()
		for (fraction in listOf(.24f, .32f, .40f, .48f, .56f, .64f)) {
			val y = top + height * fraction
			val edges = outline.indices.mapNotNull { i ->
				val (x0, y0) = outline[i]
				val (x1, y1) = outline[(i + 1) % outline.size]
				if ((y0 > y) == (y1 > y)) null else x0 + (x1 - x0) * (y - y0) / (y1 - y0)
			}
			val row = hair.filter { y in it.bounds.top..it.bounds.bottom }
			fun covered(x: Float) = row.any { x in it.bounds.left..it.bounds.right && alpha(it, x, y) }
			fun measure(edge: Float?, direction: Float): Float? {
				if (edge == null || !covered(edge) || !covered(edge - direction * step)) return null
				var margin = step
				while (margin < .16f * width && covered(edge + direction * margin)) margin += step
				return margin / width
			}
			measure(edges.minOrNull(), -1f)?.let(left::add)
			measure(edges.maxOrNull(), 1f)?.let(right::add)
		}
		if (left.size >= 4) measures["face.upperCover.R"] = left.sorted()[left.size / 2]
		if (right.size >= 4) measures["face.upperCover.L"] = right.sorted()[right.size / 2]
	}

	/** Observed seam where the face edge crosses the side ear's art, sampled across distinct raster rows. */
	private fun earAttachment(ear: Pixels, skin: ClassifiedLayer, outward: Float): Pair<Float, Float>? {
		if (outward == 0f) return null
		val rows = LinkedHashMap<Float, Float>()
		fun onSkin(x: Float, y: Float) = x >= skin.source.bounds.left && y >= skin.source.bounds.top && alpha(skin, x, y)
		for (i in 1 until ear.size) {
			val y = ear.ys[i]
			val left = ear.xs[i - 1]; val right = ear.xs[i]
			if (ear.ys[i - 1] != y || right - left != 1f) continue
			val inside = if (outward > 0f) left else right
			val outside = if (outward > 0f) right else left
			if (!onSkin(inside, y) || onSkin(outside, y)) continue
			val x = (left + right) / 2f
			val previous = rows[y]
			if (previous == null || (x - previous) * outward > 0f) rows[y] = x
		}
		if (rows.size < 3) return null
		val (y, x) = rows.entries.elementAt(rows.size / 2)
		return x to y
	}

	/** Column edges are raster-local and stay fixed across face, eye, brow and mouth fits. */
	private fun columns(raster: org.umamo.format.art.LayerRaster): HairColumns {
		val width = raster.width; val height = raster.height; val rgba = raster.rgba
		hairColumns[rgba]?.takeIf { it.width == width && it.height == height }?.let { return it }
		val upper = IntArray(width) { -1 }
		val bottom = IntArray(width) { -1 }
		var alpha = 3
		for (y in 0 until height) {
			if (Thread.currentThread().isInterrupted) throw InterruptedException()
			for (x in 0 until width) {
				if ((rgba[alpha].toInt() and 0xff) >= ALPHA) {
					if (upper[x] < 0) upper[x] = y
					bottom[x] = y
				}
				alpha += 4
			}
		}
		return HairColumns(width, height, upper, bottom).also { hairColumns[rgba] = it }
	}

	/** A hair layer's points: its top in the middle, its outermost points each side, and the tips and gaps of its lower edge. */
	private fun hairPoints(s: String, layer: ClassifiedLayer, unit: Float, faceCenterX: Float, add: (String, String, Float, Float, Boolean) -> Unit,
	                       below: Float) {
		val left = layer.source.bounds.left; val top = layer.source.bounds.top
		val (_, _, upper, bottom) = columns(layer.source.raster)
		val cols = bottom.indices.filter { bottom[it] >= 0 }
		if (cols.isEmpty()) return
		val mid = cols.minBy { abs(left + it - faceCenterX) }
		add("$s.top", s, left + mid + 0.5f, top + upper[mid] + 0.5f, false)
		add("$s.outerA", s, left + cols.first() + 0.5f, top + bottom[cols.first()] + 0.5f, false)
		add("$s.outerB", s, left + cols.last() + 0.5f, top + bottom[cols.last()] + 0.5f, false)
		// Tips: the edge's lowest points that stand out by a tenth of the eyes' distance from both sides; gaps: its highest between tips.
		val prominence = unit * 0.1f
		val window = (unit * 0.15f).toInt().coerceAtLeast(3)
		val found = ArrayList<Int>()
		fun highestColumn(from: Int, through: Int): Int {
			var best = -1
			for (column in maxOf(0, from)..minOf(bottom.lastIndex, through)) {
				val edge = bottom[column]
				if (edge >= 0 && (best < 0 || edge < bottom[best])) best = column
			}
			return best
		}
		for (x in cols) {
			val y = bottom[x]
			if (top + y < below) continue
			if ((maxOf(0, x - window)..minOf(bottom.lastIndex, x + window)).any { bottom[it] > y }) continue
			val leftUp = highestColumn(x - window * 4, x - 1)
			val rightUp = highestColumn(x + 1, x + window * 4)
			if (leftUp < 0 || rightUp < 0 || y - max(bottom[leftUp], bottom[rightUp]) < prominence) continue
			if (found.isNotEmpty() && x - found.last() < window) continue
			found += x
		}
		for ((k, x) in found.withIndex()) add("$s.tip$k", s, left + x + 0.5f, top + bottom[x] + 0.5f, false)
		for (k in 0 until found.size - 1) {
			val gx = highestColumn(found[k] + 1, found[k + 1] - 1)
			if (gx >= 0) add("$s.gap$k", s, left + gx + 0.5f, top + bottom[gx] + 0.5f, false)
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
