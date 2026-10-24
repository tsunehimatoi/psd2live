package io.github.psd2live.application

import io.github.psd2live.core.modifier.ModifierAuthoring
import io.github.psd2live.core.modifier.ModifierKinds
import io.github.psd2live.core.modifier.ModifierSampler
import io.github.psd2live.core.modifier.ModifierSampling
import io.github.psd2live.core.modifier.ModifierValue
import io.github.psd2live.core.modifier.RigModifier
import io.github.psd2live.project.MutationAuthor
import kotlinx.serialization.json.*

/** The modifier commands' schemas, shared by authoring discovery and inspection. */
internal object WorkspaceModifierSchemas {
	private val s = WorkspaceResultSchema

	private fun describe(value: JsonObject, text: String) = JsonObject(value + ("description" to JsonPrimitive(text)))

	private val kinds = ModifierKinds.all.map { it.id }.toTypedArray()
	private val settingKeys = ModifierKinds.all.flatMap { k -> k.settings.map { it.key } }.distinct().toTypedArray()

	private val number = buildJsonObject { put("type", "number") }

	/** The most components a setting has (a lattice's control point offsets). */
	private val MAX_COMPONENTS = ModifierKinds.all.maxOf { k -> k.settings.maxOf { it.size } }

	/** A constant (a number, or an array of the setting's components) or values keyed on parameters. */
	val value = buildJsonObject {
		put("oneOf", JsonArray(listOf(
			number,
			s.array(number, 1, MAX_COMPONENTS),
			s.obj(mapOf(
				"axes" to s.array(s.obj(mapOf("parameter" to s.handle(), "keys" to s.array(number, 1, ModifierSampling.MAX_KEYS))), 1, 4),
				"values" to s.array(s.array(number, 1, MAX_COMPONENTS), 1, 4096),
			)),
		)))
		put("description", "A constant, or {axes:[{parameter, keys}], values:[...]}: one value per key combination, the first axis varying fastest; multilinear between keys and held past the ends.")
	}

	private val settingsDescription = ModifierKinds.all.joinToString(" ") { k ->
		"${k.id}: " + k.settings.joinToString("; ") { "${it.key} (${it.size}) ${it.description}" }
	}

	private val optionsDescription = ModifierKinds.all.joinToString(" ") { k ->
		"${k.id}: " + k.options.joinToString("; ") { "${it.key}${if (it.required) " (required)" else ""} ${it.description}" }
	}

	private val key = s.obj(mapOf(
		"setting" to describe(s.choices(*settingKeys), "The setting to key."),
		"at" to describe(s.dictionary(number), "Parameter values of the key; the setting gains an axis for each parameter it was not keyed on."),
		"value" to describe(JsonObject(mapOf("oneOf" to JsonArray(listOf(number, s.array(number, 1, MAX_COMPONENTS))))), "The value at the key."),
		"delete" to describe(s.boolean(), "Remove the key at `at` instead; with all=true remove every key, keeping the value at `at`."),
		"all" to s.boolean(),
	), setOf("setting", "at"))

	private val parameter = s.obj(mapOf(
		"id" to s.handle(), "name" to s.string(), "min" to s.number(), "max" to s.number(), "default" to s.number(),
	), setOf("id"))

