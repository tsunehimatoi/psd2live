package io.github.psd2live.project

import io.github.psd2live.core.Side
import io.github.psd2live.core.fit.EyePoint
import io.github.psd2live.core.fit.EyeRegion
import io.github.psd2live.core.fit.EyePrimitive
import io.github.psd2live.core.fit.EyeContact
import io.github.psd2live.core.recipe.EyePartOverride
import kotlinx.serialization.json.*

internal object EyePartOverrideCodec {
	fun encode(value: EyePartOverride): JsonObject = buildJsonObject {
		put("sourceStamp", value.sourceStamp)
		value.side?.let { put("side", it.name.lowercase()) }
		value.region?.let { put("region", it.name.lowercase()) }
		value.primitive?.let { put("primitive", it.name.lowercase()) }
		value.path?.let { put("path", JsonArray(it.map(::point))) }
		value.contacts?.let { put("contacts", JsonArray(it.map { c -> buildJsonObject {
			put("region", c.region.name.lowercase()); put("from", c.from); put("to", c.to); put("centre", point(c.centre))
		} })) }
	}
	private fun point(p: EyePoint) = buildJsonArray { add(p.x); add(p.y) }
	private fun point(value: JsonElement): EyePoint {
		val a = value as? JsonArray ?: error("Eye point must be [x, y]")
		require(a.size == 2)
		return EyePoint(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float)
	}
	fun decode(value: JsonObject): EyePartOverride {
		require(value.keys.all { it in setOf("sourceStamp", "side", "region", "primitive", "contacts", "path") })
		fun text(key: String) = value[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
		return EyePartOverride(requireNotNull(text("sourceStamp")),
			text("side")?.let { Side.valueOf(it.uppercase()) },
			text("region")?.let { EyeRegion.valueOf(it.uppercase()) },
			text("primitive")?.let { EyePrimitive.valueOf(it.uppercase()) },
			value["contacts"]?.takeUnless { it == JsonNull }?.jsonArray?.map { entry ->
				val c = entry.jsonObject
				require(c.keys == setOf("region", "from", "to", "centre"))
				EyeContact(EyeRegion.valueOf(c.getValue("region").jsonPrimitive.content.uppercase()),
					c.getValue("from").jsonPrimitive.float, c.getValue("to").jsonPrimitive.float, point(c.getValue("centre")))
			},
			value["path"]?.takeUnless { it == JsonNull }?.jsonArray?.map(::point))
	}

	/** Null at either hierarchy level removes that correction. A component object patches its fields atomically. */
	fun merge(base: Map<String, Map<String, EyePartOverride>>, value: JsonElement): Map<String, Map<String, EyePartOverride>> {
		if (value == JsonNull) return emptyMap()
		val result = base.toMutableMap()
		value.jsonObject.forEach { (layer, entries) ->
			require(layer.isNotBlank())
			if (entries == JsonNull) result.remove(layer) else {
				val components = result[layer].orEmpty().toMutableMap()
				entries.jsonObject.forEach { (id, update) ->
					require(id.isNotBlank())
					if (update == JsonNull) components.remove(id) else {
						val before = components[id]?.let(::encode).orEmpty()
						components[id] = decode(JsonObject(before + update.jsonObject))
					}
				}
				if (components.isEmpty()) result.remove(layer) else result[layer] = components
			}
		}
		return result
	}
}
