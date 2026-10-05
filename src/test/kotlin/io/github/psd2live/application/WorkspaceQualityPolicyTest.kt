package io.github.psd2live.application

import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WorkspaceQualityPolicyTest {
    private fun finding(rule: QualityRule) = QualityFinding(rule, "mesh:example", buildJsonObject { put("detail", "Observed evidence") })

    @Test fun authoringFenceSeparatesQualityAndCoverageFromInvalidData() {
        val levels = mapOf(
            QualityRule.GEOMETRY_NEW_FLIP to QualitySeverity.INFO,
            QualityRule.GEOMETRY_NEW_COLLAPSE to QualitySeverity.INFO,
            QualityRule.GEOMETRY_NEW_DEGENERATE to QualitySeverity.WARNING,
            QualityRule.GEOMETRY_SAMPLING_LIMIT to QualitySeverity.WARNING,
            QualityRule.GEOMETRY_NON_FINITE to QualitySeverity.ERROR,
            QualityRule.GEOMETRY_INVALID_TOPOLOGY to QualitySeverity.ERROR)
        assertEquals(QualityRule.entries.toSet(), levels.keys)
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
        val mixed = QualityFence.AUTHORING_COMMIT.inspect(QualityRule.entries.map(::finding), "Mixed evidence")
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
