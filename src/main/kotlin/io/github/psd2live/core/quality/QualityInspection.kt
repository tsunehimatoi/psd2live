package io.github.psd2live.core.quality

import kotlinx.serialization.json.*

/** Severity describes evidence; the fence owns the action. Neither is chosen by a caller. */
enum class QualitySeverity(val wire: String) { INFO("info"), WARNING("warning"), ERROR("error") }
enum class QualityCategory(val wire: String) { QUALITY("quality"), VALIDITY("validity"), COVERAGE("coverage") }

enum class QualityDomain(val wire: String) { GENERAL("general"), GEOMETRY("geometry"), GENERATION("generation"), MODEL("model"), EXPORT("export"), SIMULATION("simulation"), PHYSICS("physics"), ASSET("asset") }

/** Single registry for stable diagnostic codes and their default classification. */
enum class QualityRule(val severity: QualitySeverity, val category: QualityCategory, val domain: QualityDomain = QualityDomain.GEOMETRY) {
    INSPECTION_NOT_RUN(QualitySeverity.WARNING, QualityCategory.COVERAGE, QualityDomain.GENERAL),
    GEOMETRY_NEW_FLIP(QualitySeverity.INFO, QualityCategory.QUALITY),
    GEOMETRY_NEW_COLLAPSE(QualitySeverity.INFO, QualityCategory.QUALITY),
    GEOMETRY_NEW_DEGENERATE(QualitySeverity.WARNING, QualityCategory.QUALITY),
    GEOMETRY_SAMPLING_LIMIT(QualitySeverity.WARNING, QualityCategory.COVERAGE),
    GEOMETRY_NON_FINITE(QualitySeverity.ERROR, QualityCategory.VALIDITY),
    GEOMETRY_INVALID_TOPOLOGY(QualitySeverity.ERROR, QualityCategory.VALIDITY),
    SOURCE_IMPORT_NOTICE(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_FEW_SEMANTIC_LAYERS(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_MISSING_FACE(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_MISSING_EYES(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_MISSING_MOUTH(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_DUPLICATE_NAMES(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    GENERATION_EMPTY_LAYER(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.GENERATION),
    MODEL_INVALID_VERTEX_ARRAY(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.MODEL),
    MODEL_NON_FINITE_GEOMETRY(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.MODEL),
    MODEL_COLLAPSED_POSE(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_BOUNDS_MISMATCH(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_BOUNDS_DEVIATION(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_MISSING_GEOMETRY(QualitySeverity.WARNING, QualityCategory.COVERAGE, QualityDomain.MODEL),
    MODEL_ENLARGED_POSE(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_INVALID_OPACITY(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.MODEL),
    MODEL_HIDDEN_POSE(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_MISSING_DIRECTIONAL_WARP(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_DIRECTIONAL_ASSUMPTION(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.MODEL),
    MODEL_MISSING_KEYFORM(QualitySeverity.WARNING, QualityCategory.COVERAGE, QualityDomain.MODEL),
    MODEL_INVALID_CONTROL_POINTS(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.MODEL),
    EXPORT_SOURCE_ART_REBUILT(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.EXPORT),
    EXPORT_INTENTIONAL_OMISSION(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.EXPORT),
    EXPORT_CONVERSION_NOTICE(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.EXPORT),
    EXPORT_IDENTITY_MISMATCH(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.EXPORT),
    SIMULATION_MEASUREMENTS(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.SIMULATION),
    SIMULATION_NON_FINITE(QualitySeverity.ERROR, QualityCategory.VALIDITY, QualityDomain.SIMULATION),
    SIMULATION_SETUP_NOTICE(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.SIMULATION),
    SIMULATION_BAKE_STALE(QualitySeverity.WARNING, QualityCategory.COVERAGE, QualityDomain.SIMULATION),
    SIMULATION_BAKE_ERROR(QualitySeverity.WARNING, QualityCategory.COVERAGE, QualityDomain.SIMULATION),
    PHYSICS_INACTIVE_GROUP(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.PHYSICS),
    PHYSICS_SHADOWED_GROUP(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.PHYSICS),
    ASSET_MATTE_MISMATCH(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.ASSET),
    ASSET_EDGE_UNCERTAINTY(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.ASSET),
    ASSET_PROCESSING_MEASUREMENTS(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.ASSET),
    ASSET_ORIENTATION_CONFLICT(QualitySeverity.WARNING, QualityCategory.QUALITY, QualityDomain.ASSET),
    ASSET_REGISTRATION_MEASUREMENTS(QualitySeverity.INFO, QualityCategory.QUALITY, QualityDomain.ASSET),
}

data class QualityFinding(val rule: QualityRule, val target: String, val evidence: JsonObject) {
    val message: String get() = evidence["detail"]?.jsonPrimitive?.contentOrNull ?: rule.name
    companion object {
        fun message(rule: QualityRule, target: String, text: String): QualityFinding =
            QualityFinding(rule, target, buildJsonObject { put("detail", text) })
    }
    fun toJson(): JsonObject = buildJsonObject {
        put("code", rule.name)
        put("severity", rule.severity.wire)
        put("category", rule.category.wire)
        put("domain", rule.domain.wire)
        put("target", target)
        put("evidence", evidence)
    }
}

enum class QualityDecision(val wire: String) { ACCEPT("accept"), ACCEPT_WITH_DIAGNOSTICS("accept_with_diagnostics"), REJECT("reject") }

/** Mutation/publication accepts quality and coverage diagnostics; observation preserves every finding. */
enum class QualityFence(val wire: String) {
    AUTHORING_COMMIT("authoring_commit"), EXPORT_PUBLICATION("export_publication"),
    BAKE_PUBLICATION("bake_publication"), OBSERVATION("observation");

    fun blocks(finding: QualityFinding): Boolean = this != OBSERVATION && finding.rule.severity == QualitySeverity.ERROR
    fun inspect(findings: List<QualityFinding>, scope: String): QualityReport = QualityReport(this, findings, scope)

    fun requireAccepted(report: QualityReport, diagnostics: () -> JsonObject) {
        require(report.fence == this) { "Report belongs to a different quality fence" }
        if (!report.canProceed) throw QualityFenceRejectedException(report, diagnostics())
    }
}

internal class QualityFenceRejectedException(val report: QualityReport, val diagnostics: JsonObject) :
    IllegalArgumentException("Quality fence rejected invalid data: " +
        report.blockers.map { it.rule.name }.distinct().joinToString(","))

data class QualityReport(val fence: QualityFence, val findings: List<QualityFinding>, val scope: String,
                         val checks: List<QualityCheckCoverage> = emptyList()) {
    val blockers: List<QualityFinding> get() = findings.filter(fence::blocks)
    val complete: Boolean get() = checks.all { it.complete } && findings.none { it.rule.category == QualityCategory.COVERAGE }
    val decision: QualityDecision get() = when {
        blockers.isNotEmpty() -> QualityDecision.REJECT
        findings.isNotEmpty() || !complete -> QualityDecision.ACCEPT_WITH_DIAGNOSTICS
        else -> QualityDecision.ACCEPT
    }
    val canProceed: Boolean get() = decision != QualityDecision.REJECT
    val canCommit: Boolean get() = canProceed

    fun toJson(): JsonObject = buildJsonObject {
        put("version", 2)
        put("fence", fence.wire)
        put("decision", decision.wire)
        put("can_proceed", canProceed)
        if (fence == QualityFence.AUTHORING_COMMIT) put("can_commit", canCommit)
        put("complete", complete)
        put("scope", scope)
        put("checks", JsonArray(checks.map { it.toJson() }))
        put("findings", JsonArray(findings.map { it.toJson() }))
    }
}

/** Checks produce evidence and explicit coverage; they never decide whether an action is allowed. */
fun interface QualityCheck<C> { fun inspect(context: C): QualityCheckResult }

data class QualityCheckCoverage(val id: String, val scope: String, val complete: Boolean) {
    fun toJson() = buildJsonObject { put("id", id); put("scope", scope); put("complete", complete) }
}

data class QualityCheckResult(val id: String, val scope: String, val findings: List<QualityFinding>,
                              val complete: Boolean = findings.none { it.rule.category == QualityCategory.COVERAGE })

object QualityInspection {
    fun <C> inspect(context: C, checks: List<QualityCheck<C>>, fence: QualityFence): QualityReport =
        combine(fence, checks.map { check ->
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Quality inspection cancelled")
            check.inspect(context)
        })

    fun combine(fence: QualityFence, checks: List<QualityCheckResult>): QualityReport {
        if (checks.isEmpty()) return combine(fence, listOf(QualityCheckResult(
            "inspection.unconfigured", "No quality checks supplied", emptyList(), complete = false)))
        require(checks.map { it.id }.distinct().size == checks.size) { "Duplicate quality check id" }
        val findings = checks.flatMap { check -> check.findings + if (!check.complete && check.findings.none { it.rule.category == QualityCategory.COVERAGE })
            listOf(QualityFinding.message(QualityRule.INSPECTION_NOT_RUN, check.id, "Check did not complete its declared scope")) else emptyList() }
        return QualityReport(fence, findings, checks.joinToString("; ") { it.scope },
            checks.map { QualityCheckCoverage(it.id, it.scope, it.complete) })
    }
}
