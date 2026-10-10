package io.github.psd2live.application

import io.github.psd2live.core.GeneratedOverrideIssueKind
import io.github.psd2live.core.GeneratedOverrideOrphanReason
import io.github.psd2live.core.quality.GeneratedOverrideQuality
import io.github.psd2live.core.quality.GeneratedOverrideRule

/** Strict transport contract of the observation quality reports read through workspace_inspect. */
internal object WorkspaceQualitySchemas {
    private val s = WorkspaceResultSchema
    private val evidence = s.obj(mapOf(
        "kind" to s.choices(*GeneratedOverrideIssueKind.entries.map { it.wire }.toTypedArray()),
        "generator" to s.string(), "key" to s.dictionary(s.number()),
        "points" to s.integer(0), "total" to s.integer(0),
        "reason" to s.choices(*GeneratedOverrideOrphanReason.entries.map { it.wire }.toTypedArray()),
    ), setOf("kind", "generator", "key", "points", "total"))
    private val skipped = s.obj(mapOf(
        "kind" to s.constant(GeneratedOverrideQuality.SUPERSEDED_KIND), "index" to s.integer(0), "op" to s.string(),
        "targets" to s.array(s.handle()), "detail" to s.string(),
    ))
    private fun finding(codes: List<GeneratedOverrideRule>, evidence: kotlinx.serialization.json.JsonObject) = s.obj(mapOf(
        "code" to s.choices(*codes.map { it.name }.toTypedArray()),
        "severity" to s.choices("info", "warning", "error"), "category" to s.choices("quality", "validity", "coverage"),
        "domain" to s.constant(GeneratedOverrideQuality.DOMAIN), "target" to s.handle(), "evidence" to evidence,
    ))
    private val finding = s.union(listOf(
        finding(GeneratedOverrideRule.entries - GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED, evidence),
        finding(listOf(GeneratedOverrideRule.SUPERSEDED_ENTRY_SKIPPED), skipped),
    ))
    private val check = s.obj(mapOf("id" to s.handle(), "scope" to s.string(), "complete" to s.boolean()))

    /** [io.github.psd2live.core.quality.RegenerationQuality.report]. */
    val regeneration = s.obj(mapOf(
        "version" to s.integer(io.github.psd2live.core.quality.RegenerationQuality.VERSION, io.github.psd2live.core.quality.RegenerationQuality.VERSION),
        "domain" to s.constant(io.github.psd2live.core.quality.RegenerationQuality.DOMAIN), "fence" to s.constant("observation"),
        "decision" to s.choices("accept", "accept_with_diagnostics"), "can_proceed" to s.constant(true),
        "complete" to s.boolean(), "scope" to s.string(), "checks" to s.array(check, 1, 1),
        "findings" to s.array(s.obj(mapOf(
            "code" to s.choices(*io.github.psd2live.core.quality.RegenerationRule.entries.map { it.name }.toTypedArray()),
            "severity" to s.choices("info", "warning", "error"), "category" to s.choices("quality", "validity", "coverage"),
            "domain" to s.constant(io.github.psd2live.core.quality.RegenerationQuality.DOMAIN), "target" to s.string(),
            "evidence" to s.obj(mapOf(
                "kind" to s.choices(*io.github.psd2live.core.RigRegeneration.IssueKind.entries.map { it.code }.toTypedArray()),
                "detail" to s.string(),
            ), setOf("kind")),
        ))),
    ))

    /** [io.github.psd2live.core.quality.SkeletonBindingQuality.report]. */
    val skeleton = s.obj(mapOf(
        "version" to s.integer(io.github.psd2live.core.quality.SkeletonBindingQuality.VERSION, io.github.psd2live.core.quality.SkeletonBindingQuality.VERSION),
        "domain" to s.constant(io.github.psd2live.core.quality.SkeletonBindingQuality.DOMAIN), "fence" to s.constant("observation"),
        "decision" to s.choices("accept", "accept_with_diagnostics"), "can_proceed" to s.constant(true),
        "complete" to s.boolean(), "scope" to s.string(), "checks" to s.array(check, 1, 1),
        "findings" to s.array(s.obj(mapOf(
            "code" to s.choices(*io.github.psd2live.core.quality.SkeletonBindingRule.entries.map { it.name }.toTypedArray()),
            "severity" to s.choices("info", "warning", "error"), "category" to s.choices("quality", "validity", "coverage"),
            "domain" to s.constant(io.github.psd2live.core.quality.SkeletonBindingQuality.DOMAIN), "target" to s.handle(),
            "evidence" to s.obj(mapOf("bone" to s.handle()), emptySet()),
        ))),
    ))

    /** [GeneratedOverrideQuality.report]. */
    val generatedOverrides = s.obj(mapOf(
        "version" to s.integer(GeneratedOverrideQuality.VERSION, GeneratedOverrideQuality.VERSION),
        "domain" to s.constant(GeneratedOverrideQuality.DOMAIN), "fence" to s.constant("observation"),
        "decision" to s.choices("accept", "accept_with_diagnostics"), "can_proceed" to s.constant(true),
        "complete" to s.boolean(), "scope" to s.string(), "checks" to s.array(check, 2, 2), "findings" to s.array(finding),
    ))
}
