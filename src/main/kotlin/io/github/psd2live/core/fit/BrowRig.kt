package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.Side
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One brow read off the art in the head's frame, as the eyes are ([EyeFit]): the first axis runs along the head's level
 * from the brow's inner end (toward the face's middle) to its outer end, the second points down the face, and a unit is
 * the brow's length along the level. [centre] is the stroke's centre line, sampled like [EyeFit.upper].
 */
data class BrowFit(
	val side: Side,
	val originX: Float, val originY: Float,
	val axisX: Float, val axisY: Float,
	val length: Float,
	val centre: List<Float>,
) {
	private val downX: Float get() = if (axisX >= 0f) -axisY else axisY
	private val downY: Float get() = if (axisX >= 0f) axisX else -axisX

	fun toBrow(x: Float, y: Float): Pair<Float, Float> {
		val dx = (x - originX) / length; val dy = (y - originY) / length
		return (dx * axisX + dy * axisY) to (dx * downX + dy * downY)
	}

	fun canvasDelta(du: Float, dv: Float): Pair<Float, Float> = ((du * axisX + dv * downX) * length) to ((du * axisY + dv * downY) * length)

	companion object {
		/**
		 * The fit of [brow] in the head's [level] (in the rig's space; [toRig] carries canvas points there); [faceCenterX]
		 * tells its inner end. Null when the stroke is too short to read.
		 */
		fun of(brow: ClassifiedLayer, level: Pair<Float, Float>, faceCenterX: Float, toRig: (Float, Float) -> Pair<Float, Float>): BrowFit? {
			val raster = brow.source.raster
			val points = ArrayList<Pair<Float, Float>>()
			for (y in 0 until raster.height) for (x in 0 until raster.width) {
				if ((raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < 96) continue
				points += toRig(brow.source.bounds.left + x + 0.5f, brow.source.bounds.top + y + 0.5f)
			}
			if (points.size < 12) return null
			val (lx, ly) = level
			fun along(p: Pair<Float, Float>) = p.first * lx + p.second * ly
			val lo = points.minOf(::along); val hi = points.maxOf(::along)
			if (hi - lo < 6f) return null
			val low = points.filter { along(it) <= lo + (hi - lo) * 0.04f }.let { it.map { p -> p.first }.average().toFloat() to it.map { p -> p.second }.average().toFloat() }
			val high = points.filter { along(it) >= hi - (hi - lo) * 0.04f }.let { it.map { p -> p.first }.average().toFloat() to it.map { p -> p.second }.average().toFloat() }
			val lowInner = abs(low.first - faceCenterX) < abs(high.first - faceCenterX)
			val inner = if (lowInner) low else high
			val sign = if (lowInner) 1f else -1f
			val frame = BrowFit(brow.semantic.side, inner.first, inner.second, lx * sign, ly * sign, hi - lo, emptyList())
			// The centre line: the mean second coordinate of the stroke at each place along it.
			val buckets = Array(EyeFit.SAMPLES) { DoubleArray(2) }
			for (p in points) {
				val (u, v) = frame.toBrow(p.first, p.second)
				val k = Math.round(u.coerceIn(0f, 1f) * (EyeFit.SAMPLES - 1))
				buckets[k][0] += v.toDouble(); buckets[k][1] += 1.0
			}
			val known = buckets.indices.filter { buckets[it][1] > 0 }
			val centre = (0 until EyeFit.SAMPLES).map { k ->
				val nearest = known.minByOrNull { abs(it - k) } ?: return null
				(buckets[nearest][0] / buckets[nearest][1]).toFloat()
			}
			return frame.copy(centre = centre)
		}
	}
}

/** Which brow modules a project builds, with their values as fractions. */
data class BrowOptions(
	val shape: Boolean = false, val shapeStrength: Float = 1f,
	val blink: Boolean = false, val blinkDip: Float = 1f,
)

/**
 * The `brow/fitted` base and the brow modules for one brow ([fit]): the brow keyed on its height by its angle, the
 * angle swept in five keys so it turns along an arc rather than shears (`brow.shape`), its sideways move and its form -
 * arching or flattening - as additive shapes, and its dip with the blink and lift with a widened eye (`brow.blink`).
 * [lift] is how far the brow travels up or down at full height, in canvas units: about half the eye's height below it.
 */
class BrowRig(val fit: BrowFit, private val lift: Float, private val options: BrowOptions) {
	private val unit = lift / fit.length

	/** A vertex's place after the brow is raised by [y] (−1 down … 1 up), turned by [angle] (−1 inner end down … 1 up), moved by [x] and bent by [form]. */
	fun delta(px: Float, py: Float, y: Float = 0f, angle: Float = 0f, x: Float = 0f, form: Float = 0f, dip: Float = 0f): Pair<Float, Float> {
		val (u, v) = fit.toBrow(px, py)
		val k = options.shapeStrength
		// Turned about the brow's middle; the inner end is at u = 0, so a positive angle lifts it.
		val theta = 0.28f * k * angle
		val cu = 0.5f; val cv = EyeFit.sample(fit.centre, 0.5f)
		val ru = cu + (u - cu) * cos(theta) - (v - cv) * sin(theta)
		val rv = cv + (u - cu) * sin(theta) + (v - cv) * cos(theta)
		val arch = -0.1f * k * form * 4f * u.coerceIn(0f, 1f) * (1f - u.coerceIn(0f, 1f))
		// The blink's dip pulls the inner end more than the outer one.
		val dipShape = dip * unit * (0.35f + 0.15f * (1f - u.coerceIn(0f, 1f)))
		val du = (ru - u) + 0.08f * k * x
		val dv = (rv - v) - y * unit + arch + dipShape
		return fit.canvasDelta(du, dv)
	}

	/** The brow's grid: its height by its angle (when `brow.shape` is on). Deltas are over the parent [frame]. */
	fun grid(positions: FloatArray, frame: Bounds, browY: ParameterId, browAngle: ParameterId): KeyformGrid<MeshDeltaForm> {
		val ys = floatArrayOf(-1f, 0f, 1f)
		val angles = if (options.shape) floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f) else null
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		for ((a, angle) in (angles ?: floatArrayOf(0f)).withIndex()) for ((i, y) in ys.withIndex()) {
			cells += KeyformCell(if (angles != null) intArrayOf(i, a) else intArrayOf(i), form(positions, frame) { px, py -> delta(px, py, y = y, angle = angle) })
		}
		return KeyformGrid(listOfNotNull(KeyformAxis(browY, ys), angles?.let { KeyformAxis(browAngle, it) }), cells)
	}

	/** The brow's additive shapes: sideways and form (`brow.shape`), the blink's dip and a widened eye's lift (`brow.blink`). */
	fun shapes(positions: FloatArray, frame: Bounds, browX: ParameterId, browForm: ParameterId, eyeOpen: ParameterId, wide: Boolean): List<BlendShapeBinding<MeshForm>> = buildList {
		fun shape(f: (Float, Float) -> Pair<Float, Float>) = MeshForm(form(positions, frame, f).positionDeltas)
		if (options.shape) {
			add(BlendShapeBinding(browX, floatArrayOf(-1f, 0f, 1f), 1, listOf(shape { x, y -> delta(x, y, x = -1f) }, null, shape { x, y -> delta(x, y, x = 1f) })))
			add(BlendShapeBinding(browForm, floatArrayOf(-1f, 0f, 1f), 1, listOf(shape { x, y -> delta(x, y, form = -1f) }, null, shape { x, y -> delta(x, y, form = 1f) })))
		}
		if (options.blink) {
			// Closed, the brow settles a little; widened, it lifts with the surprise. The neutral form is the open eye's.
			val keys = if (wide) floatArrayOf(0f, 1f, 2f) else floatArrayOf(0f, 1f)
			val forms = listOf(shape { x, y -> delta(x, y, dip = 0.25f * options.blinkDip) }, null) +
				(if (wide) listOf(shape { x, y -> delta(x, y, y = 0.6f) }) else emptyList())
			add(BlendShapeBinding(eyeOpen, keys, 1, forms))
		}
	}

	private fun form(positions: FloatArray, frame: Bounds, f: (Float, Float) -> Pair<Float, Float>): MeshDeltaForm {
		val out = FloatArray(positions.size)
		for (i in positions.indices step 2) {
			val (dx, dy) = f(positions[i], positions[i + 1])
			out[i] = dx / frame.width.coerceAtLeast(1e-4f); out[i + 1] = dy / frame.height.coerceAtLeast(1e-4f)
		}
		return MeshDeltaForm(out)
	}
}
