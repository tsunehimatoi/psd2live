package io.github.psd2live.project

import kotlinx.serialization.json.*

/** JSON form of [TextureOverride] in the document's `textureOverrides`; only non-default fields are written. */
internal object TextureOverrideCodec {
	fun encode(value: TextureOverride): JsonObject = buildJsonObject {
		value.density?.let { put("density", it) }
		if (value.lock) put("lock", true)
		value.pin?.let { pin -> putJsonObject("pin") { put("page", pin.page); put("x", pin.x); put("y", pin.y); if (pin.rotation != 0f) put("rotation", pin.rotation) } }
	}

	fun decode(value: JsonObject): TextureOverride = TextureOverride(
		density = value["density"]?.let { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Invalid texture density") },
		lock = value["lock"]?.let { it.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException("Invalid texture lock") } ?: false,
		pin = value["pin"]?.jsonObject?.let { pin ->
			fun int(name: String) = pin[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Invalid texture pin $name")
			TexturePin(int("page"), int("x"), int("y"), normalizedRotation(pin["rotation"]?.jsonPrimitive?.floatOrNull ?: 0f))
		},
	)

	/** The non-default overrides sorted by layer ID, or null when there is none to store. */
	fun encodeAll(values: Map<String, TextureOverride>): JsonObject? = values.filterValues { !it.isDefault }
		.takeIf { it.isNotEmpty() }?.toSortedMap()?.let { sorted -> JsonObject(sorted.mapValues { encode(it.value) }) }

	fun decodeAll(value: JsonObject?): Map<String, TextureOverride> =
		value?.mapValues { decode(it.value.jsonObject) }?.filterValues { !it.isDefault }.orEmpty()

	/** Canonical text for revision identity. */
	fun canonical(value: TextureOverride): String = buildString {
		append(value.density ?: "-").append(',').append(value.lock).append(',')
		value.pin?.let { append(it.page).append('/').append(it.x).append('/').append(it.y); if (it.rotation != 0f) append('/').append(it.rotation) } ?: append('-')
	}
}
