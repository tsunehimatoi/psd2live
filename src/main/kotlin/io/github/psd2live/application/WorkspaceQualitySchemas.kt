package io.github.psd2live.application

import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*

/** The shared envelope is domain-independent; small discriminator branches enforce classifications. */
internal object WorkspaceQualitySchemas {
    private val s = WorkspaceResultSchema
    val evidence = s.obj(mapOf("coordinate" to s.dictionary(s.number()),
        "triangleIds" to s.array(s.integer(0), 1, 32), "detail" to s.string(),
        "metrics" to s.dictionary(s.number()), "attributes" to s.dictionary(s.string())), emptySet())
    val finding = JsonObject(s.obj(mapOf("code" to s.handle(), "severity" to s.choices(*QualitySeverity.entries.map { it.wire }.toTypedArray()),
        "category" to s.choices(*QualityCategory.entries.map { it.wire }.toTypedArray()),
        "domain" to s.choices(*QualityDomain.entries.map { it.wire }.toTypedArray()), "target" to s.handle(), "evidence" to evidence)) +
        ("oneOf" to JsonArray(QualityRule.entries.map { rule -> buildJsonObject {
            put("properties", JsonObject(mapOf("code" to s.constant(rule.name), "severity" to s.constant(rule.severity.wire),
                "category" to s.constant(rule.category.wire), "domain" to s.constant(rule.domain.wire))))
        } })))
    private val coverage = s.obj(mapOf("id" to s.handle(), "scope" to s.string(), "complete" to s.boolean()))
    private val fields = mapOf("version" to s.integer(2, 2), "fence" to s.handle(), "decision" to s.handle(),
        "can_proceed" to s.boolean(), "can_commit" to s.boolean(), "complete" to s.boolean(), "scope" to s.string(),
        "checks" to s.array(coverage), "findings" to s.array(finding))
    val report = JsonObject(s.obj(fields, fields.keys - "can_commit") + ("oneOf" to JsonArray(QualityFence.entries.flatMap { fence ->
        QualityDecision.entries.filter { fence != QualityFence.OBSERVATION || it != QualityDecision.REJECT }.map { decision ->
            val names = if (fence == QualityFence.AUTHORING_COMMIT) fields.keys else fields.keys - "can_commit"
            s.obj(names.associateWith { JsonObject(emptyMap()) } + mapOf("fence" to s.constant(fence.wire),
                "decision" to s.constant(decision.wire), "can_proceed" to s.constant(decision != QualityDecision.REJECT),
                "findings" to buildJsonObject { put("type", "array"); if (decision == QualityDecision.ACCEPT) put("maxItems", 0) else put("minItems", 1) }) +
                if (fence == QualityFence.AUTHORING_COMMIT) mapOf("can_commit" to s.constant(decision != QualityDecision.REJECT)) else emptyMap(), names)
        }
    })))
}
