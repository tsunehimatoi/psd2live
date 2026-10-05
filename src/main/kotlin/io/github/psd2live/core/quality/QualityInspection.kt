package io.github.psd2live.core.quality

import kotlinx.serialization.json.*

/** Severity describes evidence; the fence owns the action. Neither is chosen by a caller. */
internal enum class QualitySeverity(val wire: String) { INFO("info"), WARNING("warning"), ERROR("error") }
internal enum class QualityCategory(val wire: String) { QUALITY("quality"), VALIDITY("validity"), COVERAGE("coverage") }

/** Single registry for stable diagnostic codes and their default classification. */
internal enum class QualityRule(val severity: QualitySeverity, val category: QualityCategory) {
    GEOMETRY_NEW_FLIP(QualitySeverity.INFO, QualityCategory.QUALITY),
    GEOMETRY_NEW_COLLAPSE(QualitySeverity.INFO, QualityCategory.QUALITY),
    GEOMETRY_NEW_DEGENERATE(QualitySeverity.WARNING, QualityCategory.QUALITY),
    GEOMETRY_SAMPLING_LIMIT(QualitySeverity.WARNING, QualityCategory.COVERAGE),
    GEOMETRY_NON_FINITE(QualitySeverity.ERROR, QualityCategory.VALIDITY),
    GEOMETRY_INVALID_TOPOLOGY(QualitySeverity.ERROR, QualityCategory.VALIDITY),
}

internal data class QualityFinding(val rule: QualityRule, val target: String, val evidence: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", rule.name)
        put("severity", rule.severity.wire)
        put("category", rule.category.wire)
        put("target", target)
        put("evidence", evidence)
    }
}

internal enum class QualityDecision(val wire: String) { ACCEPT("accept"), ACCEPT_WITH_DIAGNOSTICS("accept_with_diagnostics"), REJECT("reject") }

/** Authoring accepts quality/coverage diagnostics; invalid data never crosses the commit fence. */
internal enum class QualityFence(val wire: String) {
    AUTHORING_COMMIT("authoring_commit");

    fun blocks(finding: QualityFinding): Boolean = finding.rule.severity == QualitySeverity.ERROR
    fun inspect(findings: List<QualityFinding>, scope: String): QualityReport = QualityReport(this, findings, scope)

    fun requireAccepted(report: QualityReport, diagnostics: () -> JsonObject) {
        require(report.fence == this) { "Report belongs to a different quality fence" }
        if (!report.canCommit) throw QualityFenceRejectedException(report, diagnostics())
    }
}

internal class QualityFenceRejectedException(val report: QualityReport, val diagnostics: JsonObject) :
    IllegalArgumentException("Authoring quality fence rejected invalid candidate data: " +
        report.blockers.map { it.rule.name }.distinct().joinToString(","))

internal data class QualityReport(val fence: QualityFence, val findings: List<QualityFinding>, val scope: String) {
    val blockers: List<QualityFinding> get() = findings.filter(fence::blocks)
    val complete: Boolean get() = findings.none { it.rule.category == QualityCategory.COVERAGE }
    val decision: QualityDecision get() = when {
        blockers.isNotEmpty() -> QualityDecision.REJECT
        findings.isNotEmpty() -> QualityDecision.ACCEPT_WITH_DIAGNOSTICS
        else -> QualityDecision.ACCEPT
    }
    val canCommit: Boolean get() = decision != QualityDecision.REJECT

    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1)
        put("fence", fence.wire)
        put("decision", decision.wire)
        put("can_commit", canCommit)
        put("complete", complete)
        put("scope", scope)
        put("findings", JsonArray(findings.map { it.toJson() }))
    }
}
