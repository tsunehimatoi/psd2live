package io.github.psd2live.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [PhysicsEngine] over arrays: the same steps in the same order, bit for bit, without a map or an allocation per
 * frame, for fits that play one pendulum through thousands of frames thousands of times. Values come in by
 * [parameters] (the parameters the settings read or write that have a range), outputs go out by [outputs].
 *
 * Not [exact], each angle between two directions is one [fastAtan2] of their cross and dot products instead of
 * the difference of two library atan2s, which are most of a frame's time; the engine's atan2 is the JVM's, not
 * Cubism's atan2f, so it is no closer to the exported model than that.
 */
class PhysicsKernel(settings: List<RigPhysicsEdit>, ranges: Map<String, PhysicsEngine.Range>, val fps: Float? = 60f,
	private val exact: Boolean = true) {
	/** The value layout of [step]: every parameter the settings touch that has a range, in the engine's order. */
	val parameters: List<String> = settings.flatMap { it.parameters }.distinct().filter { it in ranges }
	private val index = parameters.withIndex().associate { it.value to it.index }
	private val rangeOf = parameters.map { ranges.getValue(it) }.toTypedArray()

	/** The parameters [step] writes, in the order the engine's result first names them. */
	val outputs: List<String>

	/** Each parameter's default: a value [step] reads for a parameter the caller does not move. */
	val defaults = FloatArray(parameters.size) { rangeOf[it].default }

	private class Strand(val setting: RigPhysicsEdit, index: Map<String, Int>, ranges: Map<String, PhysicsEngine.Range>, outputSlot: Map<String, Int>) {
		val n = setting.segments.size + 1
		val x = FloatArray(n); val y = FloatArray(n)
		val lastX = FloatArray(n); val lastY = FloatArray(n)
		val vx = FloatArray(n); val vy = FloatArray(n)
		val gravityX = FloatArray(n); val gravityY = FloatArray(n)
		val current = FloatArray(setting.outputs.size)
		val previous = FloatArray(setting.outputs.size)
		val length = FloatArray(setting.segments.size) { setting.segments[it].length }
		val mobility = FloatArray(setting.segments.size) { setting.segments[it].mobility }
		val delay = FloatArray(setting.segments.size) { setting.segments[it].delay }
		val acceleration = FloatArray(setting.segments.size) { setting.segments[it].acceleration }
		/** Inputs with a range: their parameter's slot, range and weight. */
		val inputs = setting.inputs.filter { it.parameter in ranges }
		val inputSlot = IntArray(inputs.size) { index.getValue(inputs[it].parameter) }
		val inputRange = Array(inputs.size) { ranges.getValue(inputs[it].parameter) }
		val inputWeight = FloatArray(inputs.size) { inputs[it].weight / PhysicsEngine.MAXIMUM_WEIGHT }
		/** Per output: its parameter's slot when the engine caches it, else -1; its slot in [outputs], else -1. */
		val outputCache = IntArray(setting.outputs.size) { index[setting.outputs[it].parameter] ?: -1 }
		val outputSlot = IntArray(setting.outputs.size) { k ->
			val o = setting.outputs[k]
			if (o.vertex in 1 until n && o.parameter in ranges) outputSlot.getValue(o.parameter) else -1
		}
		val outputRange = Array(setting.outputs.size) { ranges[setting.outputs[it].parameter] }

		fun reset() {
			var at = 0f
			for (i in 0 until n) {
				if (i > 0) at += length[i - 1]
				x[i] = 0f; y[i] = at; lastX[i] = 0f; lastY[i] = at
				vx[i] = 0f; vy[i] = 0f
				gravityX[i] = 0f; gravityY[i] = 1f
			}
			current.fill(0f); previous.fill(0f)
		}
	}

	private val strands: Array<Strand>
	private val inputCaches = FloatArray(parameters.size)
	private var seen = false
	private var remain = 0f
	private val caches = FloatArray(parameters.size)
	private val written: BooleanArray

	init {
		val slots = LinkedHashMap<String, Int>()
		for (s in settings) for (o in s.outputs) if (o.vertex in 1 until s.segments.size + 1 && o.parameter in ranges) slots.getOrPut(o.parameter) { slots.size }
		outputs = slots.keys.toList()
		written = BooleanArray(outputs.size)
		strands = settings.map { Strand(it, index, ranges, slots) }.toTypedArray()
		reset()
	}

	/** The slot of [parameter] in [parameters], or -1. */
	fun indexOf(parameter: String): Int = index[parameter] ?: -1

	fun reset() {
		for (s in strands) s.reset()
		seen = false
		remain = 0f
	}

	/**
	 * Advances by a frame of [dt] seconds with the parameters at [values] (laid out as [parameters]) and writes the
	 * driven parameters to [out] (laid out as [outputs]). Returns false, leaving [out] alone, where the engine
	 * returns nothing.
	 */
	fun step(values: FloatArray, dt: Float, out: FloatArray): Boolean {
		if (dt <= 0f || strands.isEmpty()) return false
		remain += dt
		if (remain > PhysicsEngine.MAX_DELTA_TIME) remain = 0f
		if (!seen) { values.copyInto(inputCaches, 0, 0, parameters.size); seen = true }
		val h = fps?.takeIf { it > 0f }?.let { 1f / it } ?: dt
		while (remain >= h) {
			for (s in strands) s.current.copyInto(s.previous)
			val w = h / remain
			for (p in parameters.indices) {
				val v = inputCaches[p] * (1f - w) + values[p] * w
				caches[p] = v
				inputCaches[p] = v
			}
			for (s in strands) {
				advance(s, h)
				val outs = s.setting.outputs
				for (k in outs.indices) {
					val o = outs[k]
					if (o.vertex !in 1 until s.n) continue
					val raw = output(s, o)
					s.current[k] = raw
					val c = s.outputCache[k]
					if (c >= 0) caches[c] = blend(caches[c], raw, o, rangeOf[c])
				}
			}
			remain -= h
		}
		val alpha = remain / h
		written.fill(false)
		for (s in strands) {
			val outs = s.setting.outputs
			for (k in outs.indices) {
				val slot = s.outputSlot[k]
				if (slot < 0) continue
				val o = outs[k]
				val start = if (written[slot]) out[slot] else values[index.getValue(o.parameter)]
				out[slot] = blend(start, s.previous[k] * (1f - alpha) + s.current[k] * alpha, o, s.outputRange[k]!!)
				written[slot] = true
			}
		}
		return true
	}

	private fun advance(s: Strand, dt: Float) {
		val n = s.setting.normalization
		var tx = 0f
		var ty = 0f
		var angle = 0f
		for (j in s.inputs.indices) {
			val input = s.inputs[j]
			val value = caches[s.inputSlot[j]]
			val weight = s.inputWeight[j]
			when (input.type) {
				PhysicsSourceType.X -> tx += PhysicsEngine.normalize(value, s.inputRange[j], n.positionMin, n.positionMax, n.positionDefault, input.reflect) * weight
				PhysicsSourceType.ANGLE -> angle += PhysicsEngine.normalize(value, s.inputRange[j], n.angleMin, n.angleMax, n.angleDefault, input.reflect) * weight
			}
		}
		val radian = -angle / 180f * PI
		tx = tx * cos(radian) - ty * sin(radian)
		ty = tx * sin(radian) + ty * cos(radian)
		update(s, tx, ty, angle, PhysicsEngine.MOVEMENT_THRESHOLD * n.positionMax, dt)
	}

	private fun update(s: Strand, tx: Float, ty: Float, totalAngle: Float, threshold: Float, dt: Float) {
		s.x[0] = tx
		s.y[0] = ty
		val totalRadian = totalAngle / 180f * PI
		var gx = sin(totalRadian)
		var gy = cos(totalRadian)
		val gl = sqrt(gx * gx + gy * gy)
		gx /= gl; gy /= gl
		for (i in 1 until s.n) {
			val forceX = gx * s.acceleration[i - 1]
			val forceY = gy * s.acceleration[i - 1]
			s.lastX[i] = s.x[i]
			s.lastY[i] = s.y[i]
			val delay = s.delay[i - 1] * dt * 30f
			var dx = s.x[i] - s.x[i - 1]
			var dy = s.y[i] - s.y[i - 1]
			val radian = direction(s.gravityX[i], s.gravityY[i], gx, gy) / PhysicsEngine.AIR_RESISTANCE
			dx = cos(radian) * dx - dy * sin(radian)
			dy = sin(radian) * dx + dy * cos(radian)
			var px = s.x[i - 1] + dx + s.vx[i] * delay + forceX * delay * delay
			var py = s.y[i - 1] + dy + s.vy[i] * delay + forceY * delay * delay
			var nx = px - s.x[i - 1]
			var ny = py - s.y[i - 1]
			val length = sqrt(nx * nx + ny * ny)
			nx /= length; ny /= length
			px = s.x[i - 1] + nx * s.length[i - 1]
			py = s.y[i - 1] + ny * s.length[i - 1]
			if (kotlin.math.abs(px) < threshold) px = 0f
			s.x[i] = px
			s.y[i] = py
			if (delay != 0f) {
				s.vx[i] = (px - s.lastX[i]) / delay * s.mobility[i - 1]
				s.vy[i] = (py - s.lastY[i]) / delay * s.mobility[i - 1]
			}
			s.gravityX[i] = gx
			s.gravityY[i] = gy
		}
	}

	private fun output(s: Strand, o: PhysicsOutput): Float {
		val i = o.vertex
		val raw = when (o.type) {
			PhysicsSourceType.X -> s.x[i] - s.x[i - 1]
			PhysicsSourceType.ANGLE -> direction(if (i >= 2) s.x[i - 1] - s.x[i - 2] else 0f,
				if (i >= 2) s.y[i - 1] - s.y[i - 2] else 1f, s.x[i] - s.x[i - 1], s.y[i] - s.y[i - 1])
		}
		return if (o.reflect) -raw else raw
	}

	/** The angle from direction ([fromX], [fromY]) to ([toX], [toY]), within ±π. */
	private fun direction(fromX: Float, fromY: Float, toX: Float, toY: Float): Float =
		if (exact) PhysicsEngine.directionToRadian(fromX, fromY, toX, toY) else kotlin.math.atan2(fromX * toY - fromY * toX, fromX * toX + fromY * toY)

	private fun blend(current: Float, raw: Float, o: PhysicsOutput, range: PhysicsEngine.Range): Float {
		val value = (raw * PhysicsEngine.scaleOf(o)).coerceIn(minOf(range.min, range.max), maxOf(range.min, range.max))
		val weight = o.weight / PhysicsEngine.MAXIMUM_WEIGHT
		return if (weight >= 1f) value else current * (1f - weight) + value * weight
	}

	private companion object {
		const val PI = 3.1415926535897932384626433832795f
	}
}
