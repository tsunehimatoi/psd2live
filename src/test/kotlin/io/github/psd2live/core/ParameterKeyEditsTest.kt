package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class ParameterKeyEditsTest {
    private val id = ParameterId("P")
    private fun model(): PuppetModel {
        val axis = KeyformAxis(id, floatArrayOf(-1f, 1f))
        val geometry = KeyformGrid(listOf(axis), listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f))),
            KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(2f, 4f))),
        ))
        val channels = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid<ChannelValue>(listOf(axis), listOf(
            KeyformCell(intArrayOf(0), ChannelValue.Scalar(0f)), KeyformCell(intArrayOf(1), ChannelValue.Scalar(1f)),
        ))))
        val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(),
            DrawableMesh(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), intArrayOf()), geometry, channels)
        return PuppetModel(parameters = listOf(Parameter(id, "P", -1f, 1f, 0f)), parts = emptyList(),
            deformers = emptyList(), drawables = listOf(drawable, drawable.copy(id = DrawableId("other"))),
            rootChildren = emptyList(), rootPartId = null)
    }
    private fun edit(action: String, values: List<Float>, track: String = "geometry", from: Float? = null) = buildJsonObject {
        put("op", "parameter_keys"); put("target", "mesh:mesh"); put("parameter", "P")
        put("track", track); put("action", action); put("values", JsonArray(values.map(::JsonPrimitive)))
        from?.let { put("from", it) }
    }
    @Test fun addInterpolatesMoveKeepsFormAndDeleteIsTrackScoped() {
        val before = model()
        val added = RigAuthoringJournal.apply(before, edit("add", listOf(0f)))
        val grid = added.drawables.first().geometryGrid!!
        assertContentEquals(floatArrayOf(-1f, 0f, 1f), grid.axes.single().keys)
        assertContentEquals(floatArrayOf(1f, 2f), grid.cells.single { it.coordinate[0] == 1 }.form.positionDeltas)
        assertSame(before.drawables[1], added.drawables[1])
        assertSame(before.drawables.first().channelGrids, added.drawables.first().channelGrids)
        val moved = RigAuthoringJournal.apply(added, edit("move", listOf(0.5f), from = 0f))
        assertContentEquals(floatArrayOf(-1f, 0.5f, 1f), moved.drawables.first().geometryGrid!!.axes.single().keys)
        assertSame(grid.cells[1].form, moved.drawables.first().geometryGrid!!.cells[1].form)
        val deleted = RigAuthoringJournal.apply(moved, edit("delete", listOf(0.5f)))
        assertContentEquals(floatArrayOf(-1f, 1f), deleted.drawables.first().geometryGrid!!.axes.single().keys)
    }
    @Test fun channelEditDoesNotModifyGeometry() {
        val before = model()
        val after = RigAuthoringJournal.apply(before, edit("add", listOf(0f), "OPACITY"))
        assertSame(before.drawables.first().geometryGrid, after.drawables.first().geometryGrid)
        val grid = after.drawables.first().channelGrids[FormChannel.OPACITY]!!
        assertEquals(ChannelValue.Scalar(0.5f), grid.cells.single { it.coordinate[0] == 1 }.form)
    }
    @Test fun seedCreatesRequestedPointsAndRepeatedAddHasNoHistoryEntry() {
        val before = model().let { it.copy(drawables = listOf(it.drawables.first().copy(geometryGrid = null))) }
        val command = edit("add", listOf(-1f, 1f))
        val (after, journal) = RigAuthoringJournal.compile(before, JsonArray(listOf(command, command)))
        assertEquals(1, journal.size)
        assertContentEquals(floatArrayOf(-1f, 1f), after.drawables.first().geometryGrid!!.axes.single().keys)
        assertTrue(after.drawables.first().geometryGrid!!.cells.all { it.form.positionDeltas.all { v -> v == 0f } })
    }
    @Test fun rejectsInvalidMovesAndDestructiveLastKeyRemoval() {
        val before = model()
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(before, edit("add", listOf(2f))) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(before, edit("delete", listOf(1f))) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.apply(before, edit("move", listOf(1f), from = -1f)) }
        assertFailsWith<IllegalArgumentException> { RigAuthoringJournal.compile(before, JsonArray(listOf(edit("add", listOf(0f)), edit("move", listOf(0f), from = 0.8f)))) }
        assertContentEquals(floatArrayOf(-1f, 1f), before.drawables.first().geometryGrid!!.axes.single().keys)
    }
    @Test fun insertionPreservesEverySliceOfOtherAxesAndReplays() {
        val q = ParameterId("Q")
        val before = model().let { base ->
            val axes = listOf(KeyformAxis(id, floatArrayOf(-1f, 1f)), KeyformAxis(q, floatArrayOf(0f, 1f)))
            val cells = (0..1).flatMap { y -> (0..1).map { x -> KeyformCell(intArrayOf(x, y), MeshDeltaForm(floatArrayOf(x * 2f + y * 10f, 0f))) } }
            base.copy(parameters = base.parameters + Parameter(q, "Q", 0f, 1f, 0f),
                drawables = listOf(base.drawables.first().copy(geometryGrid = KeyformGrid(axes, cells))))
        }
        val (after, journal) = RigAuthoringJournal.compile(before, JsonArray(listOf(edit("add", listOf(0f)))))
        val grid = after.drawables.first().geometryGrid!!
        assertEquals(6, grid.cells.size)
        assertContentEquals(floatArrayOf(0f, 1f), grid.axes[1].keys)
        for (y in 0..1) assertEquals(1f + y * 10f, grid.cells.single { it.coordinate.contentEquals(intArrayOf(1, y)) }.form.positionDeltas[0])
        val replay = journal.fold(before, RigAuthoringJournal::apply).drawables.first().geometryGrid!!
        for (cell in grid.cells) assertContentEquals(cell.form.positionDeltas, replay.cells.single { it.coordinate.contentEquals(cell.coordinate) }.form.positionDeltas)
    }

    @Test fun propertiesAndPointDraftCompileAsOneAtomicBatch() {
        val before = model()
        val properties = buildJsonObject {
            put("op", "structure")
            putJsonArray("edits") { add(buildJsonObject {
                put("kind", "parameter"); put("action", "update"); put("id", "P")
                put("name", "Updated"); put("min", -2f); put("max", 2f); put("default", 0f)
            }) }
        }
        val commands = JsonArray(listOf(properties, edit("add", listOf(0f)), edit("move", listOf(0.5f), from = 0f)))
        val (after, journal) = RigAuthoringJournal.compile(before, commands)
        assertEquals("Updated", after.parameters.single().name)
        assertContentEquals(floatArrayOf(-1f, 0.5f, 1f), after.drawables.first().geometryGrid!!.axes.single().keys)
        assertEquals(3, journal.size)
        assertEquals("P", before.parameters.single().name)
        assertContentEquals(floatArrayOf(-1f, 1f), before.drawables.first().geometryGrid!!.axes.single().keys)
        assertFailsWith<IllegalArgumentException> {
            RigAuthoringJournal.compile(before, JsonArray(commands + edit("add", listOf(3f))))
        }
        assertEquals("P", before.parameters.single().name)
    }

    @Test fun parameterPointsDoNotRequireAComponentAndMovesFollowExistingShapes() {
        val before = model()
        val point = buildJsonObject {
            put("op", "parameter_keys"); put("parameter", "P"); put("action", "add"); put("values", JsonArray(listOf(JsonPrimitive(0f))))
        }
        val added = RigAuthoringJournal.apply(before, point)
        assertEquals(listOf(-1f, 0f, 1f), added.parameters.single().keys)
        assertSame(before.drawables.first().geometryGrid, added.drawables.first().geometryGrid)
        assertSame(before.drawables.first().channelGrids, added.drawables.first().channelGrids)
        val original = before.drawables.first().geometryGrid!!.cells[1].form
        val moved = RigAuthoringJournal.apply(added, buildJsonObject {
            put("op", "parameter_keys"); put("parameter", "P"); put("action", "move")
            put("values", JsonArray(listOf(JsonPrimitive(0.5f)))); put("from", 1f)
        })
        assertEquals(listOf(-1f, 0f, 0.5f), moved.parameters.single().keys)
        assertContentEquals(floatArrayOf(-1f, 0.5f), moved.drawables.first().geometryGrid!!.axes.single().keys)
        assertSame(original, moved.drawables.first().geometryGrid!!.cells[1].form)
        val deleted = RigAuthoringJournal.apply(moved, buildJsonObject {
            put("op", "parameter_keys"); put("parameter", "P"); put("action", "delete")
            put("values", JsonArray(listOf(JsonPrimitive(0.5f))))
        })
        assertEquals(listOf(-1f, 0f), deleted.parameters.single().keys)
        val axes = deleted.drawables.first().geometryGrid?.axes.orEmpty()
        assertTrue(axes.none { axis ->
            axis.parameterId == id && axis.keys.any { kotlin.math.abs(it - 0.5f) < 0.001f }
        })
    }

}
