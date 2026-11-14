package io.github.psd2live.application

import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.LayoutReference
import io.github.psd2live.project.WorkspaceLayoutCodec
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceReferenceEditsTest {
	private val document = WorkspaceMaterialEdits.addLinked(Characters.head(false), "head.psd")
	private val face = document.layout.layerMaterials.getValue("face")
	private val hair = document.layout.layerMaterials.getValue("hair")

	@Test
	fun referencesAreAddedTurnedLockedLaidUnderAndReordered() {
		val one = WorkspaceMaterialEdits.addReference(document, buildJsonObject { put("material_id", face) })
		val two = WorkspaceMaterialEdits.addReference(one, buildJsonObject { put("material_id", hair); put("opacity", 0.8f) })
		val (first, second) = two.layout.references
		assertEquals(LayoutReference.FIRST_ID, first.id)
		assertTrue(second.id.startsWith("ref_") && second.materialId == hair && second.opacity == 0.8f)
		val turned = WorkspaceMaterialEdits.reference(two, buildJsonObject {
			put("id", second.id); put("rotation", -30f); put("locked", true); put("under", true)
		}).layout.reference(second.id)!!
		assertEquals(330f, turned.rotation)
		assertTrue(turned.locked && turned.under && turned.rect == second.rect)
		// Without an id the first one is meant, as before there could be several.
		val faded = WorkspaceMaterialEdits.reference(two, buildJsonObject { put("opacity", 0.25f) })
		assertEquals(listOf(0.25f, 0.8f), faded.layout.references.map { it.opacity })
		val ordered = WorkspaceMaterialEdits.orderReference(two, second.id, 0)
		assertEquals(listOf(second.id, first.id), ordered.layout.references.map { it.id })
		val removed = WorkspaceMaterialEdits.reference(ordered, buildJsonObject { put("id", second.id); put("material_id", JsonNull) })
		assertEquals(listOf(first.id), removed.layout.references.map { it.id })
	}

	@Test
	fun aSingleReferenceIsWrittenAsDocumentsBeforeSeveralWrote() {
		val single = document.layout.copy(references = listOf(LayoutReference(face, LayerCanvasRect(1f, 2f, 30f, 40f), 0.4f)))
		val written = WorkspaceLayoutCodec.encodeLayout(single)!!
		assertEquals(setOf("materialId", "rect", "opacity"), written.getValue("reference").jsonObject.keys)
		assertNull(written["references"])
		val several = single.copy(references = single.references + LayoutReference(hair, LayerCanvasRect(0f, 0f, 10f, 10f), id = "ref_x",
			rotation = 15f, locked = true, under = true))
		val read = WorkspaceLayoutCodec.decode(WorkspaceLayoutCodec.encodeLayout(several), null) { _, _, _ -> ByteArray(0) }
		assertEquals(several.references, read.references)
		assertEquals(single.references, WorkspaceLayoutCodec.decode(written, null) { _, _, _ -> ByteArray(0) }.references)
	}

	@Test
	fun aReferenceMovesBetweenBackgroundLayerAndForegroundKeepingItsPin() = kotlinx.coroutines.runBlocking {
		val builder = WorkspacePreviewBuilder()
		val model = builder.build(document)
		val parameter = model.rig.puppet.parameters.first()
		val image = WorkspaceMaterialEdits.addReference(document, buildJsonObject {
			put("material_id", face); put("opacity", 0.5f)
			put("points", kotlinx.serialization.json.buildJsonArray {
				add(buildJsonObject { put(parameter.id.raw, parameter.max) }); add(buildJsonObject { put(parameter.id.raw, parameter.min) })
			})
		})
		val reference = image.layout.references.single()
		suspend fun mode(document: io.github.psd2live.project.WorkspaceDocument, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
			WorkspaceReferenceModes.apply(document, builder.build(document), buildJsonObject(build))
		val behind = mode(image) { put("reference_id", reference.id); put("mode", "background") }
		assertTrue(behind.layout.references.single().under)
		// Into the stack: a reference layer showing the same material over the same rectangle, pinned the same.
		val layered = mode(behind) { put("reference_id", reference.id); put("mode", "layer") }
		assertTrue(layered.layout.references.isEmpty())
		val layer = layered.layout.referenceLayerIds.single()
		assertEquals(face, layered.layout.layerMaterials[layer])
		assertEquals(listOf(mapOf(parameter.id.raw to parameter.max), mapOf(parameter.id.raw to parameter.min)), layered.layout.referencePins[layer])
		// And out again in front: an image once more, the layer gone.
		val front = mode(layered) { put("layer_id", layer); put("mode", "foreground") }
		val back = front.layout.references.single()
		assertTrue(!back.under && back.materialId == face && back.pins == reference.pins)
		assertTrue(front.layout.referenceLayerIds.isEmpty() && front.source.layers.none { it.id.raw == layer })
		assertEquals(reference.rect, back.rect)
		assertEquals(reference.opacity, back.opacity)
	}

	@Test
	fun aReferenceShowsAtAnyOfItsPointsAndOnePointIsWrittenAsBefore() {
		val parameters = mapOf("A" to org.umamo.runtime.model.Parameter(org.umamo.runtime.model.ParameterId("A"), "A", -30f, 30f, 0f),
			"B" to org.umamo.runtime.model.Parameter(org.umamo.runtime.model.ParameterId("B"), "B", -1f, 1f, 0f))
		val points = listOf(mapOf("A" to 30f), mapOf("A" to -30f, "B" to 1f))
		assertTrue(io.github.psd2live.project.ReferencePins.shows(points, parameters) { mapOf("A" to 30f)[it] })
		assertTrue(io.github.psd2live.project.ReferencePins.shows(points, parameters) { mapOf("A" to -30f, "B" to 1f)[it] })
		assertTrue(!io.github.psd2live.project.ReferencePins.shows(points, parameters) { mapOf("A" to -30f)[it] })
		assertTrue(io.github.psd2live.project.ReferencePins.shows(emptyList(), parameters) { null })
		val one = io.github.psd2live.project.ReferencePins.encode(listOf(mapOf("B" to 1f, "A" to 2f)))
		assertEquals(buildJsonObject { put("A", 2f); put("B", 1f) }, one)
		assertEquals(points, io.github.psd2live.project.ReferencePins.decode(io.github.psd2live.project.ReferencePins.encode(points)))
	}

	@Test
	fun aReferenceInTheLayerStackIsAnArtMeshOfTheModelInItsGuidePart() = kotlinx.coroutines.runBlocking {
		val builder = WorkspacePreviewBuilder()
		val model = builder.build(document)
		val placed = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(WorkspaceMaterialEdits.PLACE, buildJsonObject {
			put("material_id", face); put("layer_id", "guide"); put("reference", true)
		}), document, model)
		// In a generated project it is in the model at once: its own mesh, no generation waiting for it.
		assertTrue("guide" in WorkspaceReferenceMeshes.createdLayerIds(placed))
		assertTrue("guide" !in WorkspaceLayoutPreview.pendingIds(placed))
		val built = builder.build(placed, model)
		val mesh = built.rig.puppet.drawables.single { built.rig.layerIdByDrawableId[it.id.raw] == "guide" }
		assertEquals(8, mesh.mesh!!.positions.size, "A new reference uses four rectangle corners")
		assertEquals(6, mesh.mesh!!.indices.size)
		val part = built.rig.puppet.parts.single { it.id.raw == WorkspaceReferenceMeshes.PART_ID }
		assertTrue(part.isSketch && org.umamo.runtime.model.OrgChild.Drawable(mesh.id) in part.children)
		assertTrue("guide" !in built.referenceLayerIds)
		// Given back to the model, it keeps its mesh and leaves the guide part.
		val given = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(WorkspaceLayoutEdits.MARK_REFERENCE, buildJsonObject {
			put("layer_ids", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("guide")) }); put("reference", false)
		}), placed, built)
		assertTrue("guide" !in given.layout.generation.pendingLayerIds && "guide" in WorkspaceReferenceMeshes.createdLayerIds(given))
		assertTrue(builder.build(given, built).rig.puppet.parts.none { it.id.raw == WorkspaceReferenceMeshes.PART_ID })
	}

    @Test fun opacityEditsWorkBeforeAndAfterGenerationWithoutJournalingPendingMeshIds() = kotlinx.coroutines.runBlocking {
        for (generated in listOf(false, true)) {
            val base = document.copy(layout = document.layout.copy(generation = document.layout.generation.copy(generated = generated)))
            val builder = WorkspacePreviewBuilder()
            val model = builder.build(base)
            val placed = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(WorkspaceMaterialEdits.PLACE, buildJsonObject {
                put("material_id", face); put("layer_id", "guide"); put("reference", true)
            }), base, model)
            val shown = builder.build(placed)
            var current = placed
            for (opacity in listOf(0f, 0.37f, 1f)) {
                val request = buildJsonObject { put("layer_id", "guide"); put("opacity", opacity) }
                current = WorkspaceDocumentEdits.apply(WorkspaceDocumentOperation(WorkspaceReferenceOpacity.OP, request), current, builder.build(current))
                val replayed = builder.build(current)
                assertEquals(opacity, replayed.rig.puppet.drawables.single { replayed.rig.layerIdByDrawableId[it.id.raw] == "guide" }.opacity)
                assertEquals(opacity, current.source.layers.single { it.id.raw == "guide" }.opacity)
                val exported = io.github.psd2live.bridge.PuppetIr.toPuppet(io.github.psd2live.bridge.PuppetIr.toIr(replayed.rig.puppet))
                assertEquals(opacity, exported.drawables.single { it.id == replayed.rig.puppet.drawables.single { d -> replayed.rig.layerIdByDrawableId[d.id.raw] == "guide" }.id }.opacity)
                assertTrue(current.rigEdits.authoringJournal.none { WorkspaceLayoutPreview.MESH_PREFIX in it.toString() })
                assertEquals(current, WorkspaceReferenceOpacity.apply(current, replayed, request), "The same value is a no-op")
            }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                WorkspaceReferenceOpacity.apply(placed, shown, buildJsonObject { put("layer_id", "guide"); put("opacity", -0.1f) })
            }
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                WorkspaceReferenceOpacity.apply(placed, shown, buildJsonObject { put("layer_id", "guide"); put("reference_id", "ref"); put("opacity", 0.5f) })
            }
        }
    }

}
