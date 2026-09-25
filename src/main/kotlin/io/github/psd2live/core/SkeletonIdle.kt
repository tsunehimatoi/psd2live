package io.github.psd2live.core

import org.umamo.runtime.model.ParameterId
import kotlin.math.PI
import kotlin.math.sin

/** A small, looping neutral motion shared by the live preview and exported idle motion. */
object SkeletonIdle {
	const val durationSeconds = 6f

	fun sample(spec: SkeletonSpec?, seconds: Double): Map<ParameterId, Float> {
		if (spec?.enabled != true) return emptyMap()
		val cycle = 2.0 * PI * seconds / durationSeconds
		val bones = spec.bones.asSequence().filterNot { it.role.anchor }.associate { bone ->
			val sidePhase = if (bone.side == Side.RIGHT) PI else 0.0
			val (amplitude, phase) = when (bone.role) {
				BoneRole.UPPER_ARM -> 3.0 to 0.0
				BoneRole.FOREARM -> 4.0 to 0.55
				BoneRole.HAND -> 2.0 to 0.85
				BoneRole.THIGH -> 1.5 to 0.35
				BoneRole.SHIN -> 2.0 to 0.75
				BoneRole.FOOT -> 1.0 to 1.0
				BoneRole.TAIL -> 5.0 to (bone.chainIndex - 1).coerceAtLeast(0) * -0.4
				BoneRole.WING -> 3.5 to 0.3
				else -> 0.0 to 0.0
			}
			ParameterId(bone.parameterId) to (sin(cycle + sidePhase + phase) * amplitude).toFloat()
		}
		val phase = ((seconds % durationSeconds) + durationSeconds) % durationSeconds
		val squat = if (phase in 4.5..5.5 && spec.bones.any { it.role == BoneRole.THIGH || it.role == BoneRole.SHIN }) {
			(sin((phase - 4.5) * PI) * 0.18).toFloat().coerceAtLeast(0f)
		} else 0f
		return if (spec.bones.any { it.role == BoneRole.THIGH || it.role == BoneRole.SHIN })
			bones + (ParameterId("ParamSquat") to squat) else bones
	}
}
