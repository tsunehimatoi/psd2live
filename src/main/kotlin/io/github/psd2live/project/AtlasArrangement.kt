package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.util.BitSet

/** JSON form of [AtlasArrangement] under the document setting [KEY]; tiles sorted by layer ID. */
object AtlasArrangementCodec {
	const val KEY = "atlasArrangement"

	fun encode(value: AtlasArrangement): JsonObject = buildJsonObject {
		put("fitStep", value.fitStep)
		putJsonObject("tiles") {
			for ((id, tile) in value.tiles.toSortedMap()) putJsonObject(id) {
				put("page", tile.page); put("x", tile.x); put("y", tile.y)
				if (tile.rotation != 0f) put("rotation", tile.rotation)
				tile.footprint?.let { footprint -> putJsonObject("footprint") {
					put("cell", footprint.cell); put("columns", footprint.columns); put("rows", footprint.rows)
					put("bits", java.util.Base64.getEncoder().encodeToString(footprint.bits.toByteArray()))
				} }
			}
		}
	}

	fun decode(settings: JsonObject): AtlasArrangement? {
		val value = settings[KEY] as? JsonObject ?: return null
		fun JsonObject.int(name: String) = this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Invalid atlas arrangement $name")
		val tiles = (value["tiles"] as? JsonObject ?: throw IllegalArgumentException("Invalid atlas arrangement tiles")).mapValues { (_, entry) ->
			val tile = entry.jsonObject
			val footprint = (tile["footprint"] as? JsonObject)?.let {
				TextureFootprint(it.int("cell"), it.int("columns"), it.int("rows"),
					BitSet.valueOf(java.util.Base64.getDecoder().decode(it["bits"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Invalid footprint bits"))))
			}
			ArrangedTile(tile.int("page"), tile.int("x"), tile.int("y"), footprint,
				normalizedRotation(tile["rotation"]?.let { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Invalid atlas arrangement rotation") } ?: 0f))
		}
		return AtlasArrangement(value.int("fitStep"), tiles)
	}

	/** [settings] with [value] stored, or without any arrangement when null. */
	fun with(settings: JsonObject, value: AtlasArrangement?): JsonObject =
		JsonObject(if (value == null) settings - KEY else settings + (KEY to encode(value)))
}
