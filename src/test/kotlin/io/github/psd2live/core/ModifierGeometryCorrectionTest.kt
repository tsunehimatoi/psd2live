package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class ModifierGeometryCorrectionTest {
    private fun model(value: Float = 0f): PuppetModel {
        val mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
        val grid = KeyformGrid<MeshDeltaForm>(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(6) { value }))))
        val drawable = Drawable(DrawableId("mouth"), "Mouth", null, BlendMode.Normal, emptyList(), mesh, grid, texturePage = 0)
        return PuppetModel(emptyList(), emptyList(), emptyList(), listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null)
    }

    private fun command(base: PuppetModel, relative: Boolean) = buildJsonObject {
        put("op", GeneratedOverrides.OP); put("generator", "modifier:mouth"); put("target", "mesh:mouth")
        put("key", JsonObject(emptyMap()))
        put("base", JsonArray(List(6) { JsonPrimitive(0f) }))
        put("points", JsonArray(List(6) { JsonPrimitive(if (it == 0) .25f else 0f) }))
        if (relative) {
            put("merge_mode", "delta")
            put("geometry_binding", GeneratedOverrides.geometryBinding(base, "mesh", "mouth"))
        }
    }

    @Test fun relativeCorrectionFollowsRegenerationWhileLegacyOverrideKeepsItsValue() {
        val generated = model(1f)
        val relative = GeneratedOverrides.apply(generated, command(model(), true))
        assertEquals(1.25f, relative.model.drawables.single().geometryGrid!!.cells.single().form.positionDeltas[0])
        assertTrue(relative.issues.isEmpty())
        val old = GeneratedOverrides.apply(generated, command(model(), false))
        assertEquals(.25f, old.model.drawables.single().geometryGrid!!.cells.single().form.positionDeltas[0])
        assertEquals(GeneratedOverrideIssueKind.CONFLICT, old.issues.single().kind)
    }

    @Test fun onlyArtistBoundAxesCanBeCreatedWhenAnInsertedKeyReplays() {
        val open = ParameterId("Open")
        val source = model().copy(parameters = listOf(Parameter(open, "Open", 0f, 1f, 0f)))
        val key = buildJsonObject { put("Open", .4f) }
        val old = JsonObject(command(source, true) + mapOf("key" to key, "insert_keys" to JsonPrimitive(true)))
        val artistBound = JsonObject(old + ("source_axes" to JsonArray(emptyList())))
        val updated = GeneratedOverrides.apply(source, artistBound)
        assertTrue(updated.issues.isEmpty())
        val grid = updated.model.drawables.single().geometryGrid!!
        assertContentEquals(floatArrayOf(0f, .4f, 1f), grid.axes.single().keys)
        assertEquals(.25f, grid.cells.single { it.coordinate.single() == 1 }.form.positionDeltas.first())

        val lostAxis = JsonObject(old + ("source_axes" to JsonArray(listOf(JsonPrimitive("Open")))))
        for (record in listOf(old, lostAxis)) {
            val replay = GeneratedOverrides.apply(source, record)
            assertSame(source, replay.model)
            assertEquals(GeneratedOverrideOrphanReason.MISSING_KEYFORM, replay.issues.single().reason)
        }
    }

    @Test fun equalVertexCountDoesNotPermitReplayingOnChangedConnectivity() {
        val source = model()
        val drawable = source.drawables.single()
        val mesh = drawable.mesh!!
        val rebuilt = source.copy(drawables = listOf(drawable.copy(mesh = DrawableMesh(mesh.positions, mesh.uvs, intArrayOf(0, 2, 1)))))
        val outcome = GeneratedOverrides.apply(rebuilt, command(source, true))
        assertSame(rebuilt, outcome.model)
        assertEquals(GeneratedOverrideOrphanReason.SHAPE_MISMATCH, outcome.issues.single().reason)
    }

    @Test fun serializedCorrectionDoesNotAccumulateAcrossRebuilds() {
        val record = Json.parseToJsonElement(command(model(), true).toString()).jsonObject
        repeat(3) {
            assertEquals(2.25f, GeneratedOverrides.apply(model(2f), record).model.drawables.single()
                .geometryGrid!!.cells.single().form.positionDeltas[0])
        }
    }

    @Test fun preservingSharedWarpChildrenKeepsNestedRotationAndSiblingAppearance() {
        val base = model()
        val parameter = ParameterId("P")
        val lattice = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val parent = Deformer.Warp(DeformerId("parent"), "Parent", null, null, 1, 1, true,
            KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), WarpLatticeForm(lattice)), KeyformCell(intArrayOf(1), WarpLatticeForm(lattice.copyOf())))))
        val rotation = Deformer.Rotation(DeformerId("rotation"), "Rotation", parent.id, null, 25f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(.2f, .3f, 10f, 1f)))))
        val child = base.drawables.single().copy(parentDeformerId = rotation.id)
        val sibling = child.copy(id = DrawableId("sibling"), parentDeformerId = parent.id)
        val source = base.copy(parameters = listOf(Parameter(parameter, "P", 0f, 1f, 0f)),
            deformers = listOf(parent, rotation), drawables = listOf(child, sibling),
            rootChildren = listOf(OrgChild.Drawable(child.id), OrgChild.Drawable(sibling.id)))
        val corrected = CanvasEdits.apply(source, buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "parent"); put("preserve_children", true)
            put("key", buildJsonObject { put("P", .35f) })
            put("points", JsonArray(lattice.mapIndexed { i, value -> JsonPrimitive(value + if (i % 2 == 0) .1f else 0f) }))
        })
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        for (value in listOf(0f, .1f, .35f, .6f, 1f)) {
            val pose = mapOf(parameter to value)
            val expected = evaluator.evaluate(source, pose).worldPositions
            val actual = evaluator.evaluate(corrected, pose).worldPositions
            expected.forEach { (id, points) -> points.indices.forEach { i ->
                assertEquals(points[i], actual.getValue(id)[i], .0001f, "parameter=$value drawable=$id component=$i")
            } }
        }
        val blendId = ParameterId("Blend")
        val blended = source.copy(parameters = source.parameters + Parameter(blendId, "Blend", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE),
            deformers = listOf(parent), drawables = listOf(child.copy(parentDeformerId = parent.id,
                blendShapes = listOf(BlendShapeBinding(blendId, floatArrayOf(0f, 1f), 0,
                    listOf(null, MeshForm(FloatArray(6) { if (it % 2 == 0) .2f else 0f })))))),
            rootChildren = listOf(OrgChild.Drawable(child.id)))
        val translatedBlend = CanvasEdits.apply(blended, buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "parent"); put("preserve_children", true)
            put("key", buildJsonObject { put("P", .35f) })
            put("points", JsonArray(lattice.mapIndexed { i, value -> JsonPrimitive(value + if (i % 2 == 0) .1f else 0f) }))
        })
        for (blend in listOf(0f, .25f, .5f, .75f, 1f)) {
            val pose = mapOf(parameter to .35f, blendId to blend)
            val expected = evaluator.evaluate(blended, pose).worldPositions.getValue(child.id)
            val actual = evaluator.evaluate(translatedBlend, pose).worldPositions.getValue(child.id)
            expected.indices.forEach { assertEquals(expected[it], actual[it], .0001f) }
        }
        assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(blended, buildJsonObject {
                put("op", "canvas_geometry"); put("kind", "warp"); put("id", "parent"); put("preserve_children", true)
                put("key", buildJsonObject { put("P", .35f) })
                put("points", JsonArray(lattice.mapIndexed { i, value -> JsonPrimitive(value + if (i == 6) .4f else 0f) }))
            })
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            CanvasEdits.apply(source, buildJsonObject {
                put("op", "canvas_geometry"); put("kind", "warp"); put("id", "parent"); put("preserve_children", true)
                put("key", buildJsonObject { put("P", .35f) })
                put("points", JsonArray(lattice.mapIndexed { i, value -> JsonPrimitive(value + if (i == 6) .4f else 0f) }))
            })
        }
        assertTrue(failure.message.orEmpty().contains("preserve"), failure.message)
    }

    @Test fun insertingAnIntermediateKeyPreservesTheWholeInterpolatedTrack() {
        val p = ParameterId("open")
        val base = model()
        val drawable = base.drawables.single()
        val source = base.copy(parameters = listOf(Parameter(p, "Open", 0f, 1f, 0f)), drawables = listOf(drawable.copy(
            geometryGrid = KeyformGrid(listOf(KeyformAxis(p, floatArrayOf(0f, 1f))), listOf(
                KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))),
                KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6) { 2f })))))))
        val inserted = GeneratedOverrides.withGeometryKeys(source, "mesh", "mouth", mapOf("open" to .35f))
        assertContentEquals(floatArrayOf(0f, .35f, 1f), inserted.drawables.single().geometryGrid!!.axes.single().keys)
        for (value in listOf(0f, .1f, .35f, .6f, 1f)) {
            val before = RigGeometryTools.geometry(source, "mesh", "mouth", mapOf("open" to value)).points
            val after = RigGeometryTools.geometry(inserted, "mesh", "mouth", mapOf("open" to value)).points
            before.indices.forEach { assertEquals(before[it], after[it], .000001f) }
        }
    }
}
