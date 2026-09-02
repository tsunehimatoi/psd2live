package io.github.autolive2d.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Cubism Framework's compact JSON reader requires a line break after a final numeric value before
 * a closing bracket/brace. Re-encoding through the pretty printer keeps generated sidecars inside
 * that stricter subset while preserving their JSON data exactly.
 */
internal object CubismJson {
	private val writer = Json { prettyPrint = true }

	fun normalize(source: String): String = writer.encodeToString(
		JsonElement.serializer(),
		Json.parseToJsonElement(source),
	)
}
