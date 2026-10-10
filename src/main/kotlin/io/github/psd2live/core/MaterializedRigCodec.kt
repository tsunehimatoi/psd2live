package io.github.psd2live.core

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import io.github.psd2live.core.legacy.SupersededEntryNote

/**
 * An [AuthoredRig] as a revision stores it: the authored puppet as [RigIrObjects] (frame, deformers, meshes) and a
 * JSON header with the rest of the rig - the maps from meshes to source layers, pages and neutral bounds, the face
 * frame, the warnings, the entries the replay skipped, the meshes whose layer visibility applies - and the binding key
 * of the atlas its texture coordinates address ([PSD2LivePipeline.materializedPreview]).
 *
 * The base's split-part side channel and unbound rig are not stored: nothing after the journal reads them.
 */
internal object MaterializedRigCodec {
	const val FORMAT = "psd2live-authored-rig"
	const val VERSION = 1

	class Encoded(val header: JsonObject, val objects: RigIrObjects.Objects)

	class Decoded(val authored: AuthoredRig, val bindingKey: String)

	fun encode(authored: AuthoredRig, bindingKey: String): Encoded {
		val rig = authored.rig
		val header = buildJsonObject {
			put("format", FORMAT); put("version", VERSION); put("build", Compiler.version); put("binding_key", bindingKey)
			putJsonObject("pages") { rig.pageByDrawableId.toSortedMap().forEach { (id, page) -> put(id, page) } }
			putJsonObject("layers") { rig.layerIdByDrawableId.toSortedMap().forEach { (id, layer) -> put(id, layer) } }
			putJsonObject("bounds") {
				rig.sourceBoundsByDrawableId.toSortedMap().forEach { (id, b) -> put(id, floats(b.left, b.top, b.right, b.bottom)) }
			}
			put("face", floats(rig.faceCenterX, rig.faceCenterY, rig.faceRadiusX, rig.faceRadiusY, rig.initialHeadAngleZ))
			put("warnings", JsonArray(rig.warnings.map(::JsonPrimitive)))
			put("skipped", JsonArray(rig.supersededEntryNotes.map { note -> buildJsonObject {
				put("index", note.index); put("op", note.op); put("targets", JsonArray(note.targets.map(::JsonPrimitive))); put("detail", note.detail)
			} }))
			put("visibility", JsonArray(authored.visibilityTargets.map { (id, layer) -> JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(layer))) }))
		}
		return Encoded(header, RigIrObjects.split(PuppetIr.toIr(rig.puppet)))
	}

	/** The rig [header] and [objects] describe; [IllegalArgumentException] or [java.io.IOException] when they are not one. */
	fun decode(header: JsonObject, objects: RigIrObjects.Objects): Decoded {
		require(header["format"]?.jsonPrimitive?.contentOrNull == FORMAT) { "Not a stored authored rig" }
		require(header["version"]?.jsonPrimitive?.intOrNull == VERSION) { "Unsupported stored authored rig version" }
		val puppet = PuppetIr.toPuppet(RigIrObjects.join(objects))
		fun strings(name: String) = header.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.content }
		val face = numbers(header.getValue("face"), 5)
		val rig = BuiltRig(
			puppet = puppet,
			pageByDrawableId = header.getValue("pages").jsonObject.mapValues { it.value.jsonPrimitive.int },
			sourceBoundsByDrawableId = header.getValue("bounds").jsonObject.mapValues { (_, value) ->
				numbers(value, 4).let { Bounds(it[0], it[1], it[2], it[3]) }
			},
			layerIdByDrawableId = strings("layers"),
			faceCenterX = face[0], faceCenterY = face[1], faceRadiusX = face[2], faceRadiusY = face[3],
			warnings = header.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
			initialHeadAngleZ = face[4],
			supersededEntryNotes = header.getValue("skipped").jsonArray.map { element ->
				val note = element.jsonObject
				SupersededEntryNote(note.getValue("index").jsonPrimitive.int, note.getValue("op").jsonPrimitive.content,
					note.getValue("targets").jsonArray.map { it.jsonPrimitive.content }, note.getValue("detail").jsonPrimitive.content)
			},
		)
		val visibility = header.getValue("visibility").jsonArray.map { element ->
			val pair = element.jsonArray
			require(pair.size == 2) { "Invalid stored visibility target" }
			pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
		}
		return Decoded(AuthoredRig(rig, visibility), header.getValue("binding_key").jsonPrimitive.content)
	}

	/**
	 * [authored] as an index: `{header, frame, deformers:[...], meshes:[...]}`, the objects held in [RigObjects] and
	 * named by their hashes in rig order.
	 */
	fun index(authored: AuthoredRig, bindingKey: String): JsonObject = index(encode(authored, bindingKey))

	fun index(encoded: Encoded): JsonObject = buildJsonObject {
		put("header", encoded.header)
		put("frame", RigObjects.put(encoded.objects.frame))
		put("deformers", JsonArray(encoded.objects.deformers.map { JsonPrimitive(RigObjects.put(it)) }))
		put("meshes", JsonArray(encoded.objects.meshes.map { JsonPrimitive(RigObjects.put(it)) }))
	}

	/** Every object hash [index] names. */
	fun hashes(index: JsonObject): List<String> = listOf(index.getValue("frame").jsonPrimitive.content) +
		index.getValue("deformers").jsonArray.map { it.jsonPrimitive.content } + index.getValue("meshes").jsonArray.map { it.jsonPrimitive.content }

	/** The authored rig [index] describes, its objects read from [RigObjects]. */
	fun fromIndex(index: JsonObject): Decoded = decode(index.getValue("header").jsonObject, RigIrObjects.Objects(
		RigObjects.get(index.getValue("frame").jsonPrimitive.content),
		index.getValue("deformers").jsonArray.map { RigObjects.get(it.jsonPrimitive.content) },
		index.getValue("meshes").jsonArray.map { RigObjects.get(it.jsonPrimitive.content) }))

	private fun floats(vararg values: Float) = JsonArray(values.map { JsonPrimitive(java.lang.Float.floatToRawIntBits(it)) })

	/** Floats stored as their raw bits, so a value comes back bit for bit. */
	private fun numbers(value: JsonElement, size: Int): FloatArray {
		val array = value.jsonArray
		require(array.size == size) { "Invalid stored number list" }
		return FloatArray(size) { java.lang.Float.intBitsToFloat(array[it].jsonPrimitive.int) }
	}
}
