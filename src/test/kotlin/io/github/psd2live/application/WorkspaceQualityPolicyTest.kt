package io.github.psd2live.application

import io.github.psd2live.core.quality.*
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigIntegrityValidator
import io.github.psd2live.core.sim.*
import org.umamo.interop.*
import org.umamo.runtime.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceQualityPolicyTest {
    private fun finding(rule: QualityRule) = QualityFinding(rule, "mesh:example", buildJsonObject { put("detail", "Observed evidence") })

    @Test fun everyDomainUsesTheSameLevelsWhileObservationPreservesErrorsWithoutBlocking() {
        for (rule in QualityRule.entries) for (fence in QualityFence.entries) {
            val report = QualityInspection.combine(fence, listOf(QualityCheckResult("test", "Declared sample", listOf(finding(rule)))))
            validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, report.toJson())
            assertEquals(fence == QualityFence.OBSERVATION || rule.severity != QualitySeverity.ERROR, report.canProceed)
            assertEquals(rule.category != QualityCategory.COVERAGE, report.complete)
            assertEquals(rule.domain.wire, report.toJson()["findings"]!!.jsonArray.single().jsonObject["domain"]!!.jsonPrimitive.content)
            assertEquals(fence == QualityFence.AUTHORING_COMMIT, "can_commit" in report.toJson())
        }
    }

    @Test fun incompleteAndMissingChecksRemainVisibleAndCannotMasqueradeAsSuccess() {
        for (checks in listOf(emptyList(), listOf(QualityCheckResult("partial", "Sampled portion", emptyList(), complete = false)))) {
            val report = QualityInspection.combine(QualityFence.EXPORT_PUBLICATION, checks)
            assertTrue(report.canProceed)
            assertFalse(report.complete)
            assertEquals(QualityDecision.ACCEPT_WITH_DIAGNOSTICS, report.decision)
            assertEquals(QualityRule.INSPECTION_NOT_RUN, report.findings.single().rule)
            validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, report.toJson())
        }
        assertFailsWith<IllegalArgumentException> { QualityInspection.combine(QualityFence.OBSERVATION,
            listOf(QualityCheckResult("same", "A", emptyList()), QualityCheckResult("same", "B", emptyList()))) }
    }

    @Test fun intentionalExportOmissionAndDeclaredIdShorteningDoNotBecomeInvalidityErrors() {
        val parameter = Parameter(ParameterId("long-parameter"), "Parameter", -1f, 1f, 0f)
        val mesh = Drawable(DrawableId("guide"), "Guide", null, BlendMode.Normal, emptyList(), null, null)
        val expected = PuppetModel(listOf(parameter), emptyList(), emptyList(), listOf(mesh), emptyList(), null)
        val actual = expected.copy(parameters = listOf(parameter.copy(id = ParameterId("short"))), drawables = emptyList())
        val notices = listOf(
            ExportNotice.UnsupportedChange(ExportEntityCategory.Parameter, parameter.id.raw, ExportNoticeReason.IdTruncated(64, "short")),
            ExportNotice.UnsupportedChange(ExportEntityCategory.Drawable, mesh.id.raw, ExportNoticeReason.SketchPartIsNotRuntimeContent))
        val report = QualityInspection.combine(QualityFence.EXPORT_PUBLICATION, listOf(
            ExportIdentityCheck.inspect(ExportIdentityInput("MOC3", expected, actual, notices)),
            ExportConversionCheck.inspect(ExportConversionInput("MOC3", notices))))
        assertTrue(report.canProceed)
        assertTrue(report.findings.any { it.rule == QualityRule.EXPORT_INTENTIONAL_OMISSION })
        assertTrue(report.findings.none { it.rule == QualityRule.EXPORT_IDENTITY_MISMATCH })
        validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, report.toJson())
        val unexplained = QualityInspection.inspect(ExportIdentityInput("MOC3", expected, actual),
            listOf(ExportIdentityCheck), QualityFence.EXPORT_PUBLICATION)
        assertFalse(unexplained.canProceed)
        assertFailsWith<QualityFenceRejectedException> { unexplained.fence.requireAccepted(unexplained) { unexplained.toJson() } }
    }

    @Test fun finiteRotationShapingIsInformationButNonFiniteScaleRemainsAnError() {
        fun model(scale: Float): PuppetModel {
            val rotation = Deformer.Rotation(DeformerId("rotation"), "Rotation", null, null, 0f,
                KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 0f, scale)))))
            return PuppetModel(emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null)
        }
        val shaping = RigIntegrityValidator.inspectDirectionalWarpDimensions("test", model(2f))
            .single { it.target == "rotation:rotation" }
        assertEquals(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, shaping.rule)
        assertEquals(QualitySeverity.INFO, shaping.rule.severity)
        val invalid = RigIntegrityValidator.inspectDirectionalWarpDimensions("test", model(Float.NaN))
            .single { it.target == "rotation:rotation" }
        assertEquals(QualityRule.MODEL_NON_FINITE_GEOMETRY, invalid.rule)
        val report = QualityFence.EXPORT_PUBLICATION.inspect(listOf(invalid), "Directional scale check")
        assertFalse(report.canProceed)
        validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, report.toJson())
    }

    @Test fun domainChecksProduceStructuredEvidenceAndInvalidBakeCannotReplacePersistedBake() {
        val matte = QualityInspection.inspect(MatteQualityInput(0.2, 3, 1, 5, 2), listOf(MatteQualityCheck), QualityFence.OBSERVATION)
        assertTrue(matte.findings.any { it.rule == QualityRule.ASSET_MATTE_MISMATCH })
        assertTrue(matte.findings.any { it.rule == QualityRule.ASSET_EDGE_UNCERTAINTY })
        validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, matte.toJson())
        val observed = QualityInspection.inspect(SimulationQualityInput("sway", mapOf("fit_r2" to Float.NaN)),
            listOf(SimulationQualityCheck), QualityFence.OBSERVATION)
        assertTrue(observed.canProceed)
        assertEquals(QualityRule.SIMULATION_NON_FINITE, observed.findings.single().rule)
        assertEquals("fit_r2", observed.findings.single().evidence["attributes"]!!.jsonObject["non_finite_metrics"]!!.jsonPrimitive.content)
        validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, observed.toJson())
        val old = SimBakeResult("old", emptyMap(), emptyList(), emptyList())
        val edit = RigSimEdit("sway", "Sway", SimKind.HAIR, listOf("mesh"), bake = old)
        val overlay = RigEditOverlay(simEdits = listOf(edit))
        val invalid = SimBakeResult("new", emptyMap(), emptyList(), emptyList(), fit = Float.NaN)
        val rejection = assertFailsWith<QualityFenceRejectedException> { SimAuthoring.withBake(overlay, edit.id, invalid) }
        assertEquals(QualityFence.BAKE_PUBLICATION, rejection.report.fence)
        assertEquals(old, overlay.simEdits.single().bake)
        validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, rejection.diagnostics)
    }

    @Test fun authoringFenceSeparatesQualityAndCoverageFromInvalidData() {
        val levels = mapOf(
            QualityRule.GEOMETRY_NEW_FLIP to QualitySeverity.INFO,
            QualityRule.GEOMETRY_NEW_COLLAPSE to QualitySeverity.INFO,
            QualityRule.GEOMETRY_NEW_DEGENERATE to QualitySeverity.WARNING,
            QualityRule.GEOMETRY_SAMPLING_LIMIT to QualitySeverity.WARNING,
            QualityRule.GEOMETRY_NON_FINITE to QualitySeverity.ERROR,
            QualityRule.GEOMETRY_INVALID_TOPOLOGY to QualitySeverity.ERROR)
        assertEquals(QualityRule.entries.filter { it.domain == QualityDomain.GEOMETRY }.toSet(), levels.keys)
        for ((rule, level) in levels) {
            val report = QualityFence.AUTHORING_COMMIT.inspect(listOf(finding(rule)), "Test evidence scope")
            assertEquals(level, rule.severity)
            assertEquals(level != QualitySeverity.ERROR, report.canCommit)
            assertEquals(rule != QualityRule.GEOMETRY_SAMPLING_LIMIT, report.complete)
            validateWorkspaceResult("quality", WorkspaceQualitySchemas.report, report.toJson())
            if (level == QualitySeverity.ERROR) {
                val failure = assertFailsWith<QualityFenceRejectedException> {
                    report.fence.requireAccepted(report) { report.toJson() }
                }
                assertEquals(report, failure.report)
            } else {
                report.fence.requireAccepted(report) { report.toJson() }
            }
        }
        val empty = QualityFence.AUTHORING_COMMIT.inspect(emptyList(), "No changed geometry")
        assertEquals(QualityDecision.ACCEPT, empty.decision)
        assertTrue(empty.complete)
        val mixed = QualityFence.AUTHORING_COMMIT.inspect(QualityRule.entries.filter { it.domain == QualityDomain.GEOMETRY }.map(::finding), "Mixed evidence")
        assertEquals(QualityDecision.REJECT, mixed.decision)
        assertFalse(mixed.complete)
        assertEquals(2, mixed.blockers.size)
    }

    @Test fun publicSchemaRejectsReclassifiedFindingsAndContradictoryDecisions() {
        val report = QualityFence.AUTHORING_COMMIT.inspect(listOf(finding(QualityRule.GEOMETRY_NEW_FLIP)), "Observed pose").toJson()
        val original = report.getValue("findings").jsonArray.single().jsonObject
        val badFinding = JsonObject(original + ("severity" to JsonPrimitive("warning")))
        assertFailsWith<WorkspaceOutputContractFailure> { validateWorkspaceResult("quality", WorkspaceQualitySchemas.report,
            JsonObject(report + ("findings" to JsonArray(listOf(badFinding))))) }
        assertFailsWith<WorkspaceOutputContractFailure> { validateWorkspaceResult("quality", WorkspaceQualitySchemas.report,
            JsonObject(report + ("can_commit" to JsonPrimitive(false)))) }
    }
}
