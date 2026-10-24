package io.github.psd2live.core.modifier

import kotlinx.serialization.json.*

/**
 * A saved stack: the modifiers of one mesh, in order, with their settings and keys but without their IDs or meshes,
 * to put on other meshes or into other projects. The parameters the modifiers made travel with them; the ones they
 * are keyed on must exist where the preset is used, or the modifier that needs one is refused.
 */
class ModifierPreset(val name: String, val modifiers: List<JsonObject>) {
	init {
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "A preset has a name" }
		require(modifiers.isNotEmpty()) { "A preset holds at least one modifier" }
	}

	fun toJson() = buildJsonObject {
		put("schema", SCHEMA); put("name", name)
		put("modifiers", JsonArray(modifiers))
	}

	/**
	 * The `modifier_put` requests that put this stack on [targets], one per modifier, in order and after those
	 * already there. A parameter a modifier made is made again only where [parameters] lacks it.
	 */
	fun requests(targets: List<String>, parameters: Set<String>): List<JsonObject> = modifiers.map { m ->
		buildJsonObject {
			for ((key, value) in m) if (key != "created") put(key, value)
			putJsonArray("targets") { targets.forEach { add(it) } }
			val created = m["created"]?.jsonArray.orEmpty().filter { it.jsonObject["id"]?.jsonPrimitive?.content !in parameters }
			if (created.isNotEmpty()) put("parameters", JsonArray(created))
		}
	}

	companion object {
		const val SCHEMA = 1

		/** [modifiers] (one mesh's stack) as a preset called [name]. */
		fun of(name: String, modifiers: List<RigModifier>) = ModifierPreset(name, modifiers.map { m ->
			buildJsonObject {
				put("kind", m.kind); put("name", m.name); put("enabled", m.enabled)
				putJsonObject("settings") { m.settings.toSortedMap().forEach { (k, v) -> put(k, ModifierValue.toJson(v)) } }
				if (m.created.isNotEmpty()) putJsonArray("created") { m.created.forEach { add(JsonObject(RigModifier.parameterJson(it) - "kind")) } }
				m.weights?.let { w -> putJsonObject("weights") { put("group", w.group); put("invert", w.invert) } }
				if (m.sampling != ModifierSampling()) putJsonObject("sampling") { put("tolerance", m.sampling.tolerance); put("max_keys", m.sampling.maxKeys) }
				// A target option names a mesh of this project: a preset keeps only the others.
				val kept = m.options.filterKeys { k -> m.definition.options.firstOrNull { it.key == k }?.type != ModifierOption.Type.TARGET }
				if (kept.isNotEmpty()) putJsonObject("options") { kept.toSortedMap().forEach { (k, v) -> put(k, v) } }
			}
		})

		fun fromJson(o: JsonObject): ModifierPreset {
			require(o.getValue("schema").jsonPrimitive.int == SCHEMA) { "Unsupported preset schema" }
			return ModifierPreset(o.getValue("name").jsonPrimitive.content, o.getValue("modifiers").jsonArray.map { it.jsonObject })
		}
	}
}
