package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.recipe.ModuleCost
import io.github.psd2live.core.recipe.RecipeKnob
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModule
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/**
 * The rig's areas, bases and modules as data, and with a project open how its recipe uses them: what `settings.rig`
 * may choose. It needs no project; with one, each module also says whether it applies to the art, is on and costs.
 */
internal fun registerRigModuleOperations(registry: WorkspaceOperationRegistry, queries: WorkspaceQueries) {
	registry.register(WorkspaceOperationDefinition("module_catalog",
		"List the rig's areas (eye, brow, mouth, face, hair, body, outfit, expression, motion), each with its bases - mutually " +
			"exclusive ways to build the area, the first the legacy one - and its modules: capabilities that add up, each switched on " +
			"or off on its own (tier core/major/minor says how much it matters to the look; requires lists modules it builds on), with " +
			"their adjustable values (unit, range, default; percent of the measure the module names). With a project open: modern " +
			"(false for a project saved before modules, which generates as it did until upgraded with settings changes.rig {}), the " +
			"base each area builds with, and per module whether it applies to the art, is on, and its cost - the parameters it adds, " +
			"the keyforms and blend-shape forms, its physics groups; measured on the rig when on, estimated when off. Change with " +
			"settings changes.rig {bases: {area: {base, values}}, modules: {id: {on, values}}}, null entries back to the default, " +
			"rig null back to legacy.",
		schema(emptyMap()), WorkspaceOperationKind.QUERY, workspaceBound = false, resultSchema = RigModuleSchemas.catalog)) { _, _ ->
		WorkspaceOperationOutput(queries.rigModules())
	}
}

/** [RigModules] described for `module_catalog`, against [document] and its [model] when a project is open. */
internal object RigModuleCatalog {
	fun describe(document: WorkspaceDocument?, model: RigPreviewModel?): JsonObject = buildJsonObject {
		val config = document?.let { WorkspaceSettingsCodec.decode(it.settings) }
		val subject = RecipeSubject.of(model?.analysis)
		val physics = if (document != null && model != null) WorkspaceDocumentEdits.physicsCatalog(document, model).map { it.id }.toSet() else emptySet()
		config?.let { put("modern", it.recipe.modern) }
		put("areas", JsonArray(RigModules.areas.map { area ->
			buildJsonObject {
				put("id", area.id)
				config?.let { put("base", it.recipe.base(area.id).id) }
				put("bases", JsonArray(area.bases.map { base ->
					buildJsonObject { put("id", base.id); put("version", base.version); put("values", knobs(base.knobs)) }
				}))
				put("modules", JsonArray(area.modules.map { module ->
					buildJsonObject {
						put("id", module.id); put("tier", module.tier.name.lowercase()); put("version", module.version)
						put("requires", JsonArray(module.requires.map(::JsonPrimitive)))
						put("values", knobs(module.knobs))
						if (config != null) {
							val applies = module.applies(subject)
							val on = config.recipe.switchedOn(module) { RigModules.setting(config, it) }
							put("applies", applies); put("on", on)
							if (applies) put("cost", cost(if (on && model != null) ModuleCost.measure(module, subject, model.rig.puppet, physics) else module.estimate(subject)))
						}
					}
				}))
			}
		}))
	}

	/** [module]'s cost on [model] when on, else its estimate; null when it does not apply. */
	fun cost(module: RigModule, config: io.github.psd2live.core.PipelineConfig, model: RigPreviewModel?, physics: Set<String>): ModuleCost? {
		val subject = RecipeSubject.of(model?.analysis)
		if (!module.applies(subject)) return null
		val on = config.recipe.switchedOn(module) { RigModules.setting(config, it) }
		return if (on && model != null) ModuleCost.measure(module, subject, model.rig.puppet, physics) else module.estimate(subject)
	}

	private fun knobs(knobs: List<RecipeKnob>) = JsonArray(knobs.map { knob ->
		buildJsonObject {
			put("id", knob.id); put("unit", knob.unit.name.lowercase()); put("minimum", knob.range.start)
			put("maximum", knob.range.endInclusive); put("default", knob.default); put("advanced", knob.advanced)
		}
	})

