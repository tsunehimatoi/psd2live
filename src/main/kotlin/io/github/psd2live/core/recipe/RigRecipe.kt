package io.github.psd2live.core.recipe

import io.github.psd2live.core.RigTuning

/**
 * One adjustable value of a [RigBase] or [RigModule]: its [id] within its owner, the [unit] the panel shows it in,
 * its [range] and [default], and whether the panel folds it under Advanced. A module's knobs are few and broad - each
 * drives a group of the values its algorithm reads - so the common ones stay on top and the rest fold away. A percent
 * is of the measure the module names (an eye's height, the mouth's width...), so the same value fits any art.
 */
class RecipeKnob(
	val id: String,
	val unit: RigTuning.Unit,
	val range: ClosedFloatingPointRange<Float>,
	val default: Float,
	val advanced: Boolean = false,
) {
	init { require(default in range) { "$id: default $default outside $range" } }
}

/**
 * A parameter a module adds to the rig: its [id] (a Cubism standard name where there is one), the message key of its
 * name, its range and default, and the parameter group ([group], a `ParamGroup…` id) it is listed in.
 */
class ModuleParameter(val id: String, val nameKey: String, val min: Float, val max: Float, val default: Float, val group: String)

/**
 * How much a module matters to the look: [CORE] modules are what a part needs to look finished, [MAJOR] ones add a
 * visible quality, [MINOR] ones a finish. The presets and the parameter budget turn the least important off first.
 */
enum class ModuleTier { CORE, MAJOR, MINOR }

/**
 * One of the mutually exclusive ways an area builds its base - the eyelids closing as the legacy U or by a fitted
 * template. Its [id] is `<area>/<name>` and never changes once a project may have stored it; [version] is the
 * algorithm's revision, and a change that alters an existing project's rig is a new version.
 */
class RigBase(val id: String, val version: Int = 1, val knobs: List<RecipeKnob> = emptyList()) {
	val area: String get() = id.substringBefore('/')
	private val knobById = knobs.associateBy { it.id }

	fun knob(id: String): RecipeKnob? = knobById[id]

	init {
		require(id.matches(Regex("[a-z]+/[a-z][a-z-]*"))) { "Bad base id: $id" }
		require(knobById.size == knobs.size) { "$id: duplicate knob" }
	}
}

/**
 * One capability of an area that adds to the rest - the eye smile, gaze moving the lids, the lids' aftershock - each
 * switched on or off on its own. Its [id] is `<area>.<name>`.
 *
 * [requires] lists modules it builds on: switching one of those off switches this one off with it. [base] is the base
 * of its area it works on (the eye modules on the fitted lids): under another base it is not built. [setting] names
 * the project setting that already switches a capability older than the registry (eye jelly, eyes through the bangs):
 * such a module's state is that setting's, in legacy projects too. [applies] says whether the art has what the module
 * works on, [defines] the parameters it adds, and [estimate] what it costs when it is off and nothing built shows it
 * ([ModuleCost.measure] reads the built rig when it is on).
 */
class RigModule(
	val id: String,
	val tier: ModuleTier,
	val version: Int = 1,
	val knobs: List<RecipeKnob> = emptyList(),
	val requires: List<String> = emptyList(),
	val base: String? = null,
	val setting: String? = null,
	val physicsGroups: List<String> = emptyList(),
	val applies: (RecipeSubject) -> Boolean = { true },
	val defines: List<ModuleParameter> = emptyList(),
	val estimate: (RecipeSubject) -> ModuleCost = { ModuleCost(defines.map { it.id }, physicsGroups = physicsGroups.size) },
) {
	/** The ids of the parameters it adds for the art [subject]. */
	val parameters: (RecipeSubject) -> List<String> = { defines.map { it.id } }

	val area: String get() = id.substringBefore('.')
	private val knobById = knobs.associateBy { it.id }

	fun knob(id: String): RecipeKnob? = knobById[id]

	init {
		require(id.matches(Regex("[a-z]+\\.[a-z][a-z-]*"))) { "Bad module id: $id" }
		require(knobById.size == knobs.size) { "$id: duplicate knob" }
	}
}

/**
 * One card of the rig: the [bases] it can build with (the first is the legacy one, what a project from before the
 * registry generates) and the [modules] it can add.
 */
class RigArea(val id: String, val bases: List<RigBase>, val modules: List<RigModule>) {
	val legacy: RigBase get() = bases.first()

	/** The base a project that chose none generates with: the newest one, the last registered. */
	val default: RigBase get() = bases.last()

	init {
		require(bases.isNotEmpty() && bases.all { it.area == id }) { "$id: bases must belong to the area" }
		require(modules.all { it.area == id }) { "$id: modules must belong to the area" }
	}
}