	val put = WorkspaceCommandSchema(
		properties = buildJsonObject {
			put("state", s.handle())
			put("id", describe(s.handle(), "The modifier to change; a new one when absent or unknown (an unknown ID becomes the new one's)."))
			put("kind", describe(s.choices(*kinds), "Required for a new modifier."))
			put("name", s.string())
			put("targets", describe(s.array(s.handle(), 1, 256), "Mesh IDs (or mesh:<ID>), and warp:<ID> for a warp deformer, whose control points move and carry everything under it; the settings are shared, each target is keyed on its own."))
			put("part", describe(s.handle(), "Instead of targets: every mesh under this part (or part:<ID>), sub-parts included; a mesh moved in or out joins or leaves."))
			put("excluded", describe(s.array(s.handle(), 0, 256), "With part: meshes left out."))
			put("enabled", s.boolean())
			put("settings", describe(s.dictionary(value), "Whole values by setting key; null resets one to the kind's default. $settingsDescription"))
			put("output", describe(s.choices("auto", "merge", "stage"),
				"How the stack is written on a mesh: merge into its keyforms, stage (a warp of its own between the mesh and its parent, keyed on the modifiers' parameters only, so the mesh's keyforms are not multiplied), or auto (stage when merging would multiply them past four times and 256)."))
			put("options", describe(s.dictionary(s.nullable(s.string())), "The kind's text choices by key (a parameter ID, a mesh ID or warp:<ID>, a side); null clears one. $optionsDescription"))
			put("keys", describe(s.array(key, 0, 256), "Single keys to set or remove, after settings."))
			put("parameters", describe(s.array(parameter, 0, 16), "Parameters the modifier makes and owns, to key its settings on."))
			put("weights", describe(s.nullable(s.obj(mapOf("group" to s.handle(), "invert" to s.boolean()), setOf("group"))),
				"A vertex group of every target limiting the modifier per vertex; null removes it."))
			put("sampling", describe(s.obj(mapOf(
				"tolerance" to s.number(0.05, 64), "max_keys" to s.integer(2, ModifierSampling.MAX_KEYS)), emptySet()),
				"How finely the effect is sampled into keyforms: canvas pixels between keys, and keys per parameter."))
		},
		required = listOf("state"),
	)

	val delete = WorkspaceCommandSchema(buildJsonObject { put("state", s.handle()); put("id", s.handle()) }, listOf("state", "id"))

	val move = WorkspaceCommandSchema(buildJsonObject {
		put("state", s.handle()); put("id", s.handle())
		put("before", describe(s.handle(), "Place it before this modifier."))
		put("to", describe(s.choices("first", "last"), "Place it first or last."))
	}, listOf("state", "id"))

	val apply = WorkspaceCommandSchema(buildJsonObject {
		put("state", s.handle()); put("id", s.handle())
		put("mode", describe(s.choices("keyforms", "rest"),
			"keyforms (default): key the targets as ordinary keyforms; the modifier must be alone on each. rest: move the rest shape by what it does at pose."))
		put("pose", describe(s.dictionary(number), "mode=rest: parameter values to take the shape at; the rest stay at their defaults."))
		put("mesh", describe(s.handle(), "Apply to this mesh alone; the modifier stays on its other meshes."))
	}, listOf("state", "id"))

	val split = WorkspaceCommandSchema(buildJsonObject {
		put("state", s.handle()); put("id", s.handle())
		put("mesh", describe(s.handle(), "The mesh that gets a copy of its own and leaves the modifier."))
		put("new_id", describe(s.handle(), "The copy's ID; a fresh one when absent."))
	}, listOf("state", "id", "mesh"))

	val copy = WorkspaceCommandSchema(buildJsonObject {
		put("state", s.handle()); put("id", s.handle())
		put("targets", describe(s.array(s.handle(), 1, 256), "The meshes the copy acts on."))
		put("new_id", describe(s.handle(), "The copy's ID; a fresh one when absent."))
	}, listOf("state", "id", "targets"))

	val simulationApply = WorkspaceCommandSchema(buildJsonObject { put("state", s.handle()); put("id", describe(s.handle(), "The baked simulation to apply.")) },
		listOf("state", "id"))

	val inspected = s.obj(mapOf(
		"id" to s.handle(), "kind" to s.handle(), "name" to s.string(), "targets" to s.array(s.handle()),
		"part" to s.nullable(s.handle()), "excluded" to s.array(s.handle()), "enabled" to s.boolean(),
		"settings" to s.dictionary(value), "created" to s.array(parameter), "weights" to s.nullable(s.obj(mapOf("group" to s.handle(), "invert" to s.boolean()))),
		"sampling" to s.obj(mapOf("tolerance" to s.number(0), "max_keys" to s.integer(2))),
		"drivers" to s.array(s.handle()), "options" to s.dictionary(s.string()), "output" to s.choices("auto", "merge", "stage"),
		"issues" to s.array(s.obj(mapOf("kind" to s.choices(*ModifierSampler.IssueKind.entries.map { it.code }.toTypedArray()),
			"target" to s.handle(), "detail" to s.string(), "error" to s.number(0)), setOf("kind", "target"))),
	))

