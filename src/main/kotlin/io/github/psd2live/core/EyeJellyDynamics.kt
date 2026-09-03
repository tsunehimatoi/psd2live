package io.github.psd2live.core

import kotlin.math.abs

/** Lightweight fallback matching the exported blink-driven pendulum when Cubism SDK is unavailable. */
internal class EyeJellyDynamics {
	var value: Float = 0f
		private set
	private var velocity = 0f
	private var previousOpenness = 1f

	fun advance(openness: Float, deltaTime: Float, enabled: Boolean): Float {
		val open = openness.coerceIn(0f, 1f)
		if (!enabled) {
			reset(open)
			return 0f
		}
		val dt = deltaTime.coerceIn(0.001f, 0.05f)
		val blinkVelocity = ((open - previousOpenness) / dt).coerceIn(-16f, 16f)
		val drive = (blinkVelocity * 0.055f).coerceIn(-0.85f, 0.85f)
		velocity += ((drive - value) * 86f - velocity * 10.5f) * dt
		value = (value + velocity * dt).coerceIn(-1f, 1f)
		previousOpenness = open
		if (abs(value) < 1e-4f && abs(velocity) < 1e-4f && abs(drive) < 1e-4f) {
			value = 0f
			velocity = 0f
		}
		return value
	}

	fun reset(openness: Float = 1f) {
		value = 0f
		velocity = 0f
		previousOpenness = openness.coerceIn(0f, 1f)
	}
}
