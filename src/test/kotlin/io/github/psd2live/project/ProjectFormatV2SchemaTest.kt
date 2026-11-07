package io.github.psd2live.project

import io.github.psd2live.core.modifier.ModifierCapability
import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFormatV2SchemaTest {
	/** A layout-node value as the writer stores it: the state under the key named by its kind. */
	private fun schema(kind: String, content: JsonObject): Int =
		ProjectFormatV2.schemaOf(kind, buildJsonObject { put(kind, content) })

	@Test fun layoutFieldsAddedAfterLayoutNodesNeedANewerSchema() {
		assertEquals(ProjectFormatV2.NODE_SCHEMA, schema("layout", buildJsonObject {
			putJsonObject("generation") { put("generated", true) }
			putJsonObject("layerTags") { putJsonArray("layer") { add(JsonPrimitive("tag")) } }
		}))
		// A single reference image, several references, their layers, pins and drawings all postdate layout nodes.
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("layout", buildJsonObject {
			put("reference", buildJsonObject {})
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("layout", buildJsonObject {
			putJsonArray("references") { add(buildJsonObject {}) }
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("layout", buildJsonObject {
			putJsonArray("referenceLayerIds") { add(JsonPrimitive("guide")) }
			putJsonObject("referencePins") { putJsonArray("guide") { add(buildJsonObject {}) } }
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("layout", buildJsonObject {
			putJsonObject("drawingKit") { put("styles", ".part{}") }
		}))
	}

	@Test fun materialFieldsAddedAfterLayoutNodesNeedANewerSchema() {
		assertEquals(ProjectFormatV2.NODE_SCHEMA, schema("materials", buildJsonObject {
			putJsonArray("folders") {
				add(buildJsonObject { put("id", "folder"); put("name", "Folder") })
			}
			putJsonArray("items") {
				add(buildJsonObject { put("id", "item"); put("name", "Item"); put("kind", "IMAGE"); putJsonArray("placement") { add(JsonPrimitive(0)) } })
			}
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("materials", buildJsonObject {
			putJsonArray("items") { add(buildJsonObject { put("id", "item"); put("reference", true) }) }
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("materials", buildJsonObject {
			putJsonArray("items") { add(buildJsonObject { put("id", "item"); putJsonArray("placementCanvas") { add(JsonPrimitive(100)); add(JsonPrimitive(100)) } }) }
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_LAYOUT, schema("materials", buildJsonObject {
			putJsonArray("folders") { add(buildJsonObject { put("id", "folder"); put("origin", "PSD") }) }
		}))
	}

	@Test fun clipsWithExpressionsAPoseOrEventsNeedANewerSchemaAndSplitLosslessly(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
		val motion = buildJsonObject { put("id", "wave"); put("name", "Wave") }
		assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.clipsSchema(buildJsonObject { putJsonArray("motions") { add(motion) } }))
		assertEquals(ProjectFormatV2.NODE_SCHEMA_SIDECARS, ProjectFormatV2.clipsSchema(buildJsonObject {
			putJsonArray("motions") { add(JsonObject(motion + ("events" to buildJsonArray { add(buildJsonObject { put("time", 0.5); put("value", "peak") }) }))) }
		}))
		val snapshot = buildJsonObject {
			putJsonObject("rigEdits") {
				putJsonArray("motions") { add(motion) }
				putJsonArray("expressions") { add(buildJsonObject { put("id", "joy"); put("name", "Joy"); putJsonArray("entries") {} }) }
				putJsonObject("pose") { put("fade_in", 0.5); putJsonArray("groups") { add(buildJsonArray { add(buildJsonObject { put("part", "PartArmA") }) }) } }
			}
		}
		val index = ProjectFormatV2.split(root, snapshot)
		val clips = kotlinx.serialization.json.Json.parseToJsonElement(java.nio.file.Files.readString(
			root.resolve("document/clips/${(index.getValue("clips") as JsonPrimitive).content}.json"))) as JsonObject
		assertEquals(JsonPrimitive(ProjectFormatV2.NODE_SCHEMA_SIDECARS), clips["schema"])
		assertEquals(snapshot.getValue("rigEdits"), (ProjectFormatV2.join(root, index).getValue("rigEdits") as JsonObject)
			.let { JsonObject(it.filterKeys { key -> key in setOf("motions", "expressions", "pose") }) })
	}

	@Test fun unpackFindsExactPluginLocksInHistoricalRevisions(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
		val swing = ModifierPluginReference("example.swing", "1.2.0", "1".repeat(64))
		val physics = ModifierPluginReference("example.physics", "1.0.0", "2".repeat(64))
		val targets = listOf(ModifierTarget("mesh", "body"))
		val historical = RigModifierStack(appliedCapabilities = listOf(ModifierCapability("physics", 1, targets, plugin = physics)))
			.add("swing", 1, targets, id = "spring", plugin = swing).update("spring") { it.copy(enabled = false) }
		val stackNode = ProjectFormatV2.split(root, buildJsonObject {
			putJsonObject("rigEdits") { put("modifierStack", historical.toJson()) }
		})
		val emptyHead = ProjectFormatV2.split(root, buildJsonObject {})
		ProjectFormatV2.writeRevision(root, "1".repeat(64), stackNode)
		ProjectFormatV2.writeRevision(root, "2".repeat(64), emptyHead)
		java.nio.file.Files.writeString(root.resolve("history/HEAD.json"), "{}")

		assertEquals(setOf(swing, physics), ProjectFormatV2.unpack(root, "project"))
	}

}
