package io.github.psd2live.core

import io.github.psd2live.i18n.tr

/**
 * The physics groups PSD2Live generates: hair and eye presets, skeleton follow-through and swing
 * pendulums. [PhysicsCatalog] lays the user's groups over them.
 */
object PhysicsGenerator {
	const val FRONT_HAIR_ID = "PhysicsHairFront"
	const val BACK_HAIR_ID = "PhysicsHairBack"
	const val EYE_JELLY_ID = "PhysicsEyeJelly"
	const val EYE_L_BOUNCE_ID = "PhysicsEyeLBounce"
	const val EYE_R_BOUNCE_ID = "PhysicsEyeRBounce"
	const val MOUTH_SPRING_ID = "PhysicsMouthSpring"
	val presetIds = listOf(BACK_HAIR_ID, FRONT_HAIR_ID, EYE_JELLY_ID)

	/** One flag per preset: whether the art has the part, or whether the user left the preset on. */
	data class Presets(val frontHair: Boolean, val backHair: Boolean, val eyeJelly: Boolean, val hairSoftness: Float = 0.5f) {
		operator fun get(id: String) = when (id) {
			FRONT_HAIR_ID -> frontHair
			BACK_HAIR_ID -> backHair
			EYE_JELLY_ID -> eyeJelly
			else -> false
		}

		companion object {
			val All = Presets(true, true, true)

			fun present(analysis: PipelineAnalysis?): Presets {
				val layers = analysis?.layers.orEmpty().filter { it.opaquePixels > 0 }
				return Presets(layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR },
					layers.any { it.semantic.tag == SemanticTag.BACK_HAIR },
					layers.any { it.semantic.tag == SemanticTag.IRIDES })
			}

			fun enabled(config: PipelineConfig) = Presets(config.physicsFrontHair, config.physicsBackHair, config.physicsEyeJelly)

			/**
			 * What [config] generates for the art of [analysis]: the legacy hair pendulums unless a simulation or the hair
			 * chains move that hair.
			 */
			fun present(analysis: PipelineAnalysis?, config: PipelineConfig): Presets {
				val chains = config.recipe.base("hair") === io.github.psd2live.core.recipe.RigModules.HAIR_CHAINS
				return present(analysis, config.hairSimulationFront || chains, config.hairSimulationBack || chains)
					.copy(hairSoftness = if (chains) config.recipe.baseValue("hair", "softness") / 100f else 0.5f)
			}