	/** [modifier] as `workspace_inspect scope=modifiers` lists it, with what keeps it from its meshes. */
	fun inspect(modifier: RigModifier, issues: List<ModifierSampler.Issue>): JsonObject = buildJsonObject {
		put("id", modifier.id); put("kind", modifier.kind); put("name", modifier.name)
		putJsonArray("targets") { modifier.target.meshes.forEach { add(it) } }
		put("part", modifier.target.part?.let(::JsonPrimitive) ?: JsonNull)
		putJsonArray("excluded") { modifier.target.excluded.sorted().forEach { add(it) } }
		put("enabled", modifier.enabled)
		putJsonObject("settings") { modifier.definition.settings.forEach { put(it.key, ModifierValue.toJson(modifier.value(it.key))) } }
		putJsonArray("created") { modifier.created.forEach { add(JsonObject(RigModifier.parameterJson(it) - "kind")) } }
		put("weights", modifier.weights?.let { w -> buildJsonObject { put("group", w.group); put("invert", w.invert) } } ?: JsonNull)
		putJsonObject("sampling") { put("tolerance", modifier.sampling.tolerance); put("max_keys", modifier.sampling.maxKeys) }
		putJsonArray("drivers") { modifier.drivers.forEach { add(it) } }
		putJsonObject("options") { modifier.options.toSortedMap().forEach { (k, v) -> put(k, v) } }
		put("output", modifier.output.id)
		putJsonArray("issues") {
			issues.filter { it.modifier == modifier.id }.forEach { i ->
				addJsonObject { put("kind", i.kind.code); put("target", i.target); put("detail", i.detail); i.error?.let { put("error", it) } }
			}
		}
	}
}

internal fun registerModifierCommands(catalog: WorkspaceCommands, workspace: WorkspaceBackend) {
	val descriptions = mapOf(
		ModifierAuthoring.PUT to "Create or change a non-destructive modifier on one or more meshes. Its settings are constants or keyed on parameters " +
			"(keys sets single keys, as the field's key button does); it is sampled into ordinary mesh keyforms on those parameters after the other " +
			"generators, and modifiers on one mesh chain in stack order. Read them with workspace_inspect scope=modifiers.",
		ModifierAuthoring.DELETE to "Delete a modifier; parameters it made that other content uses stay as ordinary parameters.",
		ModifierAuthoring.MOVE to "Move a stack entry - a modifier, or a swing, simulation or interior effect by its node ID (swing:<id>, simulation:<id>, interiorEffect:<id>) - in the order the stack runs: each entry acts on what the ones before it made. workspace_inspect scope=modifiers lists the order as stack. A simulation whose earlier entries change reports its bake stale.",
		ModifierAuthoring.APPLY to "Apply a modifier: write what it does into the journal as ordinary keyforms (mode=keyforms) or into the rest shape at a pose (mode=rest), and remove it; with mesh, for that mesh alone. The rig looks as before.",
		ModifierAuthoring.SPLIT to "Give one of a modifier's meshes a copy of its own, which then is adjusted apart; it leaves the original.",
		ModifierAuthoring.COPY to "Put a copy of a modifier on other meshes, right after it in the stack; the copy is independent and keyed on the same parameters.",
		io.github.psd2live.core.sim.SimApply.OP to "Apply a baked simulation: its keyforms become ordinary journal entries with the edits of its cells folded in, its pendulums ordinary physics groups, and the simulation is removed. The rig looks and moves as before. Refused for modes written as blend shapes.",
	)
	val schemas = mapOf(
		ModifierAuthoring.PUT to WorkspaceModifierSchemas.put, ModifierAuthoring.DELETE to WorkspaceModifierSchemas.delete,
		ModifierAuthoring.MOVE to WorkspaceModifierSchemas.move, ModifierAuthoring.APPLY to WorkspaceModifierSchemas.apply,
		ModifierAuthoring.SPLIT to WorkspaceModifierSchemas.split, ModifierAuthoring.COPY to WorkspaceModifierSchemas.copy,
		io.github.psd2live.core.sim.SimApply.OP to WorkspaceModifierSchemas.simulationApply,
	)
	for ((id, schema) in schemas) catalog.register(id, descriptions.getValue(id), schema, hints = MUTATING) { request ->
		WorkspaceOperationOutput(workspace.applyDocumentEdits(request.arguments.getValue("state").jsonPrimitive.content, "Modifier",
			listOf(WorkspaceDocumentOperation(id, request.arguments)), MutationAuthor.AGENT).toJson())
	}
}