	private fun cost(cost: ModuleCost) = buildJsonObject {
		put("parameters", JsonArray(cost.parameters.map(::JsonPrimitive))); put("keyforms", cost.keyforms)
		put("blend_shapes", cost.blendShapes); put("physics_groups", cost.physicsGroups); put("estimated", cost.estimated)
	}
}

private fun schema(fields: Map<String, JsonObject>, required: List<String> = fields.keys.toList()) = buildJsonObject {
	put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
	put("required", JsonArray(required.map(::JsonPrimitive)))
}

/** The schemas of the module catalogue and of a project's recipe, derived from the registry. */
internal object RigModuleSchemas {
	private fun type(name: String) = buildJsonObject { put("type", name) }
	private fun array(items: JsonObject) = buildJsonObject { put("type", "array"); put("items", items) }
	private fun choice(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
	private fun nullable(value: JsonObject) = buildJsonObject { put("oneOf", JsonArray(listOf(value, type("null")))) }

	private val knob = schema(mapOf("id" to type("string"), "unit" to type("string"), "minimum" to type("number"),
		"maximum" to type("number"), "default" to type("number"), "advanced" to type("boolean")))
	private val base = schema(mapOf("id" to type("string"), "version" to type("integer"), "values" to array(knob)))
	private val cost = schema(mapOf("parameters" to array(type("string")), "keyforms" to type("integer"), "blend_shapes" to type("integer"),
		"physics_groups" to type("integer"), "estimated" to type("boolean")))
	private val module = schema(mapOf("id" to type("string"), "tier" to choice(listOf("core", "major", "minor")), "version" to type("integer"),
		"requires" to array(type("string")), "values" to array(knob), "applies" to type("boolean"), "on" to type("boolean"), "cost" to cost),
		listOf("id", "tier", "version", "requires", "values"))
	private val area = schema(mapOf("id" to type("string"), "base" to type("string"), "bases" to array(base), "modules" to array(module)),
		listOf("id", "bases", "modules"))
	val catalog: JsonObject = schema(mapOf("modern" to type("boolean"), "areas" to array(area)), listOf("areas"))

	private fun values(knobs: List<RecipeKnob>, owner: String) = schema(knobs.associate { knob ->
		knob.id to buildJsonObject {
			put("type", "number"); put("minimum", knob.range.start); put("maximum", knob.range.endInclusive)
			put("description", "$owner: ${knob.unit.name.lowercase().replace('_', ' ')}, default ${knob.default}${if (knob.advanced) " (advanced)" else ""}")
		}
	}, emptyList())

	/** What `settings` accepts under `changes.rig`: the bases and modules to change, null entries for their defaults; null for legacy. */
	val change: JsonObject = nullable(schema(mapOf(
		"bases" to schema(RigModules.areas.associate { area ->
			area.id to nullable(schema(mapOf("base" to choice(area.bases.map { it.id }), "version" to type("integer"),
				"values" to values(area.bases.flatMap { it.knobs }.distinctBy { it.id }, area.id)), emptyList()))
		}, emptyList()),
		"modules" to schema(RigModules.modules.associate { module ->
			module.id to nullable(schema(buildMap {
				put("on", type("boolean")); put("version", type("integer"))
				if (module.setting == null) put("values", values(module.knobs, module.id))
			}, emptyList()))
		}, emptyList()),
	), emptyList()))

	/** A project's stored recipe, as inspect scope=settings reports it: present for a modern project. */
	val stored: JsonObject = schema(mapOf(
		"bases" to schema(RigModules.areas.associate { area ->
			area.id to schema(mapOf("base" to choice(area.bases.map { it.id }), "version" to type("integer"),
				"values" to buildJsonObject { put("type", "object"); put("additionalProperties", type("number")) }), listOf("base", "version"))
		}, emptyList()),
		"modules" to schema(RigModules.modules.filter { it.setting == null }.associate { module ->
			module.id to schema(mapOf("on" to type("boolean"), "version" to type("integer"),
				"values" to buildJsonObject { put("type", "object"); put("additionalProperties", type("number")) }), listOf("version"))
		}, emptyList()),
	), emptyList())
}
