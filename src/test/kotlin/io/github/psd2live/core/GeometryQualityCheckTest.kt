package io.github.psd2live.core

import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.test.*

class GeometryQualityCheckTest {
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

    private fun report(before: PuppetModel, after: PuppetModel) = GeometryQualityCheck.evaluate(before, after)

    @Test fun safeEditPassesAndReportsNativeCoordinate() {
        val before = model()
        val after = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1.1f, 1f, 0f, 1f)))))
        val result = report(before, after)
        assertTrue(result.safe)
        assertEquals(listOf("mesh:mesh"), result.affectedTargets)
        assertEquals(listOf(emptyMap()), result.affectedCoordinates.getValue("mesh:mesh"))
        assertTrue(result.violations.isEmpty())
    }

    @Test fun classifiesNewFlipDegeneracyAndSevereCollapseWithoutRejectingAuthoring() {
        val before = model()
        val flipped = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1f, 0f, 1f)))))
        val flipReport = report(before, flipped)
        assertTrue(flipReport.safe)
        assertEquals(1, flipReport.newFlipCount)
        assertEquals(QualityRule.GEOMETRY_NEW_FLIP, flipReport.information.single().reason)

        val degenerate = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)))))
        val degenerateReport = report(before, degenerate)
        assertTrue(degenerateReport.safe)
        assertEquals(1, degenerateReport.newDegenerateCount)
        assertTrue(degenerateReport.warnings.any { it.reason == QualityRule.GEOMETRY_NEW_DEGENERATE })

        val collapsed = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0.005f, 0f, 1f)))))
        val collapseReport = report(before, collapsed)
        assertTrue(collapseReport.safe)
        assertEquals(1, collapseReport.newCollapseCount)
        assertTrue(collapseReport.information.any { it.reason == QualityRule.GEOMETRY_NEW_COLLAPSE })
    }

    @Test fun rejectsInvalidTopologyAndNonFiniteGeometry() {
        val before = model()
        val invalid = model(indices = intArrayOf(0, 1, 99))
        val invalidReport = report(before, invalid)
        assertFalse(invalidReport.safe)
        assertEquals(1, invalidReport.newInvalidTopologyCount)
        assertEquals(QualityRule.GEOMETRY_INVALID_TOPOLOGY, invalidReport.violations.single().reason)

        val values = FloatArray(8)
        values[2] = Float.NaN
        val nonFinite = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(values))))
        val nonFiniteReport = report(before, nonFinite)
        assertFalse(nonFiniteReport.safe)
        assertEquals(1, nonFiniteReport.newNonFiniteCount)
        assertEquals(QualityRule.GEOMETRY_NON_FINITE, nonFiniteReport.violations.single().reason)
    }

    @Test fun reportsExistingAndNewFlipsWithoutTreatingEitherAsInvalidData() {
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
        assertTrue(rejected.safe)
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
        assertTrue(GeometryQualityCheck.evaluate(before, candidate).safe)
        assertContentEquals(floatArrayOf(-1f, 0f, 1f), candidate.drawables.single().geometryGrid!!.axes.single().keys)
    }

    @Test fun wholeSurfaceAffineMirrorAndCompressionAreExemptAndZeroAreaIsWarning() {
        val before = model()
        for (scale in listOf(-1f, 0.005f)) {
            val after = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, scale, 0f, scale)))))
            assertTrue(report(before, after).safe)
        }
        val zero = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f)))))
        assertTrue(report(before, zero).safe)
        assertTrue(report(before, zero).warnings.isNotEmpty())
    }

    @Test fun pureBlendEditAndSimultaneousBlendKeysAreChecked() {
        val before = model().copy(parameters = listOf(Parameter(p, "Blend", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE),
            Parameter(q, "Other", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE)))
        fun blend(id: ParameterId, amount: Float) = BlendShapeBinding(id, floatArrayOf(0f, 1f), 0,
            listOf(null, MeshForm(floatArrayOf(0f, 0f, 0f, 0f, 0f, amount, 0f, 0f), opacity = 1f)))
        val after = before.copy(drawables = listOf(before.drawables.single().copy(blendShapes = listOf(blend(p, -0.6f), blend(q, -0.6f)))))
        val result = report(before, after)
        assertTrue(result.safe)
        assertEquals(4, result.affectedCoordinates.getValue("mesh:mesh").size)
        assertTrue(result.information.any { it.coordinate == mapOf("P" to 1f, "Q" to 1f) })
        val single = before.copy(drawables = listOf(before.drawables.single().copy(blendShapes = listOf(blend(p, -2f)))))
        assertTrue(report(before, single).safe)
    }

    @Test fun newInterpolatedKeyDoesNotMisclassifyAnExistingDefect() {
        val axis = KeyformAxis(p, floatArrayOf(-1f, 1f))
        val shape = MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1f, 0f, 1f))
        val before = model(listOf(KeyformCell(intArrayOf(0), shape), KeyformCell(intArrayOf(1), shape)), listOf(axis))
        val after = model(listOf(KeyformCell(intArrayOf(0), shape), KeyformCell(intArrayOf(1), shape), KeyformCell(intArrayOf(2), shape)),
            listOf(KeyformAxis(p, floatArrayOf(-1f, 0f, 1f))))
        assertTrue(report(before, after).safe)
        assertEquals(0, report(before, after).newFlipCount)
    }

    @Test fun excessiveBlendCombinationsReportIncompleteCoverageWithoutRejectingAuthoring() {
        val parameters = (0..14).map { Parameter(ParameterId("Blend$it"), "Blend $it", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE) }
        val before = model().copy(parameters = parameters)
        val blends = parameters.map { BlendShapeBinding(it.id, floatArrayOf(0f, 1f), 0,
            listOf(null, MeshForm(FloatArray(8), opacity = 1f))) }
        val after = before.copy(drawables = listOf(before.drawables.single().copy(blendShapes = blends)))
        val result = report(before, after)
        assertTrue(result.safe)
        assertFalse(result.quality.complete)
        assertEquals(QualityDecision.ACCEPT_WITH_DIAGNOSTICS, result.quality.decision)
        assertEquals(QualityRule.GEOMETRY_SAMPLING_LIMIT, result.warnings.single().reason)
        assertEquals(emptyList(), result.affectedCoordinates.getValue("mesh:mesh"))
        // A budget limit must not hide invalid native data.
        val corrupt = after.copy(drawables = listOf(after.drawables.single().copy(
            mesh = DrawableMesh(FloatArray(8) { Float.NaN }, base.copyOf(), triangles))))
        assertFalse(report(before, corrupt).safe)
        assertEquals(QualityRule.GEOMETRY_NON_FINITE, report(before, corrupt).violations.single().reason)
        val mixed = after.copy(drawables = after.drawables + corrupt.drawables.single().copy(id = DrawableId("second")))
        val mixedReport = report(before, mixed)
        assertFalse(mixedReport.safe)
        assertFalse(mixedReport.quality.complete)
        assertEquals(QualityRule.GEOMETRY_SAMPLING_LIMIT, mixedReport.warnings.single().reason)
        assertEquals(QualityRule.GEOMETRY_NON_FINITE, mixedReport.violations.single().reason)
    }

    @Test fun allCallersUseTheSharedInformationAndWarningPolicy() {
        val before = model()
        val flipped = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1f, 0f, 1f)))))
        val allowed = GeometryQualityCheck.evaluate(before, flipped)
        assertTrue(allowed.safe)
        assertTrue(allowed.violations.isEmpty())
        assertEquals(QualityRule.GEOMETRY_NEW_FLIP, allowed.information.single().reason)
        val degenerate = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)))))
        assertTrue(GeometryQualityCheck.evaluate(before, degenerate).safe)
        assertEquals(QualitySeverity.WARNING, report(before, degenerate).quality.findings.single().rule.severity)
    }

    @Test fun tinyNegativeAreaProducesOnlyTheDegeneracyWarning() {
        val before = model()
        val after = model(listOf(KeyformCell(intArrayOf(), MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, -1e-7f, 0f, 1f)))))
        val result = report(before, after)
        assertTrue(result.safe)
        assertEquals(0, result.newFlipCount)
        assertEquals(1, result.newDegenerateCount)
        assertTrue(result.information.isEmpty())
        assertEquals(QualityRule.GEOMETRY_NEW_DEGENERATE, result.warnings.single().reason)
    }

    @Test fun newlyBoundParameterStillComparesExistingGeometryAtItsBaselinePose() {
        val form = MeshDeltaForm(deltas(0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f))
        val before = model(listOf(KeyformCell(intArrayOf(), form)))
        val after = model(listOf(KeyformCell(intArrayOf(0), form), KeyformCell(intArrayOf(1), form)),
            listOf(KeyformAxis(p, floatArrayOf(-1f, 1f))))
        val result = report(before, after)
        assertTrue(result.safe)
        assertEquals(0, result.newDegenerateCount)
        assertTrue(result.preexistingDegenerateCount > 0)
    }
}