/** A base an area builds with, the [version] it was chosen at and the [values] of its knobs that differ from the defaults. */
data class BaseChoice(val base: String, val version: Int = 1, val values: Map<String, Float> = emptyMap())

/**
 * A module the project changed: [on] false switches it off (null keeps the default, on where it applies), [values] are
 * its knobs that differ from their defaults and [version] the module's when they were set.
 */
data class ModuleChoice(val on: Boolean? = null, val version: Int = 1, val values: Map<String, Float> = emptyMap())

/**
 * How a project builds its rig: each area's base and the modules it switched off or adjusted.
 *
 * A [modern] recipe is a project's from the registry on: an area without a choice builds with its [RigArea.default]
 * base and every module that applies is on. A legacy recipe ([Legacy]) is that of a project saved before: every area
 * builds with its legacy base and no module is on - except those standing for an older [RigModule.setting], which
 * the setting switches - so the project generates exactly what it did. A legacy project becomes modern only when
 * the user upgrades it.
 */
data class RigRecipe(
	val modern: Boolean,
	val bases: Map<String, BaseChoice> = emptyMap(),
	val modules: Map<String, ModuleChoice> = emptyMap(),
) {
	/** The base [area] builds with. */
	fun base(area: String): RigBase {
		val known = RigModules.area(area) ?: error("Unknown rig area: $area")
		if (!modern) return known.legacy
		val chosen = bases[area]?.base ?: return known.default
		return known.bases.firstOrNull { it.id == chosen } ?: known.default
	}

	/** [knob]'s value in [area]'s base: the chosen value clamped to its range, or the default. */
	fun baseValue(area: String, knob: String): Float {
		val base = base(area)
		val descriptor = requireNotNull(base.knob(knob)) { "${base.id} has no value $knob" }
		val chosen = bases[area]?.takeIf { it.base == base.id }?.values?.get(knob)
		return chosen?.takeIf { it.isFinite() }?.coerceIn(descriptor.range) ?: descriptor.default
	}

	/**
	 * Whether the project switched [module] on, its prerequisites included; [setting] reads a [RigModule.setting]. A
	 * module that does not apply to the art is not built whatever this says.
	 */
	fun switchedOn(module: RigModule, setting: (String) -> Boolean): Boolean {
		val own = when {
			module.setting != null -> setting(module.setting)
			!modern -> false
			else -> modules[module.id]?.on ?: true
		}
		return own && module.requires.all { id -> RigModules.module(id)?.let { switchedOn(it, setting) } == true }
	}

	/** [knob]'s value in [module]: the chosen value clamped to its range, or the default. */
	fun value(module: String, knob: String): Float {
		val known = RigModules.module(module) ?: error("Unknown rig module: $module")
		val descriptor = requireNotNull(known.knob(knob)) { "$module has no value $knob" }
		val chosen = modules[module]?.values?.get(knob)
		return chosen?.takeIf { it.isFinite() }?.coerceIn(descriptor.range) ?: descriptor.default
	}

	/**
	 * This recipe without choices that change nothing or that the registry no longer knows: an area on its default
	 * base with its values at their defaults, a module on with its values at their defaults. A legacy recipe has none.
	 */
	fun normalized(): RigRecipe {
		if (!modern) return Legacy
		val baseChoices = bases.mapNotNull { (areaId, choice) ->
			val area = RigModules.area(areaId) ?: return@mapNotNull null
			val base = area.bases.firstOrNull { it.id == choice.base } ?: return@mapNotNull null
			val values = choice.values.filter { (id, value) -> base.knob(id)?.let { value.isFinite() && value != it.default } == true }
			if (base === area.default && values.isEmpty()) null else areaId to BaseChoice(base.id, base.version, values.toSortedMap())
		}.toMap().toSortedMap()
		val moduleChoices = modules.mapNotNull { (id, choice) ->
			val module = RigModules.module(id)?.takeIf { it.setting == null } ?: return@mapNotNull null
			val values = choice.values.filter { (knob, value) -> module.knob(knob)?.let { value.isFinite() && value != it.default } == true }
			val on = choice.on?.takeIf { !it }
			if (on == null && values.isEmpty()) null else id to ModuleChoice(on, module.version, values.toSortedMap())
		}.toMap().toSortedMap()
		return RigRecipe(true, baseChoices, moduleChoices)
	}

	companion object {
		/** The recipe of a project saved before the registry: legacy bases, no module but the settings' own. */
		val Legacy = RigRecipe(false)

		/** A new project's recipe: every area on its default base, every module that applies on. */
		val Modern = RigRecipe(true)
	}
}
