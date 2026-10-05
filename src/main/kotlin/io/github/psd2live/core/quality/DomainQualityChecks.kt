package io.github.psd2live.core.quality

import kotlinx.serialization.json.*

/** Common, finite evidence shapes allow all domains to use the same report/schema. */
object QualityEvidence {
    fun metrics(values: Map<String, Number>, attributes: Map<String, String> = emptyMap(), detail: String? = null): JsonObject = buildJsonObject {
        putJsonObject("metrics") { values.filterValues { it.toDouble().isFinite() }.forEach { (key, value) -> put(key, value) } }
        putJsonObject("attributes") {
            attributes.forEach { (key, value) -> put(key, value) }
            values.filterValues { !it.toDouble().isFinite() }.keys.takeIf { it.isNotEmpty() }?.let { put("non_finite_metrics", it.joinToString(",")) }
        }
        detail?.let { put("detail", it) }
    }
}

data class SimulationQualityInput(val id: String, val metrics: Map<String, Number>, val notes: List<String> = emptyList(),
                                  val stale: Boolean = false, val bake: Boolean = false)
object SimulationQualityCheck : QualityCheck<SimulationQualityInput> {
    fun failedBake(id: String, detail: String): QualityReport = QualityInspection.combine(QualityFence.OBSERVATION,
        listOf(QualityCheckResult("simulation.bake:$id", "Bake attempt failed; previous data retained",
            listOf(QualityFinding.message(QualityRule.SIMULATION_BAKE_ERROR, "simulation:$id", detail)), complete = false)))

    override fun inspect(context: SimulationQualityInput): QualityCheckResult {
        val findings = mutableListOf(QualityFinding(if (context.metrics.values.all { it.toDouble().isFinite() })
            QualityRule.SIMULATION_MEASUREMENTS else QualityRule.SIMULATION_NON_FINITE, "simulation:${context.id}",
            QualityEvidence.metrics(context.metrics, mapOf("measurement" to if (context.bake) "held_out_bake" else "sampled_response"))))
        context.notes.forEach { findings += QualityFinding.message(QualityRule.SIMULATION_SETUP_NOTICE, "simulation:${context.id}", it) }
        if (context.stale) findings += QualityFinding.message(QualityRule.SIMULATION_BAKE_STALE, "simulation:${context.id}", "Bake belongs to different model inputs")
        return QualityCheckResult("simulation:${context.id}", "Sampled simulation measurements and declared setup; no visual proof", findings)
    }
}

data class MatteQualityInput(val borderMatch: Double, val unresolved: Int, val enclosed: Int, val removed: Int, val unmixed: Int)
object MatteQualityCheck : QualityCheck<MatteQualityInput> {
    override fun inspect(context: MatteQualityInput): QualityCheckResult {
        val metrics = QualityEvidence.metrics(mapOf("border_match_fraction" to context.borderMatch, "unresolved_edge_pixels" to context.unresolved,
            "possible_enclosed_matte_pixels" to context.enclosed, "removed_pixels" to context.removed, "unmixed_edge_pixels" to context.unmixed))
        val findings = mutableListOf(QualityFinding(QualityRule.ASSET_PROCESSING_MEASUREMENTS, "asset:matte", metrics))
        if (context.borderMatch < 0.6) findings += QualityFinding(QualityRule.ASSET_MATTE_MISMATCH, "asset:matte", metrics)
        if (context.unresolved > 0 || context.enclosed > 0) findings += QualityFinding(QualityRule.ASSET_EDGE_UNCERTAINTY, "asset:matte", metrics)
        return QualityCheckResult("asset.matte", "Declared flat matte and edge measurements; no foreground/coverage proof", findings)
    }
}

data class RegistrationQualityInput(val id: String, val residual: Double, val orientationConflict: Boolean, val mode: String)
object RegistrationQualityCheck : QualityCheck<RegistrationQualityInput> {
    override fun inspect(context: RegistrationQualityInput): QualityCheckResult {
        val evidence = QualityEvidence.metrics(mapOf("anchor_rms_canvas_units" to context.residual), mapOf("mode" to context.mode))
        val findings = listOf(QualityFinding(QualityRule.ASSET_REGISTRATION_MEASUREMENTS, "asset:${context.id}", evidence)) +
            if (context.orientationConflict) listOf(QualityFinding(QualityRule.ASSET_ORIENTATION_CONFLICT, "asset:${context.id}", evidence)) else emptyList()
        return QualityCheckResult("asset.registration:${context.id}", "Declared registration anchors and handedness; no shape-match proof", findings)
    }
}
