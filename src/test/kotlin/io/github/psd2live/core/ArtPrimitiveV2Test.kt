package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtPrimitiveV2Test {
	private val primitiveJson = """
		{"id": "LegL_part", "layer_id": "leg:0", "source_id": "src", "name": "Leg L",
		 "source_bounds": [10, 20, 30, 60], "neutral_bounds": [0.1, 0.2, 0.3, 0.6],
		 "classification": {"type": "PRESET", "tag": "EYEWHITE", "side": "LEFT", "parameter": "", "switch": 0},
		 "parent": "DeformBodyXY",
		 "positions": [0, 0, 1, 0, 0, 1, 1, 1], "triangles": [0, 1, 2, 1, 3, 2], "canvas_uvs": [10, 20, 30, 20, 10, 60, 30, 60],
		 "fixed_topology": true, "frozen_axes": ["ParamEyeLOpen"],
		 "authored": {"order": 500}}
	""".trimIndent()

	private fun primitive(edit: JsonObject.() -> JsonObject = { this }) = Json.parseToJsonElement(primitiveJson).jsonObject.edit()

	private fun record(vararg primitives: JsonObject, version: Int = ArtPrimitiveV2.VERSION_V2) = buildJsonObject {
		put("op", ArtPrimitiveV2.OP); put("v", version); put("origin", "split"); put("texture_source_id", "src")
		put("supersedes", buildJsonArray { add("Leg") }); put("supersedes_layers", buildJsonArray { add("leg") })
		put("replace", buildJsonObject { put("Leg", JsonArray(primitives.map { it.getValue("id") })) })
		put("primitives", JsonArray(primitives.toList()))
		putJsonObject("glues") { put("replaced", JsonArray(emptyList())); put("appended", JsonArray(emptyList())) }
	}

	private fun JsonObject.with(key: String, value: JsonElement?) = JsonObject(if (value == null) this - key else this + (key to value))

	@Test fun decodesAHandWrittenPrimitive() {
		val part = ArtPrimitiveV2.decodePrimitive(primitive(), 3)
		assertEquals(3, part.recordIndex)
		assertEquals(DrawableId("LegL_part"), part.drawableId)
		assertEquals("leg:0", part.layerId)
		assertEquals("src", part.sourceId)
		assertEquals("Leg L", part.name)
		assertEquals(DeformerId("DeformBodyXY"), part.parent)
		assertContentEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), part.positions)
		assertContentEquals(intArrayOf(0, 1, 2, 1, 3, 2), part.triangles)
		assertContentEquals(floatArrayOf(10f, 20f, 30f, 20f, 10f, 60f, 30f, 60f), part.canvasUvs)
		assertEquals(LayerClassificationOverride(LayerType.PRESET, SemanticTag.EYEWHITE, Side.LEFT, "", 0), part.classification)
		assertEquals(setOf(ParameterId("ParamEyeLOpen")), part.frozenAxes)
		assertTrue(part.fixedTopology)
		assertEquals(LayerBounds(10, 20, 20, 40), part.sourceBounds)
		assertEquals(Bounds(0.1f, 0.2f, 0.3f, 0.6f), part.neutralBounds)
		// Classification encodes back to what it read; a null parent decodes as the root.
		assertEquals(primitive().getValue("classification"), ArtPrimitiveV2.encodeClassification(part.classification))
		assertEquals(null, ArtPrimitiveV2.decodePrimitive(primitive { with("parent", JsonNull) }, 0).parent)
		// Equality is by content.
		assertEquals(part, ArtPrimitiveV2.decodePrimitive(primitive(), 3))
		assertEquals(part.hashCode(), ArtPrimitiveV2.decodePrimitive(primitive(), 3).hashCode())
		assertNotEquals(part, ArtPrimitiveV2.decodePrimitive(primitive { with("positions", Json.parseToJsonElement("[0,0,2,0,0,1,1,1]")) }, 3))
	}

	@Test fun decodesTheMeshForm() {
		val flat = primitive()
		val meshed = JsonObject(flat - "positions" - "triangles" - "canvas_uvs" + (ArtPrimitiveV2.MESH to buildJsonObject {
			put(ArtPrimitiveV2.CANVAS_POSITIONS, flat.getValue("positions")); put(ArtPrimitiveV2.TRIANGLES, flat.getValue("triangles"))
			put(ArtPrimitiveV2.CANVAS_UVS, flat.getValue("canvas_uvs"))
		}))
		val part = ArtPrimitiveV2.decodePrimitive(meshed, 3)
		assertTrue(part.canvasPositions)
		assertFalse(ArtPrimitiveV2.decodePrimitive(flat, 3).canvasPositions)
		assertContentEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), part.positions)
		assertContentEquals(intArrayOf(0, 1, 2, 1, 3, 2), part.triangles)
		assertNotEquals(ArtPrimitiveV2.decodePrimitive(flat, 3), part)
		assertFailsWith<IllegalArgumentException> { ArtPrimitiveV2.decodePrimitive(meshed.with(ArtPrimitiveV2.MESH, JsonPrimitive(1)), 0) }
		assertFailsWith<IllegalArgumentException> {
			ArtPrimitiveV2.decodePrimitive(meshed.with(ArtPrimitiveV2.MESH, JsonObject(meshed.getValue(ArtPrimitiveV2.MESH).jsonObject - ArtPrimitiveV2.CANVAS_POSITIONS)), 0)
		}
	}

	@Test fun rejectsInvalidPrimitives() {
		val broken = listOf<JsonObject.() -> JsonObject>(
			{ with("id", null) },
			{ with("layer_id", JsonPrimitive("")) },
			{ with("source_bounds", null) },
			{ with("source_bounds", Json.parseToJsonElement("[30, 20, 10, 60]")) },
			{ with("neutral_bounds", Json.parseToJsonElement("[0, 0, 1]")) },
			{ with("positions", Json.parseToJsonElement("[0, 0, 1, 0, 0]")) },
			{ with("canvas_uvs", Json.parseToJsonElement("[10, 20, 30, 20]")) },
			{ with("triangles", Json.parseToJsonElement("[0, 1, 9]")) },
			{ with("positions", Json.parseToJsonElement("[0, 0, \"x\", 0, 0, 1, 1, 1]")) },
			{ with("fixed_topology", null) },
			{ with("frozen_axes", JsonPrimitive("ParamEyeLOpen")) },
			{ with("authored", null) },
			{ with("classification", null) },
			{ with("classification", Json.parseToJsonElement("""{"type":"PRESET","tag":"NOPE","side":"LEFT","parameter":"","switch":0}""")) },
			{ with("classification", Json.parseToJsonElement("""{"type":"PRESET","tag":"EYEWHITE","side":"LEFT","parameter":""}""")) },
		)
		for ((index, edit) in broken.withIndex()) {
			assertFailsWith<IllegalArgumentException>("case $index") { ArtPrimitiveV2.decodePrimitive(primitive(edit), 0) }
		}
		val other = primitive { with("id", JsonPrimitive("Other")) }
		assertFailsWith<IllegalArgumentException> { ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = listOf(record(primitive(), primitive())))) }
		assertFailsWith<IllegalArgumentException> { ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = listOf(record(other).with("supersedes", null)))) }
		assertFailsWith<IllegalArgumentException> { ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = listOf(record()))) }
	}

	@Test fun recognisesOnlyVersionTwoRecords() {
		assertTrue(ArtPrimitiveV2.isV2(record(primitive())))
		assertFalse(ArtPrimitiveV2.isV2(record(primitive(), version = 1)))
		assertFalse(ArtPrimitiveV2.isV2(buildJsonObject { put("op", "canvas_mesh_create"); put("v", 2) }))
	}

	@Test fun resolvesNothingWithoutVersionTwoRecords() {
		assertSame(ResolvedLayers.Empty, ArtPrimitiveV2.resolve(RigEditOverlay.Empty))
		val journal = listOf(buildJsonObject { put("op", "parameter_set") }, record(primitive(), version = 1))
		assertSame(ResolvedLayers.Empty, ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = journal)))
		assertTrue(ResolvedLayers.Empty.isEmpty())
	}

	@Test fun resolvesPartsAndStubsWithAStableContentKey() {
		val journal = listOf(buildJsonObject { put("op", "parameter_set") }, record(primitive(), primitive { with("id", JsonPrimitive("LegR_part")) }))
		val resolved = ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = journal))
		assertEquals(listOf(DrawableId("LegL_part"), DrawableId("LegR_part")), resolved.parts.map { it.drawableId })
		assertEquals(listOf(1, 1), resolved.parts.map { it.recordIndex })
		assertEquals(setOf("leg"), resolved.stubLayers)
		assertEquals(setOf(DrawableId("Leg")), resolved.stubDrawables)
		val again = ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = journal))
		assertEquals(resolved, again)
		assertEquals(resolved.contentKey, again.contentKey)
		assertEquals(64, resolved.contentKey.length)
		assertNotEquals(ResolvedLayers.Empty.contentKey, resolved.contentKey)
		val moved = ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = listOf(journal[0], record(primitive(),
			primitive { with("id", JsonPrimitive("LegR_part")).with("canvas_uvs", Json.parseToJsonElement("[10, 20, 30, 20, 10, 60, 30, 61]")) }))))
		assertNotEquals(resolved.contentKey, moved.contentKey)
	}
}