			/** [present] without the hair the model preset simulates: that hair has no legacy pendulum. */
			fun present(analysis: PipelineAnalysis?, simulatedFront: Boolean, simulatedBack: Boolean) = present(analysis).let {
				it.copy(frontHair = it.frontHair && !simulatedFront, backHair = it.backHair && !simulatedBack)
			}
		}
	}

	/**
	 * The pendulums of the rig modules that the model's parameters ([available]) call for: the aftershock of each eye's
	 * blink (`eye.aftershock`), a short springy pendulum the openness swings, its angle the eye's bounce.
	 */
	internal fun moduleRules(available: Set<String>, hairSoftness: Float = 0.5f): List<RigPhysicsEdit> = buildList {
		for ((side, id) in listOf("L" to EYE_L_BOUNCE_ID, "R" to EYE_R_BOUNCE_ID)) {
			val open = "ParamEye${side}Open"; val bounce = "ParamEye${side}Bounce"
			if (open !in available || bounce !in available) continue
			add(RigPhysicsEdit(id = id, name = tr("model.physics.eyeBounce", side),
				inputs = listOf(PhysicsInput(open, 100f, PhysicsSourceType.X)),
				outputs = listOf(PhysicsOutput(bounce, 2, 1.4f)),
				segments = listOf(PhysicsSegment(1f, 0.9f, 0.6f, 1.6f), PhysicsSegment(1.2f, 0.85f, 0.45f, 1.8f)),
				normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f)))
		}
		addAll(io.github.psd2live.core.fit.HairChain.physics(available, hairSoftness))
		// Each brow's overshoot as it stops: a short springy pendulum on its height and angle.
		for (side in listOf("L", "R")) {
			val spring = "ParamBrow${side}Spring"
			if (spring !in available) continue
			val inputs = listOf(PhysicsInput("ParamBrow${side}Y", 70f, PhysicsSourceType.X), PhysicsInput("ParamBrow${side}Angle", 30f, PhysicsSourceType.X))
				.filter { it.parameter in available }
			if (inputs.isEmpty()) continue
			add(RigPhysicsEdit(id = "PhysicsBrow${side}Spring", name = tr("model.physics.browSpring", side), inputs = inputs,
				outputs = listOf(PhysicsOutput(spring, 2, 1.2f)),
				segments = listOf(PhysicsSegment(1f, 0.92f, 0.55f, 1.6f), PhysicsSegment(1f, 0.85f, 0.45f, 1.8f)),
				normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f)))
		}
		// The chest settling after the body moves and lifting with the breath: up and down on the nod, the lean and the
		// breath, side to side on the body's turn and sway.
		if ("ParamBustY" in available) add(RigPhysicsEdit(id = "PhysicsBustY", name = tr("model.physics.bust", "Y"),
			inputs = listOf(PhysicsInput("ParamBodyAngleY", 50f, PhysicsSourceType.X), PhysicsInput("ParamAngleY", 20f, PhysicsSourceType.X),
				PhysicsInput("ParamBreath", 30f, PhysicsSourceType.X)).filter { it.parameter in available },
			outputs = listOf(PhysicsOutput("ParamBustY", 2, 1.2f)),
			segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.4f), PhysicsSegment(1.2f, 0.85f, 0.45f, 1.6f)),
			normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f)))
		if ("ParamBustX" in available) add(RigPhysicsEdit(id = "PhysicsBustX", name = tr("model.physics.bust", "X"),
			inputs = listOf(PhysicsInput("ParamBodyAngleX", 60f, PhysicsSourceType.X), PhysicsInput("ParamBodyAngleZ", 40f, PhysicsSourceType.ANGLE))
				.filter { it.parameter in available },
			outputs = listOf(PhysicsOutput("ParamBustX", 2, 1.1f)),
			segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.4f), PhysicsSegment(1.2f, 0.85f, 0.45f, 1.6f)),
			normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f)))
		// The jaw's soft tissue settling after the mouth moves, mostly on the opening and a little on the form.
		if ("ParamMouthOpenY" in available && "ParamMouthSpring" in available) add(RigPhysicsEdit(id = MOUTH_SPRING_ID, name = tr("model.physics.mouthSpring"),
			inputs = listOf(PhysicsInput("ParamMouthOpenY", 80f, PhysicsSourceType.X), PhysicsInput("ParamMouthForm", 20f, PhysicsSourceType.X)),
			outputs = listOf(PhysicsOutput("ParamMouthSpring", 2, 1.2f)),
			segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.6f), PhysicsSegment(1.1f, 0.85f, 0.4f, 1.8f)),
			normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f)))
	}

	/** The head and body sway every hanging part follows. */
	internal fun headAndBodyInputs(available: Set<String>?) = listOf(
		PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X),
		PhysicsInput("ParamAngleZ", 60f, PhysicsSourceType.ANGLE),
		PhysicsInput("ParamBodyAngleX", 40f, PhysicsSourceType.X),
		PhysicsInput("ParamBodyAngleZ", 40f, PhysicsSourceType.ANGLE),
	).filter { available == null || it.parameter in available }

	/** Hair and eye presets for the parts [present] in the art. */
	internal fun presetRules(present: Presets, available: Set<String>? = null): List<RigPhysicsEdit> = buildList {
		if (present.backHair) add(hairRule(BACK_HAIR_ID, tr("model.physics.backHair"), "ParamHairBack", 2.061f, 15f, 0.95f, 0.8f, 1.5f, 30f, available))
		if (present.frontHair) add(hairRule(FRONT_HAIR_ID, tr("model.physics.frontHair"), "ParamHairFront", 1.522f, 7.9f, 0.77f, 1.45f, 0.8f, 10f, available))
		if (present.eyeJelly) add(RigPhysicsEdit(
			id = EYE_JELLY_ID,
			name = tr("model.physics.eyeJelly"),
			inputs = listOf(PhysicsInput("ParamEyeLOpen", 50f, PhysicsSourceType.X), PhysicsInput("ParamEyeROpen", 50f, PhysicsSourceType.X))
				.filter { available == null || it.parameter in available },
			outputs = listOf(PhysicsOutput("ParamEyeBallForm", 2, 0.32f)),
			segments = listOf(PhysicsSegment(1f, 0.88f, 0.18f, 1.9f), PhysicsSegment(1f, 0.80f, 0.32f, 2.2f)),
			normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f),
		))
	}

	private fun hairRule(id: String, name: String, output: String, scale: Float, length: Float, mobility: Float, delay: Float,
		acceleration: Float, angle: Float, available: Set<String>?) = RigPhysicsEdit(
		id, name, headAndBodyInputs(available), listOf(PhysicsOutput(output, 1, scale)),
		listOf(PhysicsSegment(length, mobility, delay, acceleration)), PhysicsNormalization(angleMin = -angle, angleMax = angle),
	)

	/**
	 * Follow-through on the loose appendages: every tail segment after the first trails the one it hangs
	 * from, and wings trail the body. Arms and legs stay off physics - they are posed and animated
	 * directly, and a simulation would overwrite both.
	 */
	internal fun skeletonRules(spec: SkeletonSpec?, available: Set<String>): List<RigPhysicsEdit> {
		if (spec?.enabled != true) return emptyList()
		return spec.bones.filter { it.role == BoneRole.WING || (it.role == BoneRole.TAIL && it.chainIndex > 1) }
			.mapNotNull { bone ->
				val input = when (bone.role) {
					BoneRole.TAIL -> spec.bone(bone.parentId ?: "")?.takeIf { it.role == BoneRole.TAIL }?.parameterId
						?: "ParamBodyAngleZ"
					else -> "ParamBodyAngleZ"
				}
				if (input !in available || bone.parameterId !in available || input == bone.parameterId) return@mapNotNull null
				val tail = bone.role == BoneRole.TAIL
				RigPhysicsEdit("PhysicsSkel_${bone.id}", bone.name, listOf(PhysicsInput(input)),
					listOf(PhysicsOutput(bone.parameterId, 1, if (tail) 0.75f else 0.4f)),
					listOf(PhysicsSegment((bone.length / 30f).coerceIn(3f, 16f), 0.55f, if (tail) 0.65f else 0.9f, 0.8f)),
					PhysicsNormalization(angleMin = -30f, angleMax = 30f))
			}
	}

	/**
	 * One pendulum per direction of every regenerating swing, with a vertex per segment. Left/right swings
	 * take the hair inputs. A rigid pendulum hanging straight down ignores vertical travel, so up/down swings
	 * feed head and body pitch in as sideways travel, and the resulting angle drives the up/down forms.
	 */
	internal fun swingRules(swings: List<RigSwingEdit>, available: Set<String>): List<RigPhysicsEdit> = swings.flatMap { swing ->
		if (swing.parameterIds.any { it !in available }) return@flatMap emptyList()
		swing.motions.mapNotNull { motion ->
			val physics = motion.physics ?: return@mapNotNull null
			val inputs = when (motion.kind) {
				SwingKind.LATERAL -> headAndBodyInputs(null)
				SwingKind.VERTICAL -> listOf(
					PhysicsInput("ParamAngleY", 60f, PhysicsSourceType.X),
					PhysicsInput("ParamBodyAngleY", 40f, PhysicsSourceType.X),
					PhysicsInput("ParamAngleZ", 20f, PhysicsSourceType.ANGLE),
				)
			}.filter { it.parameter in available && it.parameter !in swing.parameterIds }
			if (inputs.isEmpty()) return@mapNotNull null
			val id = swingPhysicsId(swing, motion.kind)
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
	internal fun swingPhysicsId(swing: RigSwingEdit, kind: SwingKind = swing.motions.first().kind): String =
		"PhysicsSwing_${swing.id}" + if (swing.motions.size == 1) "" else if (kind == SwingKind.LATERAL) "_X" else "_Y"

	/** The swing a generated group belongs to, if it is a swing pendulum. */
	fun swingOf(groupId: String, swings: List<RigSwingEdit>): RigSwingEdit? =
		swings.firstOrNull { swing -> swing.motions.any { swingPhysicsId(swing, it.kind) == groupId } }
}
