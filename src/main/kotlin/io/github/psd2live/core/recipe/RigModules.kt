package io.github.psd2live.core.recipe

import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.SemanticTag

/**
 * Every area of the generated rig with its bases and modules: the one registry the generator, the model-preset panel,
 * the settings and MCP `module_catalog` read. The design of each module - what it reads, what it makes, which merits
 * of a hand-made rig it brings - is in docs/zh/spec/RIG_PRESETS.md and the module catalogue it links.
 *
 * Areas come in the order the panel shows their cards. Each has its legacy base first, what a project saved before the
 * registry generates; a newer base is registered after it and becomes the default of new projects.
 */
object RigModules {
	private fun legacy(area: String) = RigBase("$area/legacy")

	private val eyeTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)

	/** The eye jelly pendulum: the irides wobble after a blink. A switch older than the registry ([io.github.psd2live.core.PipelineConfig.physicsEyeJelly]). */
	val EYE_JELLY = RigModule("eye.jelly", ModuleTier.MAJOR, setting = "physicsEyeJelly", physicsGroups = listOf(PhysicsGenerator.EYE_JELLY_ID),
		applies = { it.has(SemanticTag.IRIDES) },
		estimate = { ModuleCost(emptyList(), physicsGroups = 1) })

	/** Every part of the eyes drawn over the bangs, half seen through ([io.github.psd2live.core.SeeThroughLayers]). */
	val EYE_SEE_THROUGH = RigModule("eye.see-through", ModuleTier.CORE, setting = "seeThroughEyes",
		applies = { subject -> subject.has(SemanticTag.FRONT_HAIR) && eyeTags.any { subject.has(it) } })

	/** The eyebrows drawn over the bangs, half seen through. */
	val BROW_SEE_THROUGH = RigModule("brow.see-through", ModuleTier.CORE, setting = "seeThroughBrows",
		applies = { it.has(SemanticTag.FRONT_HAIR) && it.has(SemanticTag.EYEBROW) })

	/** The generated lip lines that close the mouth over an open-mouth drawing ([io.github.psd2live.core.MouthLipLayers]). */
	val MOUTH_OUTLINE = RigModule("mouth.outline", ModuleTier.CORE, setting = "mouthOutlineEnabled",
		applies = { it.has(SemanticTag.MOUTH) || it.has(SemanticTag.MOUTH_OPEN) })

	private val P = io.github.psd2live.core.RigTuning.Unit.PERCENT

	/**
	 * The eyes fitted from their drawn outline ([io.github.psd2live.core.fit.EyeRig]): the lids close over a pseudo-3D
	 * eyeball, keeping their arc, the white, lashes and lid skin each following them their own way.
	 */
	val EYE_FITTED = RigBase("eye/fitted", knobs = listOf(
		RecipeKnob("lowerShare", P, 0f..50f, 5f),
		RecipeKnob("roundness", P, 40f..160f, 100f),
		RecipeKnob("lashSquash", P, 20f..100f, 60f, advanced = true),
	))

	private const val FITTED_EYE = "eye/fitted"
	private fun fittedEye(subject: RecipeSubject) = subject.has(SemanticTag.EYEWHITE) && subject.has(SemanticTag.EYELASH)
	private fun pair(name: String, min: Float, max: Float, default: Float, group: String = "ParamGroupEyes") = listOf(
		ModuleParameter("ParamEyeL$name", "model.parameter.eyeL$name", min, max, default, group),
		ModuleParameter("ParamEyeR$name", "model.parameter.eyeR$name", min, max, default, group),
	)

	/** The smile of the eyes: closing, the lids meet in an upward arch and the lower lid pushes up; open, the eye is as drawn. */
	val EYE_SMILE = RigModule("eye.smile", ModuleTier.CORE, base = FITTED_EYE, applies = ::fittedEye,
		knobs = listOf(RecipeKnob("arch", P, 0f..200f, 100f), RecipeKnob("push", P, 0f..100f, 50f)),
		defines = pair("Smile", 0f, 1f, 0f))

	/** The eye widening past its drawn opening (openness up to 2), surprised, the iris narrowing. */
	val EYE_WIDE = RigModule("eye.wide", ModuleTier.MAJOR, base = FITTED_EYE, applies = ::fittedEye,
		knobs = listOf(RecipeKnob("lift", P, 10f..80f, 35f)))

	/** The lids following the gaze: looking up lifts the upper lid, looking down drops it, and its peak leans with the eye. */
	val EYE_GAZE_LID = RigModule("eye.gaze-lid", ModuleTier.MAJOR, base = FITTED_EYE, applies = ::fittedEye,
		knobs = listOf(RecipeKnob("follow", P, 0f..200f, 100f)))

	/** The emotions of the eye: angry (the inner lid presses down) and sad (the inner lid lifts, the outer droops), and the squint. */
	val EYE_EMOTION = RigModule("eye.emotion", ModuleTier.MAJOR, base = FITTED_EYE, applies = ::fittedEye,
		knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)),
		defines = pair("Form", -1f, 1f, 0f) + pair("Squint", 0f, 1f, 0f))

	/** Lid lines and lid shadows sinking into the closing lid before it shuts. */
	val EYE_LID_FADE = RigModule("eye.lid-fade", ModuleTier.MAJOR, base = FITTED_EYE,
		applies = { it.has(SemanticTag.EYELASH, io.github.psd2live.core.PartRole.LID) || it.has(SemanticTag.EYELASH, io.github.psd2live.core.PartRole.SHADOW) })

	/** The aftershock of a blink: the lid overshoots and settles, the lashes flick, driven by a pendulum on the openness. */
	val EYE_AFTERSHOCK = RigModule("eye.aftershock", ModuleTier.MINOR, base = FITTED_EYE, applies = ::fittedEye,
		knobs = listOf(RecipeKnob("softness", P, 0f..200f, 100f)),
		defines = pair("Bounce", -1f, 1f, 0f, "ParamGroupPhysics"),
		physicsGroups = listOf(PhysicsGenerator.EYE_L_BOUNCE_ID, PhysicsGenerator.EYE_R_BOUNCE_ID))

	/** The highlights drifting against the turn of the head, as a light that stays put, and going out before the lid covers them. */
	val EYE_HIGHLIGHT = RigModule("eye.highlight", ModuleTier.MAJOR, base = FITTED_EYE,
		applies = { it.has(SemanticTag.IRIDES, io.github.psd2live.core.PartRole.HIGHLIGHT) },
		knobs = listOf(RecipeKnob("parallax", P, 0f..200f, 100f)))

	/** The iris squashing toward its bottom as the lid closes over it. */
	val EYE_SQUASH = RigModule("eye.squash", ModuleTier.MINOR, base = FITTED_EYE, applies = { it.has(SemanticTag.IRIDES) && fittedEye(it) })

	/**
	 * The brows read from their stroke in the head's frame ([io.github.psd2live.core.fit.BrowRig]): raised and lowered as a
	 * whole, the base the brow modules build on.
	 */
	val BROW_FITTED = RigBase("brow/fitted", knobs = listOf(RecipeKnob("lift", P, 20f..100f, 45f)))
	private const val FITTED_BROW = "brow/fitted"
	private fun brow(name: String, min: Float, max: Float) = listOf(
		ModuleParameter("ParamBrowL$name", "model.parameter.browL$name", min, max, 0f, "ParamGroupBrows"),
		ModuleParameter("ParamBrowR$name", "model.parameter.browR$name", min, max, 0f, "ParamGroupBrows"),
	)

	/** The brow's own shapes: sideways, its angle swept along an arc (inner end down angry … up worried), and its form (arched … flat). */
	val BROW_SHAPE = RigModule("brow.shape", ModuleTier.CORE, base = FITTED_BROW, applies = { it.has(SemanticTag.EYEBROW) },
		knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)),
		defines = brow("X", -1f, 1f) + brow("Angle", -1f, 1f) + brow("Form", -1f, 1f))

	/** The brow and the upper lid as one skin: a raised brow lifts the lid, a lowered or knitted brow presses it into a glare. */
	val BROW_LID = RigModule("brow.lid-coupling", ModuleTier.MAJOR, base = FITTED_BROW,
		applies = { it.has(SemanticTag.EYEBROW) && fittedEye(it) }, knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)))

	/** The brow settling a little with the blink and lifting with a widened eye. */
	val BROW_BLINK = RigModule("brow.blink", ModuleTier.MAJOR, base = FITTED_BROW, applies = { it.has(SemanticTag.EYEBROW) },
		knobs = listOf(RecipeKnob("dip", P, 0f..200f, 100f)))

	/**
	 * The mouth fitted from its drawn open outline ([io.github.psd2live.core.fit.MouthRig]): it opens in stages, the
	 * lower lip first into a crescent, then an oval, then the drawing, and its form runs from a pout (closed a frown, open
	 * a round "o") to a smile (corners up, open a wide D), with keys between the ends.
	 */
	val MOUTH_FITTED = RigBase("mouth/fitted", knobs = listOf(
		RecipeKnob("early", P, 20f..60f, 35f),
		RecipeKnob("form", P, 50f..150f, 100f),
		RecipeKnob("inside", P, 0f..100f, 50f),
	))
	private const val FITTED_MOUTH = "mouth/fitted"
	private fun mouthOpen(subject: RecipeSubject) = subject.has(SemanticTag.MOUTH) || subject.has(SemanticTag.MOUTH_OPEN)
	private fun mouth(id: String, name: String, min: Float, max: Float, group: String = "ParamGroupMouth") = ModuleParameter(id, "model.parameter.$name", min, max, 0f, group)

	/** The five vowels lip sync drives, each a mouth shape of its own adding to the opening and the form. */
	val MOUTH_VOWEL = RigModule("mouth.vowel", ModuleTier.MAJOR, base = FITTED_MOUTH, applies = ::mouthOpen,
		defines = listOf('A', 'I', 'U', 'E', 'O').map { mouth("Param$it", "vowel$it", 0f, 1f) })

	/** The face capture's mouth movements: sideways, pucker and widen, shrug, lips pressed or parted, the funnel, and the cheeks puffing. */
	val MOUTH_CAPTURE = RigModule("mouth.capture", ModuleTier.MAJOR, base = FITTED_MOUTH, applies = ::mouthOpen,
		defines = listOf(mouth("ParamMouthX", "mouthX", -1f, 1f), mouth("ParamMouthPuckerWiden", "mouthPuckerWiden", -1f, 1f),
			mouth("ParamMouthShrug", "mouthShrug", 0f, 1f), mouth("ParamMouthPressLipOpen", "mouthPressLipOpen", -1f, 1f),
			mouth("ParamMouthFunnel", "mouthFunnel", 0f, 1f),
			mouth("ParamCheekPuff", "cheekPuff", 0f, 1f), mouth("ParamJawOpen", "jawOpen", 0f, 1f)))

	/** The tongue put out, on art with a tongue layer: the mouth opens and the tongue slides down past the lower lip's place. */
	val MOUTH_TONGUE = RigModule("mouth.tongue", ModuleTier.MINOR, requires = listOf("mouth.capture"), base = FITTED_MOUTH,
		applies = { it.has(SemanticTag.TONGUE) }, defines = listOf(mouth("ParamTongueOut", "tongueOut", 0f, 1f)))

	/** The jaw: the chin and the lower face drop with the opening, the cheeks narrowing a little and the nose nudged. */
	val MOUTH_JAW = RigModule("mouth.jaw", ModuleTier.CORE, base = FITTED_MOUTH, applies = { mouthOpen(it) && it.has(SemanticTag.FACE) },
		knobs = listOf(RecipeKnob("share", P, 5f..30f, 15f)))

	/** The smiling mouth's cheeks pushing the lower lids up. */
	val MOUTH_CHEEK_LID = RigModule("mouth.cheek-lid", ModuleTier.MINOR, base = FITTED_MOUTH, applies = { mouthOpen(it) && fittedEye(it) },
		knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)))

	/** The mouth's inside - teeth, tongue, the dark of the mouth - lagging behind the lips as the head turns. */
	val MOUTH_DEPTH = RigModule("mouth.interior-depth", ModuleTier.MAJOR, base = FITTED_MOUTH,
		applies = { it.has(SemanticTag.TOOTH_T) || it.has(SemanticTag.TOOTH_B) || it.has(SemanticTag.TONGUE) || it.has(SemanticTag.MOUTH_OPEN) },
		knobs = listOf(RecipeKnob("depth", P, 0f..200f, 100f)))

	/** The soft tissue settling after the mouth moves: a pendulum on the opening sways the jaw. */
	val MOUTH_SPRING = RigModule("mouth.spring", ModuleTier.MINOR, requires = listOf("mouth.jaw"), base = FITTED_MOUTH,
		applies = { mouthOpen(it) && it.has(SemanticTag.FACE) },
		defines = listOf(mouth("ParamMouthSpring", "mouthSpring", -1f, 1f, "ParamGroupPhysics")),
		physicsGroups = listOf(PhysicsGenerator.MOUTH_SPRING_ID))

	/**
	 * The head turning in depth ([io.github.psd2live.core.fit.HeadTurn]): every layer of the head moves round its own
	 * shell of the head - the features slide across the face and gather on the far side, the front hair runs ahead of
	 * the face and the back hair goes the other way, the far ear tucks away.
	 */
	val FACE_DEPTH_TURN = RigModule("face.depth-turn", ModuleTier.CORE, applies = { it.has(SemanticTag.FACE) },
		knobs = listOf(RecipeKnob("turn", P, 0f..100f, 45f), RecipeKnob("depth", P, 0f..200f, 100f)))

	/** The skin's overlays - blush, shading, the bangs' shadow on the face - clipped to the face, so a turn never carries them off it. */
	val FACE_SKIN_CLIP = RigModule("face.skin-clip", ModuleTier.MAJOR,
		applies = { it.has(SemanticTag.FACE) && (it.has(SemanticTag.FACE_DETAIL) || it.has(SemanticTag.FRONT_HAIR, io.github.psd2live.core.PartRole.SHADOW)) })

	/**
	 * Hair in chains ([io.github.psd2live.core.fit.HairChain]): every hair layer bends in segments on pendulums of its
	 * own kind - bangs short and light, side locks longer, back hair long and slow, an ahoge springy, a ponytail a whip -
	 * a wide layer in three locks that fall out of step, and it bounces as the head nods. It replaces the one sway of the
	 * front and the back hair.
	 */
	val HAIR_CHAINS = RigBase("hair/chains", knobs = listOf(RecipeKnob("swing", P, 30f..200f, 100f), RecipeKnob("softness", P, 0f..100f, 50f)))

	/** A skirt swaying from the waist in three panels out of step, its hem flaring out and drawing in. */
	val OUTFIT_SKIRT = RigModule("outfit.skirt", ModuleTier.CORE, applies = { it.has(SemanticTag.BOTTOMWEAR, io.github.psd2live.core.PartRole.SKIRT) ||
		it.has(SemanticTag.TOPWEAR, io.github.psd2live.core.PartRole.SKIRT) }, knobs = listOf(RecipeKnob("swing", P, 30f..200f, 100f)))

	/** Ribbons trailing and pendants swinging from where they hang, each on its own pendulum. */
	val OUTFIT_RIBBONS = RigModule("outfit.ribbons", ModuleTier.MAJOR, applies = { subject ->
		io.github.psd2live.core.PartRole.RIBBON.tags.any { subject.has(it, io.github.psd2live.core.PartRole.RIBBON) } ||
			io.github.psd2live.core.PartRole.PENDANT.tags.any { subject.has(it, io.github.psd2live.core.PartRole.PENDANT) }
	}, knobs = listOf(RecipeKnob("swing", P, 30f..200f, 100f)))

	/**
	 * The chest rising and settling with the breath and the body's moves ([io.github.psd2live.core.fit.BustRig]), on
	 * pendulums of its own.
	 */
	val BODY_BUST = RigModule("body.bust", ModuleTier.MAJOR, applies = { it.has(SemanticTag.TOPWEAR) && it.has(SemanticTag.FACE) },
		knobs = listOf(RecipeKnob("bounce", P, 0f..200f, 100f)),
		defines = listOf(ModuleParameter("ParamBustY", "model.parameter.bustY", -1f, 1f, 0f, "ParamGroupPhysics"),
			ModuleParameter("ParamBustX", "model.parameter.bustX", -1f, 1f, 0f, "ParamGroupPhysics")),
		physicsGroups = listOf("PhysicsBustY", "PhysicsBustX"))

	/** The face flushing: blush layers deepen and the skin warms on Cheek; without a blush layer the skin's color alone changes. */
	val EXPRESSION_CHEEK = RigModule("expression.cheek", ModuleTier.MAJOR, applies = { it.has(SemanticTag.FACE) },
		knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)),
		defines = listOf(ModuleParameter("ParamCheek", "model.parameter.cheek", 0f, 1f, 0f, "ParamGroupFace")))

	/** A gloomy face: the skin darkens and cools, the highlights go out. */
	val EXPRESSION_GLOOM = RigModule("expression.gloom", ModuleTier.MINOR, applies = { it.has(SemanticTag.FACE) },
		knobs = listOf(RecipeKnob("strength", P, 0f..200f, 100f)),
		defines = listOf(ModuleParameter("ParamGloom", "model.parameter.gloom", 0f, 1f, 0f, "ParamGroupFace")))

	/**
	 * Motions that move like a person ([io.github.psd2live.core.MotionGenerator.naturalBlinkTracks]): blinks that shut
	 * fast and open slowly at uneven gaps, now and then twice, one eye a frame behind the other; the idle's eyes
	 * glancing away and back now and then.
	 */
	val MOTION_NATURAL = RigBase("motion/natural")

	/** The brow overshooting a little and settling as it stops, on a pendulum of its height and angle. */
	val BROW_SPRING = RigModule("brow.spring", ModuleTier.MINOR, base = FITTED_BROW, applies = { it.has(SemanticTag.EYEBROW) },
		defines = listOf(ModuleParameter("ParamBrowLSpring", "model.parameter.browLSpring", -1f, 1f, 0f, "ParamGroupPhysics"),
			ModuleParameter("ParamBrowRSpring", "model.parameter.browRSpring", -1f, 1f, 0f, "ParamGroupPhysics")),
		physicsGroups = listOf("PhysicsBrowLSpring", "PhysicsBrowRSpring"))

	/** Animal ears, tails and wings swinging in chains of their own; the ears twitch with the blink. */
	val OUTFIT_CREATURE = RigModule("outfit.creature", ModuleTier.MAJOR, applies = { subject ->
		subject.has(SemanticTag.EARS, io.github.psd2live.core.PartRole.ANIMAL) || subject.has(SemanticTag.HEADWEAR, io.github.psd2live.core.PartRole.ANIMAL) ||
			subject.has(SemanticTag.TAIL) || subject.has(SemanticTag.WINGS)
	}, knobs = listOf(RecipeKnob("swing", P, 30f..200f, 100f)))

	/**
	 * The neck sharing the head's tilt: its top follows the head as it leans, its base stays on the shoulders; a shadow
	 * the chin casts on the neck goes with the head and is clipped to the neck.
	 */
	val FACE_NECK = RigModule("face.neck", ModuleTier.MAJOR, applies = { it.has(SemanticTag.NECK) && it.has(SemanticTag.FACE) })

	/**
	 * The hanging arms swinging a little behind the body, from the shoulder, more at the hand than at the elbow, on a
	 * pendulum of each arm. A posed skeleton moves the arms itself.
	 */
	val BODY_ARMS = RigModule("body.arms", ModuleTier.MAJOR, applies = { it.has(SemanticTag.HANDWEAR) },
		defines = listOf(ModuleParameter("ParamArmLSwing", "model.parameter.armLSwing", -1f, 1f, 0f, "ParamGroupPhysics"),
			ModuleParameter("ParamArmRSwing", "model.parameter.armRSwing", -1f, 1f, 0f, "ParamGroupPhysics")),
		physicsGroups = listOf("PhysicsArmLSwing", "PhysicsArmRSwing"))

	/**
	 * Toggles and switches choreographed: a switch between variants fades the incoming one in before the outgoing one
	 * fades out, so the change never shows a hole or two half-seen layers, and every toggle or switch gives the hanging
	 * chains a small nudge as it flips.
	 */
	val EXPRESSION_TOGGLES = RigModule("expression.toggles", ModuleTier.MAJOR, applies = { true })

	val areas: List<RigArea> = listOf(
		RigArea("face", listOf(legacy("face")), listOf(FACE_DEPTH_TURN, FACE_SKIN_CLIP, FACE_NECK)),
		RigArea("eye", listOf(legacy("eye"), EYE_FITTED), listOf(EYE_SEE_THROUGH, EYE_SMILE, EYE_WIDE, EYE_GAZE_LID, EYE_EMOTION, EYE_LID_FADE,
			EYE_HIGHLIGHT, EYE_JELLY, EYE_AFTERSHOCK, EYE_SQUASH)),
		RigArea("brow", listOf(legacy("brow"), BROW_FITTED), listOf(BROW_SEE_THROUGH, BROW_SHAPE, BROW_LID, BROW_BLINK, BROW_SPRING)),
		RigArea("mouth", listOf(legacy("mouth"), MOUTH_FITTED), listOf(MOUTH_OUTLINE, MOUTH_VOWEL, MOUTH_CAPTURE, MOUTH_TONGUE, MOUTH_JAW, MOUTH_CHEEK_LID, MOUTH_DEPTH, MOUTH_SPRING)),
		RigArea("hair", listOf(legacy("hair"), HAIR_CHAINS), emptyList()),
		RigArea("body", listOf(legacy("body")), listOf(BODY_BUST, BODY_ARMS)),
		RigArea("outfit", listOf(legacy("outfit")), listOf(OUTFIT_SKIRT, OUTFIT_RIBBONS, OUTFIT_CREATURE)),
		RigArea("expression", listOf(legacy("expression")), listOf(EXPRESSION_CHEEK, EXPRESSION_GLOOM, EXPRESSION_TOGGLES)),
		RigArea("motion", listOf(legacy("motion"), MOTION_NATURAL), emptyList()),
	)

	private val areaById = areas.associateBy { it.id }
	val modules: List<RigModule> = areas.flatMap { it.modules }
	private val moduleById = modules.associateBy { it.id }

	init {
		require(areaById.size == areas.size && moduleById.size == modules.size) { "Duplicate area or module" }
		require(areas.flatMap { it.bases }.map { it.id }.toSet().size == areas.sumOf { it.bases.size }) { "Duplicate base" }
		require(modules.all { module -> module.requires.all { it in moduleById } }) { "A module requires an unknown module" }
	}

	/** The value of a module's older [setting] in [config]: the switch the module stands for. */
	fun setting(config: io.github.psd2live.core.PipelineConfig, setting: String): Boolean = when (setting) {
		"physicsEyeJelly" -> config.physicsEyeJelly
		"seeThroughEyes" -> config.seeThroughEyes
		"seeThroughBrows" -> config.seeThroughBrows
		"mouthOutlineEnabled" -> config.mouthOutlineEnabled
		else -> error("Unknown module setting: $setting")
	}

	/** Whether [config] builds [module] for the art [subject] shows: switched on, its prerequisites too, and applying. */
	fun builds(module: RigModule, config: io.github.psd2live.core.PipelineConfig, subject: RecipeSubject): Boolean =
		module.applies(subject) && config.recipe.switchedOn(module) { setting(config, it) } &&
			(module.base == null || config.recipe.base(module.area).id == module.base)

	/** The parameters the modules [config] builds for [subject] add, in registry order. */
	fun parameters(config: io.github.psd2live.core.PipelineConfig, subject: RecipeSubject): List<ModuleParameter> =
		modules.filter { builds(it, config, subject) }.flatMap { it.defines }

	fun area(id: String): RigArea? = areaById[id]
	fun module(id: String): RigModule? = moduleById[id]
	fun base(id: String): RigBase? = area(id.substringBefore('/'))?.bases?.firstOrNull { it.id == id }

	/** The modules that build on [id], directly or through another: they go off with it. */
	fun dependents(id: String): List<RigModule> {
		val found = LinkedHashSet<RigModule>()
		fun visit(target: String) {
			for (module in modules) if (target in module.requires && found.add(module)) visit(module.id)
		}
		visit(id)
		return found.toList()
	}

	/**
	 * Which registry modules a preset of [tier]s keeps on: [ModuleTier.CORE] alone is the minimal model, with
	 * [ModuleTier.MAJOR] the standard one, everything the full one.
	 */
	fun presetOn(module: RigModule, tiers: Set<ModuleTier>): Boolean = module.tier in tiers

	/**
	 * The modules to switch off so the rig adds at most [budget] parameters: of the [on] modules, those that matter
	 * least ([ModuleTier]) and cost the most parameters first, until the rest fit; [cost] gives each module's.
	 */
	fun withinBudget(on: List<RigModule>, budget: Int, cost: (RigModule) -> Int): Set<String> {
		var total = on.sumOf(cost)
		val off = LinkedHashSet<String>()
		for (module in on.sortedWith(compareByDescending<RigModule> { it.tier.ordinal }.thenByDescending(cost))) {
			if (total <= budget) break
			if (module.id in off) continue
			val dropped = listOf(module) + dependents(module.id).filter { it in on && it.id !in off }
			dropped.forEach { off += it.id }
			total -= dropped.sumOf(cost)
		}
		return off
	}
}
