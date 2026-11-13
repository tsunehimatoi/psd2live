package io.github.psd2live.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cubism's physics as the Native Framework 5-r.5 evaluates physics3.json (CubismPhysics::Evaluate,
 * UpdateParticles, Interpolate), quirks included, so the software preview, the physics panel and MCP
 * simulations move the way the exported model does in a Cubism runtime. [fps] is the file's `Fps`: steps
 * of that length with inputs interpolated between frames, or one step per frame when null.
 */
class PhysicsEngine(settings: List<RigPhysicsEdit>, ranges: Map<String, Range>, val fps: Float? = 60f) {
	/** A parameter's span; inputs normalize against its midpoint and outputs clamp to it. */
	data class Range(val min: Float, val max: Float, val default: Float)

	/** One particle strand; index 0 is the root. Positions hang toward +Y. */
	class Strand internal constructor(val setting: RigPhysicsEdit) {
		private val n = setting.segments.size + 1
		val x = FloatArray(n)
		val y = FloatArray(n)
		internal val lastX = FloatArray(n)
		internal val lastY = FloatArray(n)
		internal val vx = FloatArray(n)
		internal val vy = FloatArray(n)
		internal val gravityX = FloatArray(n)
		internal val gravityY = FloatArray(n)
		/** Raw output values (radians for Angle) of the latest step and the one before. */
		internal val current = FloatArray(setting.outputs.size)
		internal val previous = FloatArray(setting.outputs.size)
		/** The most each output's raw value reached either way since the last [resetPeaks], for sizing its scale. */
		internal val highest = FloatArray(setting.outputs.size)
		internal val lowest = FloatArray(setting.outputs.size)
		val size: Int get() = n

		init { reset() }

		fun reset() {
			var at = 0f
			for (i in 0 until n) {
				if (i > 0) at += setting.segments[i - 1].length
				x[i] = 0f; y[i] = at; lastX[i] = 0f; lastY[i] = at
				vx[i] = 0f; vy[i] = 0f
				// Cubism starts every particle's last gravity at (0, -1) with Y flipped.
				gravityX[i] = 0f; gravityY[i] = 1f
			}
			current.fill(0f); previous.fill(0f)
			resetPeaks()
		}

		fun resetPeaks() { highest.fill(0f); lowest.fill(0f) }

		/**
		 * How far output [k] has swung toward its clamp as a fraction (1 = just reaching the parameter's
		 * end, above 1 = clamped), at its current scale; 0 before it has moved.
		 */
		fun peakFraction(k: Int, range: Range): Float {
			val o = setting.outputs.getOrNull(k) ?: return 0f
			val scale = scaleOf(o)
			val hi = maxOf(highest[k] * scale, lowest[k] * scale)
			val lo = minOf(highest[k] * scale, lowest[k] * scale)
			val up = if (range.max > 0f && hi > 0f) hi / range.max else 0f
			val down = if (range.min < 0f && lo < 0f) lo / range.min else 0f
			return maxOf(up, down)
		}

		/** The angle, in radians, vertex [vertex] makes against the segment above it (or gravity for 1). */
		fun angle(vertex: Int): Float {
			if (vertex !in 1 until n) return 0f
			val (fx, fy) = if (vertex >= 2) (x[vertex - 1] - x[vertex - 2]) to (y[vertex - 1] - y[vertex - 2]) else 0f to 1f
			return directionToRadian(fx, fy, x[vertex] - x[vertex - 1], y[vertex] - y[vertex - 1])
		}
	}

	private val ranges = ranges.toMap()
	val strands: List<Strand> = settings.map(::Strand)
	/** Every parameter the strands read or write. */
	private val touched: List<String> = settings.flatMap { it.parameters }.distinct().filter { it in this.ranges }
	/** Cubism's `_parameterInputCaches`: the inputs as of the last step. */
	private val inputCaches = HashMap<String, Float>()
	private var remain = 0f

	fun strand(id: String): Strand? = strands.firstOrNull { it.setting.id == id }

	fun reset() {
		strands.forEach(Strand::reset)
		inputCaches.clear()
		remain = 0f
	}

	/**
	 * Continues [previous]'s motion on strands of the same group and particle count, so an edit to a
	 * running pendulum retunes it instead of dropping it back to rest.
	 */
	fun carryOver(previous: PhysicsEngine?) {
		previous ?: return
		for (strand in strands) {
			val old = previous.strand(strand.setting.id)?.takeIf { it.size == strand.size } ?: continue
			for ((to, from) in listOf(strand.x to old.x, strand.y to old.y, strand.lastX to old.lastX, strand.lastY to old.lastY,
				strand.vx to old.vx, strand.vy to old.vy, strand.gravityX to old.gravityX, strand.gravityY to old.gravityY)) {
				from.copyInto(to)
			}
			if (old.setting.outputParameters == strand.setting.outputParameters) {
				old.current.copyInto(strand.current); old.previous.copyInto(strand.previous)
				old.highest.copyInto(strand.highest); old.lowest.copyInto(strand.lowest)
			}
		}
		inputCaches.putAll(previous.inputCaches)
		remain = previous.remain
	}

