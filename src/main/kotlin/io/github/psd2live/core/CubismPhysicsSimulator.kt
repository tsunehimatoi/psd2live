package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Faithful implementation of Live2D Cubism's official physics particle simulation engine
 * (CubismPhysics::updateParticles).
 *
 * Simulates linked pendulum strands under gravity, root translation/rotation inertia,
 * air resistance, rod length constraints, and mobility velocity damping.
 */
class CubismPhysicsSimulator {

	data class Particle(
		var x: Float = 0f,
		var y: Float = 0f,
		var lastX: Float = 0f,
		var lastY: Float = 0f,
		var vx: Float = 0f,
		var vy: Float = 0f,
		var forceX: Float = 0f,
		var forceY: Float = 0f,
		var lastGravityX: Float = 0f,
		var lastGravityY: Float = -1f,
		var radius: Float = 15f,
		var mobility: Float = 0.95f,
		var delay: Float = 0.8f,
		var acceleration: Float = 1.5f,
	)

	val particles = mutableListOf<Particle>()
	var currentGravityX: Float = 0f
		private set
	var currentGravityY: Float = -1f
		private set

	init {
		reset(2, 15f, 0.95f, 0.8f, 1.5f)
	}

	/**
	 * Configures or re-initializes the particle strand.
	 * @param strandCount Number of particles including the fixed root (default 2: root + 1 bob).
	 */
	fun reset(
		strandCount: Int = 2,
		totalLength: Float = 15f,
		mobility: Float = 0.95f,
		delay: Float = 0.8f,
		acceleration: Float = 1.5f,
	) {
		particles.clear()
		// Root particle at (0, 0)
		particles.add(
			Particle(
				x = 0f,
				y = 0f,
				lastX = 0f,
				lastY = 0f,
				radius = 0f,
				mobility = 1f,
				delay = 1f,
				acceleration = 1f,
			),
		)

		val segments = (strandCount - 1).coerceAtLeast(1)
		val segRadius = totalLength / segments

		for (i in 1 until strandCount) {
			val py = -segRadius * i
			particles.add(
				Particle(
					x = 0f,
					y = py,
					lastX = 0f,
					lastY = py,
					radius = segRadius,
					mobility = mobility,
					delay = delay,
					acceleration = acceleration,
					lastGravityX = 0f,
					lastGravityY = -1f,
				),
			)
		}

		currentGravityX = 0f
		currentGravityY = -1f
	}

	/**
	 * Updates the physics simulation for one frame according to Live2D Cubism's updateParticles algorithm.
	 *
	 * @param totalTranslationX Root displacement X from position-type inputs (e.g. AngleX / BodyAngleX)
	 * @param totalTranslationY Root displacement Y
	 * @param totalAngleDegrees Coordinate system rotation in degrees from angle-type inputs (e.g. AngleZ)
	 * @param dt Elapsed delta time in seconds
	 * @param airResistance Live2D constant air resistance (default 5.0)
	 */
	fun update(
		totalTranslationX: Float,
		totalTranslationY: Float,
		totalAngleDegrees: Float,
		dt: Float,
		airResistance: Float = 5.0f,
	) {
		if (particles.size < 2) return

		val clampedDt = dt.coerceIn(0.001f, 0.05f)

		// 1. Set root position
		particles[0].x = totalTranslationX
		particles[0].y = totalTranslationY

		// 2. Compute gravity direction in the tilted coordinate frame
		// In Live2D: totalAngle rotates the gravity vector from downward (0, -1)
		val totalRadian = Math.toRadians(totalAngleDegrees.toDouble()).toFloat()
		currentGravityX = sin(totalRadian)
		currentGravityY = -cos(totalRadian)

		val gravLen = sqrt(currentGravityX * currentGravityX + currentGravityY * currentGravityY)
		if (gravLen > 0.0001f) {
			currentGravityX /= gravLen
			currentGravityY /= gravLen
		}

		val thresholdValue = 0.001f

		for (i in 1 until particles.size) {
			val current = particles[i]
			val previous = particles[i - 1]

			current.lastX = current.x
			current.lastY = current.y

			// Force = Gravity * Acceleration
			current.forceX = currentGravityX * current.acceleration
			current.forceY = currentGravityY * current.acceleration

			val delayFactor = current.delay * clampedDt * 30.0f
			var dirX = current.x - previous.x
			var dirY = current.y - previous.y

			// Air resistance angular rotation between lastGravity and currentGravity
			val dot = (current.lastGravityX * currentGravityX + current.lastGravityY * currentGravityY).coerceIn(-1f, 1f)
			val cross = current.lastGravityX * currentGravityY - current.lastGravityY * currentGravityX
			val radian = atan2(cross, dot) / airResistance

			val cosR = cos(radian)
			val sinR = sin(radian)
			val rotDirX = cosR * dirX - sinR * dirY
			val rotDirY = sinR * dirX + cosR * dirY

			// Apply translation, velocity and force
			val velStepX = current.vx * delayFactor
			val velStepY = current.vy * delayFactor
			val forceStepX = current.forceX * delayFactor * delayFactor
			val forceStepY = current.forceY * delayFactor * delayFactor

			var newX = previous.x + rotDirX + velStepX + forceStepX
			var newY = previous.y + rotDirY + velStepY + forceStepY

			// Length constraint: maintain distance equal to radius
			var newDirX = newX - previous.x
			var newDirY = newY - previous.y
			val length = sqrt(newDirX * newDirX + newDirY * newDirY)

			if (length > 0.0001f) {
				newDirX /= length
				newDirY /= length
			} else {
				newDirX = 0f
				newDirY = -1f
			}

			current.x = previous.x + newDirX * current.radius
			current.y = previous.y + newDirY * current.radius

			if (abs(current.x) < thresholdValue) {
				current.x = 0f
			}

			// Update velocity scaled by mobility
			if (delayFactor != 0f) {
				current.vx = ((current.x - current.lastX) / delayFactor) * current.mobility
				current.vy = ((current.y - current.lastY) / delayFactor) * current.mobility
			}

			current.forceX = 0f
			current.forceY = 0f
			current.lastGravityX = currentGravityX
			current.lastGravityY = currentGravityY
		}
	}

	/**
	 * Returns the output angle in degrees between particle [vertexIndex] and its parent.
	 * Positive angle = deflected to the right.
	 */
	fun getOutputAngleDegrees(vertexIndex: Int = 1): Float {
		if (vertexIndex !in 1 until particles.size) return 0f
		val previous = particles[vertexIndex - 1]
		val current = particles[vertexIndex]
		val dx = current.x - previous.x
		val dy = current.y - previous.y
		// At rest, dx = 0, dy = -radius, atan2(0, -(-radius)) = 0.
		val rad = atan2(dx, -dy)
		return Math.toDegrees(rad.toDouble()).toFloat()
	}

	/** Manually sets the angular position of particle [vertexIndex] (e.g. for drag/pluck interaction). */
	fun setParticleAngle(vertexIndex: Int = 1, angleDegrees: Float) {
		if (vertexIndex !in 1 until particles.size) return
		val previous = particles[vertexIndex - 1]
		val current = particles[vertexIndex]
		val rad = Math.toRadians(angleDegrees.toDouble()).toFloat()
		current.x = previous.x + sin(rad) * current.radius
		current.y = previous.y - cos(rad) * current.radius
		current.lastX = current.x
		current.lastY = current.y
		current.vx = 0f
		current.vy = 0f
	}
}
