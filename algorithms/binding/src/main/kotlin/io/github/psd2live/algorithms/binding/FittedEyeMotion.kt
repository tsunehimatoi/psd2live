package io.github.psd2live.algorithms.binding

class FittedEyeMotion(val rigs: Map<Side, EyeRig>, val options: EyeOptions = EyeOptions()) {
	/** Fixed source-space ownership, also for a layer carrying material for both eyes. */
	fun sideAt(layerId: String, x: Float, y: Float): Side? = rigs.minByOrNull { (_, eye) ->
		val (u, v) = eye.fit.toEye(x, y)
		val samples = eye.fit.artwork?.components(layerId).orEmpty().flatMap { c ->
			c.samples.filterIndexed { i, _ -> i % (c.samples.size / 256).coerceAtLeast(1) == 0 }
		}
		(samples.minOfOrNull { it.distance(EyePoint(u, v)) } ?: kotlin.math.hypot(u - u.coerceIn(0f, 1f),
			v - v.coerceIn(EyeFit.sample(eye.fit.upper, u), EyeFit.sample(eye.fit.lower, u)))) * eye.fit.width
	}?.key
}
