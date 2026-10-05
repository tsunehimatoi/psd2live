package io.github.psd2live.application

import io.github.psd2live.core.quality.QualityRule
import kotlinx.serialization.json.*

internal object WorkspaceGeometryQualitySchemas {
    private val s = WorkspaceResultSchema
    private val coordinate = s.dictionary(s.number())
    private val violation = s.obj(mapOf("reason" to s.choices(*QualityRule.entries.filter { it.domain == io.github.psd2live.core.quality.QualityDomain.GEOMETRY }.map { it.name }.toTypedArray()),
        "target" to s.handle(), "coordinate" to coordinate, "triangleIds" to s.array(s.integer(0), 1, 32), "detail" to s.string()),
        setOf("reason", "target", "coordinate"))
    private val counts = listOf("newFlipCount", "newDegenerateCount", "newInvalidTopologyCount", "newNonFiniteCount",
        "preexistingFlipCount", "preexistingDegenerateCount", "preexistingCollapseCount", "newCollapseCount")
    val report = s.obj(mapOf("safe" to s.boolean(), "quality" to WorkspaceQualitySchemas.report, "affectedTargets" to s.array(s.handle()),
        "affectedCoordinates" to s.dictionary(s.array(coordinate)), "violations" to s.array(violation), "warnings" to s.array(violation), "information" to s.array(violation),
        "scope" to s.string(), "diagnostics" to s.array(s.obj(mapOf(
            "target" to s.handle(), "coordinate" to coordinate, "status" to s.choices("removed", "invalid_topology", "finite", "not_sampled"),
            "detail" to s.string(), "pointCount" to s.integer(0),
            "preexistingFlipCount" to s.integer(0), "preexistingDegenerateCount" to s.integer(0), "preexistingCollapseCount" to s.integer(0),
            "candidateFlipCount" to s.integer(0), "candidateDegenerateCount" to s.integer(0), "candidateCollapseCount" to s.integer(0)), setOf("target")))) +
        counts.associateWith { s.integer(0) })
    val preview = s.obj(s.identity + mapOf("revision" to s.handle(), "candidate_revision" to s.handle(),
        "dry_run" to s.constant(true), "would_change" to s.boolean(), "would_commit" to s.boolean(),
        "changed" to s.array(s.handle()), "diagnostics" to report))
}