	/**
	 * Advances by a frame of [dt] seconds with the model's parameters at [values] (missing ones at their
	 * defaults) and returns the driven parameters as Cubism leaves them after the frame.
	 */
	fun step(values: Map<String, Float>, dt: Float): Map<String, Float> {
		if (dt <= 0f || strands.isEmpty()) return emptyMap()
		fun value(p: String) = values[p] ?: ranges.getValue(p).default
		remain += dt
		if (remain > MAX_DELTA_TIME) remain = 0f
		for (p in touched) inputCaches.getOrPut(p) { value(p) }
		val h = fps?.takeIf { it > 0f }?.let { 1f / it } ?: dt
		val caches = HashMap<String, Float>()
		while (remain >= h) {
			for (s in strands) s.current.copyInto(s.previous)
			// Inputs at this step's time, interpolated from the previous step's toward this frame's.
			val w = h / remain
			for (p in touched) {
				val v = inputCaches.getValue(p) * (1f - w) + value(p) * w
				caches[p] = v
				inputCaches[p] = v
			}
			// A group's outputs land in the caches, where a later group reads them in the same step.
			for (s in strands) {
				advance(s, caches, h)
				s.setting.outputs.forEachIndexed { k, o ->
					if (o.vertex !in 1 until s.size) return@forEachIndexed
					val raw = output(s, o)
					s.current[k] = raw
					if (raw > s.highest[k]) s.highest[k] = raw
					if (raw < s.lowest[k]) s.lowest[k] = raw
					caches[o.parameter]?.let { caches[o.parameter] = blend(it, raw, o) }
				}
			}
			remain -= h
		}
		// Between steps the outputs interpolate; with no Fps that is always the step before.
		val alpha = remain / h
		val out = HashMap<String, Float>()
		for (s in strands) s.setting.outputs.forEachIndexed { k, o ->
			if (o.vertex !in 1 until s.size || o.parameter !in ranges) return@forEachIndexed
			out[o.parameter] = blend(out[o.parameter] ?: value(o.parameter), s.previous[k] * (1f - alpha) + s.current[k] * alpha, o)
		}
		return out
	}

