package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.sin

/** Immutable animation clock; both process sampling and desktop pumps advance this same state. */
internal data class PreviewAnimationClock(val elapsed: Double = 0.0, val followX: Float = 0f, val followY: Float = 0f,
                                         val bodyX: Float = 0f, val bodyY: Float = 0f) {
    fun advance(dt: Float, animate: Boolean, config: PipelineConfig, pointer: Pair<Float, Float>?): PreviewAnimationClock {
        val elapsed = elapsed + if (animate) dt else 0f
        val idle = animate && config.motionBasic && config.motionIdle && config.rigEdits.motionPresets["Idle"]?.let { !it.deleted && !it.disabled } != false
        val x = pointer?.first ?: if (idle) (sin(elapsed * 0.47) * 0.12).toFloat() else 0f
        val y = pointer?.second?.let { -it } ?: if (idle) (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat() else 0f
        val response = (dt * 7.5f).coerceAtMost(1f)
        var fx = followX + (x - followX) * response; var fy = followY + (y - followY) * response
        var bx = bodyX + (fx - bodyX) * (dt * 2.6f).coerceAtMost(1f)
        var by = bodyY + (fy - bodyY) * (dt * 2.6f).coerceAtMost(1f)
        if (pointer == null) {
            if (abs(fx - x) < 0.001f) fx = x
            if (abs(fy - y) < 0.001f) fy = y
            if (abs(bx - fx) < 0.001f) bx = fx
            if (abs(by - fy) < 0.001f) by = fy
        }
        return PreviewAnimationClock(elapsed, fx, fy, bx, by)
    }

    /** Exact cascaded exponential response: fast head, softer body, independent of frame subdivision. */
    fun advanceTracking(dt: Float, pointer: Pair<Float, Float>?): PreviewAnimationClock {
        if (dt == 0f) return this
        val headRate = 18.0
        val bodyRate = 10.0
        val headDecay = exp(-headRate * dt)
        val bodyDecay = exp(-bodyRate * dt)
        fun head(value: Float, target: Float) = (target + (value - target) * headDecay).toFloat()
        fun body(value: Float, previousHead: Float, target: Float) =
            (target + (value - target) * bodyDecay +
                bodyRate * (previousHead - target) * (bodyDecay - headDecay) / (headRate - bodyRate)).toFloat()
        val x = pointer?.first ?: 0f
        val y = pointer?.second?.let { -it } ?: 0f
        return copy(followX = head(followX, x), followY = head(followY, y),
            bodyX = body(bodyX, followX, x), bodyY = body(bodyY, followY, y))
    }

    fun sample(model: RigPreviewModel, animate: Boolean, tracking: Boolean, motion: Map<ParameterId, Float>): Map<ParameterId, Float> {
        val config = model.config
        if (config.meshOnly) return model.rig.puppet.parameters.associate { it.id to it.default }
        val settings = MotionPresetSettings.of(config, "Idle")
        val hasIdle = animate && config.motionBasic && config.motionIdle && !settings.deleted && !settings.disabled
        val idle = when {
            !hasIdle || settings.deleted -> emptyMap()
            MotionClips.overrideOf(config.rigEdits.motionClips, "Idle") != null -> MotionClips.sampleAll(MotionClips.overrideOf(config.rigEdits.motionClips, "Idle")!!, elapsed, true)
            else -> SkeletonMotions.liveIdle(config.rigEdits.skeleton, elapsed, settings)
        }
        fun value(id: ParameterId) = motion[id] ?: idle[id] ?: 0f
        val phase = elapsed % 4.6
        val blinking = animate && config.motionBasic && config.motionBlink && config.rigEdits.motionPresets["Blink"]?.let { !it.deleted && !it.disabled } != false
        val blink = if (settings.natural) (if (blinking) MotionClips.sample(MotionGenerator.naturalBlinkTracks[0], (elapsed % SkeletonMotions.IDLE_DURATION.toDouble()).toFloat()) else 1f)
            else if (blinking && phase in 4.18..4.46) (1.0 - sin((phase - 4.18) / 0.28 * PI)).toFloat().coerceIn(0f, 1f) else 1f
        val mouthPhase = elapsed % 5.8
        val follow = hasIdle || tracking
        val base = mapOf(
            StandardParameters.ANGLE_X to ((if (follow) followX * 38f else 0f) + value(StandardParameters.ANGLE_X)),
            StandardParameters.ANGLE_Y to ((if (follow) -followY * 24f else 0f) + value(StandardParameters.ANGLE_Y)),
            StandardParameters.ANGLE_Z to value(StandardParameters.ANGLE_Z),
            StandardParameters.BODY_X to ((if (follow) bodyX * 8f else 0f) + value(StandardParameters.BODY_X)).coerceIn(-10f, 10f),
            StandardParameters.BODY_Y to ((if (follow) -bodyY * 8f else 0f) + value(StandardParameters.BODY_Y)).coerceIn(-10f, 10f),
            StandardParameters.BODY_Z to value(StandardParameters.BODY_Z),
            StandardParameters.EYE_BALL_X to if (follow) followX.coerceIn(-1f, 1f) else 0f,
            StandardParameters.EYE_BALL_Y to if (follow) (-followY).coerceIn(-1f, 1f) else 0f,
            StandardParameters.EYE_L_OPEN to minOf(blink, motion[StandardParameters.EYE_L_OPEN] ?: 1f, idle[StandardParameters.EYE_L_OPEN] ?: 1f),
            StandardParameters.EYE_R_OPEN to minOf(blink, motion[StandardParameters.EYE_R_OPEN] ?: 1f, idle[StandardParameters.EYE_R_OPEN] ?: 1f),
            StandardParameters.MOUTH_FORM to if (hasIdle) sin(elapsed * 0.41).toFloat() * 0.18f else 0f,
            StandardParameters.MOUTH_OPEN to if (hasIdle && mouthPhase in 1.25..2.45) sin((mouthPhase - 1.25) / 1.2 * PI).toFloat().coerceAtLeast(0f) else 0f,
            StandardParameters.BREATH to value(StandardParameters.BREATH))
        val ids = model.rig.puppet.parameters.mapTo(hashSetOf()) { it.id }
        return boundedPreviewPose((base + idle.filterKeys { it !in base } + motion.filterKeys { it !in base }).filterKeys { it in ids }, model.rig.puppet.parameters)
    }
}
