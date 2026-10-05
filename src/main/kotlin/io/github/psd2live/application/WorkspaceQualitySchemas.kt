package io.github.psd2live.application

import io.github.psd2live.core.quality.*
import kotlinx.serialization.json.*

/** All quality reports and rule classifications use the same strict transport contract. */
internal object WorkspaceQualitySchemas {
    private val s = WorkspaceResultSchema
    val evidence = s.obj(mapOf("coordinate" to s.dictionary(s.number()),
        "triangleIds" to s.array(s.integer(0), 1, 32), "detail" to s.string()), emptySet())
    val finding = s.union(QualityRule.entries.map { rule -> s.obj(mapOf(
        "code" to s.constant(rule.name), "severity" to s.constant(rule.severity.wire),
        "category" to s.constant(rule.category.wire), "target" to s.handle(), "evidence" to evidence)) })
    val report = s.union(QualityDecision.entries.map { decision -> s.obj(mapOf(
        "version" to s.integer(1, 1), "fence" to s.choices(*QualityFence.entries.map { it.wire }.toTypedArray()),
        "decision" to s.constant(decision.wire), "can_commit" to s.constant(decision != QualityDecision.REJECT),
        "complete" to if (decision == QualityDecision.ACCEPT) s.constant(true) else s.boolean(), "scope" to s.string(),
        "findings" to if (decision == QualityDecision.ACCEPT) s.array(finding, 0, 0) else s.array(finding, 1, Int.MAX_VALUE))) })
}