	/** Runs the strands toward rest under [values]. */
	fun settle(values: Map<String, Float>, seconds: Float = 2f, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Map<String, Float> {
		var out = emptyMap<String, Float>()
		var t = 0f
		while (t < seconds) {
			if (cancelled()) throw java.util.concurrent.CancellationException("Physics settling cancelled")
			out = step(values, SETTLE_STEP); t += SETTLE_STEP
			progress((t / seconds).coerceIn(0f, 1f))
		}
		return out
	}

	private fun advance(strand: Strand, caches: Map<String, Float>, dt: Float) {
		val setting = strand.setting
		val n = setting.normalization
		var tx = 0f
		var ty = 0f
		var angle = 0f
		for (input in setting.inputs) {
			val range = ranges[input.parameter] ?: continue
			val value = caches[input.parameter] ?: range.default
			val weight = input.weight / MAXIMUM_WEIGHT
			when (input.type) {
				PhysicsSourceType.X -> tx += normalize(value, range, n.positionMin, n.positionMax, n.positionDefault, input.reflect) * weight
				PhysicsSourceType.ANGLE -> angle += normalize(value, range, n.angleMin, n.angleMax, n.angleDefault, input.reflect) * weight
			}
		}
		val radian = degreesToRadian(-angle)
		// Cubism rotates Y with the already rotated X; the exported model moves that way, so this does too.
		tx = tx * cos(radian) - ty * sin(radian)
		ty = tx * sin(radian) + ty * cos(radian)
		updateParticles(strand, tx, ty, angle, MOVEMENT_THRESHOLD * n.positionMax, dt)
	}

	private fun updateParticles(s: Strand, tx: Float, ty: Float, totalAngle: Float, threshold: Float, dt: Float) {
		s.x[0] = tx
		s.y[0] = ty
		val totalRadian = degreesToRadian(totalAngle)
		var gx = sin(totalRadian)
		var gy = cos(totalRadian)
		val gl = sqrt(gx * gx + gy * gy)
		gx /= gl; gy /= gl
		for (i in 1 until s.size) {
			val segment = s.setting.segments[i - 1]
			val forceX = gx * segment.acceleration
			val forceY = gy * segment.acceleration
			s.lastX[i] = s.x[i]
			s.lastY[i] = s.y[i]
			val delay = segment.delay * dt * 30f
			var dx = s.x[i] - s.x[i - 1]
			var dy = s.y[i] - s.y[i - 1]
			val radian = directionToRadian(s.gravityX[i], s.gravityY[i], gx, gy) / AIR_RESISTANCE
			dx = cos(radian) * dx - dy * sin(radian)
			dy = sin(radian) * dx + dy * cos(radian)
			var px = s.x[i - 1] + dx + s.vx[i] * delay + forceX * delay * delay
			var py = s.y[i - 1] + dy + s.vy[i] * delay + forceY * delay * delay
			var nx = px - s.x[i - 1]
			var ny = py - s.y[i - 1]
			val length = sqrt(nx * nx + ny * ny)
			nx /= length; ny /= length
			px = s.x[i - 1] + nx * segment.length
			py = s.y[i - 1] + ny * segment.length
			if (kotlin.math.abs(px) < threshold) px = 0f
			s.x[i] = px
			s.y[i] = py
			if (delay != 0f) {
				s.vx[i] = (px - s.lastX[i]) / delay * segment.mobility
				s.vy[i] = (py - s.lastY[i]) / delay * segment.mobility
			}
			s.gravityX[i] = gx
			s.gravityY[i] = gy
		}
	}

	/** GetOutputAngle / GetOutputTranslationX, before scale. */
	private fun output(s: Strand, o: PhysicsOutput): Float {
		val i = o.vertex
		val raw = when (o.type) {
			PhysicsSourceType.X -> s.x[i] - s.x[i - 1]
			PhysicsSourceType.ANGLE -> s.angle(i)
		}
		return if (o.reflect) -raw else raw
	}

	/** UpdateOutputParameterValue: scaled, clamped to the range, then weighted over [current]. */
	private fun blend(current: Float, raw: Float, o: PhysicsOutput): Float {
		val range = ranges.getValue(o.parameter)
		val value = (raw * scaleOf(o)).coerceIn(minOf(range.min, range.max), maxOf(range.min, range.max))
		val weight = o.weight / MAXIMUM_WEIGHT
		return if (weight >= 1f) value else current * (1f - weight) + value * weight
	}

	companion object {
		const val AIR_RESISTANCE = 5f
		const val MAXIMUM_WEIGHT = 100f
		const val MOVEMENT_THRESHOLD = 0.001f
		const val MAX_DELTA_TIME = 5f
		private const val SETTLE_STEP = 1f / 60f
		private const val PI = 3.1415926535897932384626433832795f

		/**
		 * The scale Cubism applies: the file's Scale for Angle outputs. The Native Framework never reads
		 * a translation scale from physics3.json, so an X output always writes 0 there.
		 */
		fun scaleOf(o: PhysicsOutput): Float = if (o.type == PhysicsSourceType.ANGLE) o.scale else 0f

		fun ranges(parameters: List<org.umamo.runtime.model.Parameter>): Map<String, Range> =
			parameters.associate { it.id.raw to Range(it.min, it.max, it.default) }

		/** Cubism's NormalizeParameterValue: the value's side of the range's midpoint, scaled onto the normalized side. */
		internal fun normalize(value: Float, range: Range, normMin: Float, normMax: Float, normDefault: Float, inverted: Boolean): Float {
			val maxValue = maxOf(range.max, range.min)
			val minValue = minOf(range.max, range.min)
			val v = value.coerceIn(minValue, maxValue)
			val minNorm = minOf(normMin, normMax)
			val maxNorm = maxOf(normMin, normMax)
			val middle = minValue + (maxValue - minValue) / 2f
			val offset = v - middle
			val result = when {
				offset > 0f -> if (maxValue - middle != 0f) offset * ((maxNorm - normDefault) / (maxValue - middle)) + normDefault else 0f
				offset < 0f -> if (minValue - middle != 0f) offset * ((minNorm - normDefault) / (minValue - middle)) + normDefault else 0f
				else -> normDefault
			}
			return if (inverted) result else -result
		}

		/** Cubism's DirectionToRadian: atan2 of [to] minus atan2 of [from], within ±π. */
		internal fun directionToRadian(fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
			var r = atan2(toY, toX) - atan2(fromY, fromX)
			while (r < -PI) r += PI * 2f
			while (r > PI) r -= PI * 2f
			return r
		}

		private fun degreesToRadian(degrees: Float) = degrees / 180f * PI
	}
}
