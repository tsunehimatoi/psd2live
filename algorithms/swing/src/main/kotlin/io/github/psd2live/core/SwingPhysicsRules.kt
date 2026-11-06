package io.github.psd2live.core

/** Swing-specific pendulum definitions, independent of generation settings and localization. */
object SwingPhysicsRules {
	private fun headAndBodyInputs() = listOf(
		PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X),
		PhysicsInput("ParamAngleZ", 60f, PhysicsSourceType.ANGLE),
		PhysicsInput("ParamBodyAngleX", 40f, PhysicsSourceType.X),
		PhysicsInput("ParamBodyAngleZ", 40f, PhysicsSourceType.ANGLE),
	)
	fun rules(swings: List<RigSwingEdit>, available: Set<String>): List<RigPhysicsEdit> = swings.flatMap { swing ->
		if (swing.parameterIds.any { it !in available }) return@flatMap emptyList()
		swing.motions.mapNotNull { motion ->
			val physics = motion.physics ?: return@mapNotNull null
			val inputs = when (motion.kind) {
				SwingKind.LATERAL -> headAndBodyInputs()
				SwingKind.VERTICAL -> listOf(
					PhysicsInput("ParamAngleY", 60f, PhysicsSourceType.X),
					PhysicsInput("ParamBodyAngleY", 40f, PhysicsSourceType.X),
					PhysicsInput("ParamAngleZ", 20f, PhysicsSourceType.ANGLE),
				)
			}.filter { it.parameter in available && it.parameter !in swing.parameterIds }
			if (inputs.isEmpty()) return@mapNotNull null
			val id = id(swing, motion.kind)
			val segment = PhysicsSegment(physics.length / motion.segments, physics.mobility, physics.delay, physics.acceleration)
			RigPhysicsEdit(
				id = id,
				name = if (swing.motions.size == 1) swing.name else "${swing.name} ${id.substringAfterLast('_')}",
				inputs = inputs,
				outputs = motion.parameterIds.mapIndexed { k, parameter -> PhysicsOutput(parameter, k + 1, physics.outputScale) },
				segments = List(motion.segments) { segment },
				normalization = PhysicsNormalization(angleMin = -30f, angleMax = 30f),
			)
		}
	}

	/** `PhysicsSwing_<id>` for a swing in one direction; with both, suffixed `_X` (left/right) or `_Y` (up/down). */
	fun id(swing: RigSwingEdit, kind: SwingKind = swing.motions.first().kind): String =
		"PhysicsSwing_${swing.id}" + if (swing.motions.size == 1) "" else if (kind == SwingKind.LATERAL) "_X" else "_Y"

}
