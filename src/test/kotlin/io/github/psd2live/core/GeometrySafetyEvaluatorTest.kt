package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class GeometrySafetyEvaluatorTest {
    private val p = ParameterId("P")
    private val q = ParameterId("Q")
    private val base = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    private val triangles = intArrayOf(0, 1, 2, 0, 2, 3)

    private fun model(
        cells: List<KeyformCell<MeshDeltaForm>> = listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(8)))),
        axes: List<KeyformAxis> = emptyList(),
        indices: IntArray = triangles,
    ): PuppetModel {
        val mesh = DrawableMesh(base.copyOf(), base.copyOf(), indices)
        val drawable = Drawable(DrawableId("mesh"), "Mesh", null, BlendMode.Normal, emptyList(), mesh, KeyformGrid(axes, cells))
        val parameters = axes.map { axis -> Parameter(axis.parameterId, axis.parameterId.raw, axis.keys.min(), axis.keys.max(), axis.keys.first()) }
        return PuppetModel(parameters, emptyList(), emptyList(), listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null)
    }

    private fun deltas(vararg points: Float): FloatArray = FloatArray(8).also { result ->
        for (i in points.indices) result[i] = points[i] - base[i]
    }

    private fun report(before: PuppetModel, after: PuppetModel) = GeometrySafetyEvaluator.evaluate(before, after)

    @Test fun safeEditPassesAndReportsNativeCoordinate() {
        val before = model()
        val after = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1.1f, 1f, 0f, 1f)))))
        val result = report(before, after)
        assertTrue(result.safe)
        assertEquals(listOf("mesh:mesh"), result.affectedTargets)
        assertEquals(listOf(emptyMap()), result.affectedCoordinates.getValue("mesh:mesh"))
        assertTrue(result.violations.isEmpty())
    }

    @Test fun rejectsNewFlipDegeneracyAndSevereCollapse() {
        val before = model()
        val flipped = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1f, 0f, 1f)))))
        val flipReport = report(before, flipped)
        assertFalse(flipReport.safe)
        assertEquals(1, flipReport.newFlipCount)
        assertEquals(GeometrySafetyReason.GEOMETRY_NEW_FLIP, flipReport.violations.single().reason)

        val degenerate = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)))))
        val degenerateReport = report(before, degenerate)
        assertFalse(degenerateReport.safe)
        assertEquals(1, degenerateReport.newDegenerateCount)
        assertTrue(degenerateReport.violations.any { it.reason == GeometrySafetyReason.GEOMETRY_NEW_DEGENERATE })

        val collapsed = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0.005f, 0f, 1f)))))
        val collapseReport = report(before, collapsed)
        assertFalse(collapseReport.safe)
        assertEquals(1, collapseReport.newCollapseCount)
        assertTrue(collapseReport.violations.any { it.reason == GeometrySafetyReason.GEOMETRY_NEW_COLLAPSE })
    }

    @Test fun rejectsInvalidTopologyAndNonFiniteGeometry() {
        val before = model()
        val invalid = model(indices = intArrayOf(0, 1, 99))
        val invalidReport = report(before, invalid)
        assertFalse(invalidReport.safe)
        assertEquals(1, invalidReport.newInvalidTopologyCount)
        assertEquals(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, invalidReport.violations.single().reason)

        val values = FloatArray(8)
        values[2] = Float.NaN
        val nonFinite = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(values))))
        val nonFiniteReport = report(before, nonFinite)
        assertFalse(nonFiniteReport.safe)
        assertEquals(1, nonFiniteReport.newNonFiniteCount)
        assertEquals(GeometrySafetyReason.GEOMETRY_NON_FINITE, nonFiniteReport.violations.single().reason)
    }

    @Test fun reportsButAllowsPreexistingFlipAndRejectsWorsening() {
        val axis = KeyformAxis(p, floatArrayOf(-1f, 1f))
        val safe = MeshDeltaForm(FloatArray(8))
        val oneFlip = MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1f, 0f, 1f))
        val before = model(listOf(KeyformCell(intArrayOf(0), safe), KeyformCell(intArrayOf(1), oneFlip)), listOf(axis))
        val unrelatedSafeEdit = model(listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1.1f, 1f, 0f, 1f))),
            KeyformCell(intArrayOf(1), oneFlip),
        ), listOf(axis))
        val allowed = report(before, unrelatedSafeEdit)
        assertTrue(allowed.safe)
        assertTrue(allowed.preexistingFlipCount >= 1)
        assertEquals(0, allowed.newFlipCount)

        val twoFlips = MeshDeltaForm(deltas(0f, 0f, 1f, 0f, -1f, -1f, 0f, 1f))
        val worsening = model(listOf(KeyformCell(intArrayOf(0), safe), KeyformCell(intArrayOf(1), twoFlips)), listOf(axis))
        val rejected = report(before, worsening)
        assertFalse(rejected.safe)
        assertTrue(rejected.preexistingFlipCount >= 1)
        assertEquals(1, rejected.newFlipCount)
    }

    @Test fun multiAxisCoordinatesMatchByIdentityNotCellOrder() {
        val axes = listOf(KeyformAxis(p, floatArrayOf(-1f, 1f)), KeyformAxis(q, floatArrayOf(-1f, 1f)))
        val baseline = listOf(
            KeyformCell(intArrayOf(0, 0), MeshDeltaForm(FloatArray(8))),
            KeyformCell(intArrayOf(1, 0), MeshDeltaForm(FloatArray(8))),
            KeyformCell(intArrayOf(0, 1), MeshDeltaForm(FloatArray(8))),
            KeyformCell(intArrayOf(1, 1), MeshDeltaForm(FloatArray(8))),
        )
        val candidate = listOf(
            baseline[3], baseline[1],
            KeyformCell(intArrayOf(0, 0), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1.05f, 1f, 0f, 1f))),
            baseline[2],
        )
        val result = report(model(baseline, axes), model(candidate, axes))
        assertTrue(result.safe)
        assertEquals(4, result.affectedCoordinates.getValue("mesh:mesh").size)
        assertTrue(result.affectedCoordinates.getValue("mesh:mesh").contains(mapOf("P" to -1f, "Q" to -1f)))
    }

    @Test fun repeatedEvaluationIsDeterministic() {
        val before = model()
        val after = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1.1f, 1f, 0f, 1f)))))
        assertEquals(report(before, after).toJson(), report(before, after).toJson())
    }

    @Test fun orderedCompileLetsLaterCommandUseStateCreatedEarlier() {
        val axis = KeyformAxis(p, floatArrayOf(-1f, 1f))
        val before = model(listOf(
            KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
            KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8))),
        ), listOf(axis))
        val addKey = buildJsonObject {
            put("op", "parameter_keys"); put("target", "mesh:mesh"); put("parameter", "P")
            put("track", "geometry"); put("action", "add"); putJsonArray("values") { add(0f) }
        }
        val setNewKey = buildJsonObject {
            put("op", "set"); put("target", "mesh:mesh"); putJsonObject("key") { put("P", 0f) }
            putJsonObject("geometry") { put("positionDeltas", JsonArray(deltas(0f, 0f, 1f, 0f, 1.1f, 1f, 0f, 1f).map(::JsonPrimitive))) }
        }
        val (candidate, journal) = RigAuthoringJournal.compile(before, buildJsonArray { add(addKey); add(setNewKey) })
        assertEquals(2, journal.size)
        assertTrue(GeometrySafetyEvaluator.evaluate(before, candidate).safe)
        assertContentEquals(floatArrayOf(-1f, 0f, 1f), candidate.drawables.single().geometryGrid!!.axes.single().keys)
    }
}
