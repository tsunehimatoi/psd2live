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

	val areas: List<RigArea> = listOf(
		RigArea("face", listOf(legacy("face")), emptyList()),
		RigArea("eye", listOf(legacy("eye"), EYE_FITTED), listOf(EYE_SEE_THROUGH, EYE_SMILE, EYE_WIDE, EYE_GAZE_LID, EYE_EMOTION, EYE_LID_FADE,
			EYE_HIGHLIGHT, EYE_JELLY, EYE_AFTERSHOCK, EYE_SQUASH)),
		RigArea("brow", listOf(legacy("brow")), listOf(BROW_SEE_THROUGH)),
		RigArea("mouth", listOf(legacy("mouth")), listOf(MOUTH_OUTLINE)),
		RigArea("hair", listOf(legacy("hair")), emptyList()),
		RigArea("body", listOf(legacy("body")), emptyList()),
		RigArea("outfit", listOf(legacy("outfit")), emptyList()),
		RigArea("expression", listOf(legacy("expression")), emptyList()),
		RigArea("motion", listOf(legacy("motion")), emptyList()),
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
