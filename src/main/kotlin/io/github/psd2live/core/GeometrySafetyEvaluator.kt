package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.meshBlendState
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Stable machine-readable reasons returned by the Agent geometry commit gate. */
internal enum class GeometrySafetyReason {
    GEOMETRY_NON_FINITE,
    GEOMETRY_INVALID_TOPOLOGY,
    GEOMETRY_NEW_FLIP,
    GEOMETRY_NEW_DEGENERATE,
    GEOMETRY_NEW_COLLAPSE,
    GEOMETRY_SAMPLING_LIMIT,
}

internal data class GeometrySafetyViolation(
    val reason: GeometrySafetyReason,
    val target: String,
    val coordinate: Map<String, Float>,
    val triangleIds: List<Int> = emptyList(),
    val detail: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("reason", reason.name)
        put("target", target)
        putJsonObject("coordinate") { coordinate.toSortedMap().forEach { (id, value) -> put(id, value) } }
        if (triangleIds.isNotEmpty()) put("triangleIds", JsonArray(triangleIds.take(32).map(::JsonPrimitive)))
        detail?.let { put("detail", it) }
    }
}

/**
 * Structural geometry evidence for one completely compiled candidate model.
 *
 * The evaluator is deliberately parent-local. It checks finite, well-formed native geometry and
 * newly inverted/degenerate/collapsed triangles at affected native key coordinates. Large Cartesian
 * products use bounded distributed sampling rather than exhaustive coverage. It is
 * not a visual, mask, painted-coverage, physics, or aesthetic oracle.
 */
internal data class GeometrySafetyReport(
    val safe: Boolean,
    val affectedTargets: List<String>,
    val affectedCoordinates: Map<String, List<Map<String, Float>>>,
    val newFlipCount: Int,
    val newDegenerateCount: Int,
    val newInvalidTopologyCount: Int,
    val newNonFiniteCount: Int,
    val preexistingFlipCount: Int,
    val preexistingDegenerateCount: Int,
    val preexistingCollapseCount: Int,
    val newCollapseCount: Int,
    val violations: List<GeometrySafetyViolation>,
    val diagnostics: List<JsonObject>,
    val warnings: List<GeometrySafetyViolation> = emptyList(),
    val coverage: GeometrySafetyCoverage = GeometrySafetyCoverage(GeometrySafetyCoverage.FULL, 0, 0),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("safe", safe)
        put("affectedTargets", JsonArray(affectedTargets.map(::JsonPrimitive)))
        putJsonObject("affectedCoordinates") {
            affectedCoordinates.forEach { (target, coordinates) ->
                putJsonArray(target) {
                    coordinates.forEach { coordinate ->
                        add(buildJsonObject { coordinate.toSortedMap().forEach { (id, value) -> put(id, value) } })
                    }
                }
            }
        }
        put("newFlipCount", newFlipCount)
        put("newDegenerateCount", newDegenerateCount)
        put("newInvalidTopologyCount", newInvalidTopologyCount)
        put("newNonFiniteCount", newNonFiniteCount)
        put("preexistingFlipCount", preexistingFlipCount)
        put("preexistingDegenerateCount", preexistingDegenerateCount)
        put("preexistingCollapseCount", preexistingCollapseCount)
        put("newCollapseCount", newCollapseCount)
        put("violations", JsonArray(violations.map { it.toJson() }))
        put("warnings", JsonArray(warnings.map { it.toJson() }))
        put("diagnostics", JsonArray(diagnostics))
        put("scope", "Affected parent-local native key coordinates only; large Cartesian products are sampled. No parent composition, masks, painted coverage, physics, or aesthetics." +
            if (coverage.mode == GeometrySafetyCoverage.SCOPED) " Targets compared: only those the committed commands address, their deformer descendants, glue partners and generator-owned targets; the other targets were not compared." else "")
        put("coverage", coverage.toJson())
    }

    companion object {
        fun noGeometryChange(): GeometrySafetyReport = GeometrySafetyReport(
            safe = true,
            affectedTargets = emptyList(),
            affectedCoordinates = emptyMap(),
            newFlipCount = 0,
            newDegenerateCount = 0,
            newInvalidTopologyCount = 0,
            newNonFiniteCount = 0,
            preexistingFlipCount = 0,
            preexistingDegenerateCount = 0,
            preexistingCollapseCount = 0,
            newCollapseCount = 0,
            violations = emptyList(),
            diagnostics = emptyList(),
        )
    }
}

/**
 * Which targets a report compared: [FULL] every mesh, Warp and Rotation of both models, [SCOPED] only those
 * derived from the committed commands. [checkedTargets] counts the compared targets, [totalTargets] those the
 * two models hold.
 */
internal data class GeometrySafetyCoverage(val mode: String, val checkedTargets: Int, val totalTargets: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode); put("checkedTargets", checkedTargets); put("totalTargets", totalTargets)
    }

    companion object {
        const val FULL = "full"
        const val SCOPED = "scoped"
    }
}

internal class GeometrySafetyRejectedException(val safetyReport: GeometrySafetyReport) :
    IllegalArgumentException("Geometry safety gate rejected the candidate: " +
        safetyReport.violations.map { it.reason.name }.distinct().joinToString(","))

internal object GeometrySafetyEvaluator {
    private class Target(
        val ref: String,
        val kind: String,
        val id: String,
        val parent: String?,
        /** The Drawable or Deformer whose geometry this target compares; see [sameGeometry]. */
        val source: Any,
        val coordinateSource: () -> List<Map<String, Float>>,
        val triangleSource: () -> IntArray,
        val referenceSource: () -> FloatArray,
        val pointCount: Int,
        val rawValidation: () -> String?,
    ) {
        val coordinates by lazy(coordinateSource)
        val triangles by lazy(triangleSource)
        val reference by lazy(referenceSource)
    }

    /**
     * Compares [before] with [candidate]. A null [scope] compares every mesh, Warp and Rotation. A [scope] of
     * target refs (`mesh:`, `warp:`, `rotation:`) compares only those, every deformer descendant of them and the
     * glue partners of the meshes among them; the caller derives it from the commands that produced
     * [candidate] and must fall back to null whenever it cannot name every target they may move. Within the
     * compared set the classification is the same as a full evaluation, so a scope covering every changed
     * target yields the full result.
     */
    fun evaluate(before: PuppetModel, candidate: PuppetModel, blockFoldovers: Boolean = true, scope: Set<String>? = null): GeometrySafetyReport {
        val included = scope?.let { closure(before, candidate, it) }
        val beforeTargets = targets(before, included)
        val afterTargets = targets(candidate, included)
        val coverage = GeometrySafetyCoverage(if (included == null) GeometrySafetyCoverage.FULL else GeometrySafetyCoverage.SCOPED,
            (beforeTargets.keys + afterTargets.keys).size,
            if (included == null) (beforeTargets.keys + afterTargets.keys).size else totalTargets(before, candidate))
        val beforeParameters = before.parameters.mapTo(HashSet()) { it.id.raw }
        val direct = (beforeTargets.keys + afterTargets.keys).filterTo(mutableSetOf()) { ref ->
            beforeTargets[ref]?.let { old -> afterTargets[ref]?.let { next -> old.parent != next.parent || !sameGeometry(old, next) } ?: true } ?: true
        }
        if (direct.isEmpty()) return GeometrySafetyReport.noGeometryChange().copy(coverage = coverage)

        // A changed Warp changes the inherited path of descendants even where their local forms are
        // unchanged. Include those descendants in the evidence scope instead of silently skipping them.
        val affected = direct.toMutableSet()
        val deformerRefById = afterTargets.values.filter { it.kind == "warp" || it.kind == "rotation" }.associate { it.id to it.ref }
        var expanded: Boolean
        do {
            expanded = false
            afterTargets.values.forEach { target ->
                val parentRef = target.parent?.let(deformerRefById::get)
                if (parentRef in affected && affected.add(target.ref)) expanded = true
            }
        } while (expanded)

        var newFlips = 0
        var newDegenerates = 0
        var invalid = 0
        var nonFinite = 0
        var oldFlips = 0
        var oldDegenerates = 0
        var oldCollapses = 0
        var newCollapses = 0
        val violations = mutableListOf<GeometrySafetyViolation>()
        val diagnostics = mutableListOf<JsonObject>()
        val coordinateEvidence = linkedMapOf<String, List<Map<String, Float>>>()
        val beforeSampler = Sampler(before)
        val candidateSampler = Sampler(candidate)

        for (ref in affected.sorted()) {
            checkpoint()
            val old = beforeTargets[ref]
            val next = afterTargets[ref]
            if (next == null) {
                coordinateEvidence[ref] = emptyList()
                diagnostics += buildJsonObject { put("target", ref); put("status", "removed") }
                continue
            }
            next.rawValidation()?.let { detail ->
                invalid++
                violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, emptyMap(), detail = detail)
                diagnostics += buildJsonObject { put("target", ref); put("status", "invalid_topology"); put("detail", detail) }
                continue
            }
            coordinateEvidence[ref] = next.coordinates
            val comparable = old?.takeIf { it.pointCount == next.pointCount && it.triangles.contentEquals(next.triangles) }
            // A new parent gives the points another space (canvas pixels, a Warp's 0..1 square, a Rotation's
            // frame), so the old points only tell which triangles were already bad, each in its own space; the
            // candidate is measured against its own rest form.
            val reparented = comparable != null && old.parent != next.parent
            // The reference and triangles are the same at every coordinate: their validity and areas once per target.
            val inspections = HashMap<FloatArray, Inspection>(2)
            for (coordinate in next.coordinates) {
                checkpoint()
                val candidatePoints = candidateSampler.points(next, coordinate)
                if (candidatePoints == null) {
                    invalid++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Geometry cannot be evaluated at native coordinate")
                    continue
                }
                if (!candidatePoints.all(Float::isFinite)) {
                    nonFinite++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_NON_FINITE, ref, coordinate)
                    continue
                }
                if (next.triangles.isEmpty()) {
                    diagnostics += coordinateDiagnostic(ref, coordinate, "finite", candidatePoints.size / 2)
                    continue
                }

                val oldComparable = comparable?.let { beforeSampler.points(it, coordinate.filterKeys { id -> id in beforeParameters }) }
                val reference = if (oldComparable != null && !reparented) old.reference else next.reference
                val inspection = inspections.getOrPut(reference) { Inspection(reference, next.triangles) }
                val candidateStatus = inspection.inspect(candidatePoints)
                val oldInspection = if (reparented) inspections.getOrPut(old.reference) { Inspection(old.reference, old.triangles) } else inspection
                if (!inspection.valid || !oldInspection.valid || candidateStatus == null) {
                    invalid++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Geometry scalar count or triangle indices are malformed")
                    continue
                }
                val oldStatus = oldComparable?.takeIf { it.all(Float::isFinite) }?.let {
                    if (oldInspection === inspection && it.contentEquals(candidatePoints)) candidateStatus else oldInspection.inspect(it)
                }
                if (oldComparable != null && oldStatus == null) {
                    invalid++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Baseline geometry cannot be compared")
                    continue
                }

                oldFlips += oldStatus?.flippedTriangles?.size ?: 0
                oldDegenerates += oldStatus?.let { (it.degenerateTriangles + it.degenerateReferenceTriangles).size } ?: 0
                oldCollapses += oldStatus?.collapsedTriangles?.size ?: 0

                // A whole-surface invertible affine mirror or compression is a valid authoring edit.
                // Local foldovers, zero-area geometry and non-affine collapses still fail the gate.
                val affine = oldComparable != null && !reparented && invertibleAffine(oldComparable, candidatePoints, next.triangles)
                val newlyFlipped = candidateStatus.flippedTriangles.filter { !affine && it !in oldStatus?.flippedTriangles.orEmpty() }
                val oldDegenerateTriangles = oldStatus?.let { it.degenerateTriangles + it.degenerateReferenceTriangles }.orEmpty()
                val candidateDegenerateTriangles = candidateStatus.degenerateTriangles + candidateStatus.degenerateReferenceTriangles
                val newlyDegenerate = candidateDegenerateTriangles.filter { it !in oldDegenerateTriangles }
                val newlyCollapsed = candidateStatus.collapsedTriangles.filter {
                    !affine && it !in oldStatus?.collapsedTriangles.orEmpty() && it !in newlyFlipped && it !in newlyDegenerate
                }
                if (newlyFlipped.isNotEmpty()) {
                    newFlips += newlyFlipped.size
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_NEW_FLIP, ref, coordinate, newlyFlipped)
                }
                if (newlyDegenerate.isNotEmpty()) {
                    newDegenerates += newlyDegenerate.size
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_NEW_DEGENERATE, ref, coordinate, newlyDegenerate)
                }
                if (newlyCollapsed.isNotEmpty()) {
                    newCollapses += newlyCollapsed.size
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_NEW_COLLAPSE, ref, coordinate, newlyCollapsed)
                }
                diagnostics += buildJsonObject {
                    put("target", ref)
                    putJsonObject("coordinate") { coordinate.toSortedMap().forEach { (id, value) -> put(id, value) } }
                    put("pointCount", candidatePoints.size / 2)
                    put("preexistingFlipCount", oldStatus?.flippedTriangles?.size ?: 0)
                    put("preexistingDegenerateCount", oldDegenerateTriangles.size)
                    put("preexistingCollapseCount", oldStatus?.collapsedTriangles?.size ?: 0)
                    put("candidateFlipCount", candidateStatus.flippedTriangles.size)
                    put("candidateDegenerateCount", candidateDegenerateTriangles.size)
                    put("candidateCollapseCount", candidateStatus.collapsedTriangles.size)
                }
            }
        }
        val warnings = if (blockFoldovers) emptyList() else violations.filter {
            it.reason in setOf(GeometrySafetyReason.GEOMETRY_NEW_FLIP, GeometrySafetyReason.GEOMETRY_NEW_COLLAPSE)
        }
        val blockers = violations - warnings.toSet()
        return GeometrySafetyReport(blockers.isEmpty(), affected.sorted(), coordinateEvidence, newFlips, newDegenerates,
            invalid, nonFinite, oldFlips, oldDegenerates, oldCollapses, newCollapses, blockers, diagnostics, warnings, coverage)
    }

    /** [scope], then every deformer descendant of it in either model, then the glue partners of its meshes. */
    private fun closure(before: PuppetModel, candidate: PuppetModel, scope: Set<String>): Set<String> {
        val included = scope.toMutableSet()
        val children = HashMap<String, MutableList<String>>()
        for (model in listOf(before, candidate)) {
            model.drawables.forEach { drawable ->
                drawable.parentDeformerId?.let { children.getOrPut(it.raw) { ArrayList() } += "mesh:${drawable.id.raw}" }
            }
            model.deformers.forEach { deformer ->
                deformer.parent?.let { children.getOrPut(it.raw) { ArrayList() } += refOf(deformer) }
            }
        }
        val queue = ArrayDeque(included)
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            if (!ref.startsWith("warp:") && !ref.startsWith("rotation:")) continue
            children[ref.substringAfter(':')]?.forEach { if (included.add(it)) queue += it }
        }
        for (model in listOf(before, candidate)) model.glues.forEach { glue ->
            val a = "mesh:${glue.meshA.raw}"; val b = "mesh:${glue.meshB.raw}"
            if (a in scope || b in scope || a in included || b in included) { included += a; included += b }
        }
        return included
    }

    private fun refOf(deformer: Deformer) = when (deformer) {
        is Deformer.Warp -> "warp:${deformer.id.raw}"
        is Deformer.Rotation -> "rotation:${deformer.id.raw}"
    }

    private fun totalTargets(before: PuppetModel, candidate: PuppetModel): Int = HashSet<String>().apply {
        for (model in listOf(before, candidate)) {
            model.drawables.forEach { if (it.mesh != null) add("mesh:${it.id.raw}") }
            model.deformers.forEach { add(refOf(it)) }
        }
    }.size

    private fun coordinateDiagnostic(ref: String, coordinate: Map<String, Float>, status: String, points: Int) = buildJsonObject {
        put("target", ref)
        putJsonObject("coordinate") { coordinate.toSortedMap().forEach { (id, value) -> put(id, value) } }
        put("status", status)
        put("pointCount", points)
    }

    /** The targets of [model], or only those in [included]; nothing here copies or boxes geometry. */
    private fun targets(model: PuppetModel, included: Set<String>?): Map<String, Target> = buildMap {
        model.drawables.forEach { drawable ->
            val mesh = drawable.mesh ?: return@forEach
            val ref = "mesh:${drawable.id.raw}"
            if (included != null && ref !in included) return@forEach
            put(ref, Target(ref, "mesh", drawable.id.raw, drawable.parentDeformerId?.raw, drawable,
                { coordinates(drawable.geometryGrid, drawable.blendShapes) }, { mesh.indices }, { mesh.positions },
                mesh.positions.size / 2) { validateMesh(drawable) })
        }
        model.deformers.forEach { deformer ->
            val ref = refOf(deformer)
            if (included != null && ref !in included) return@forEach
            when (deformer) {
                is Deformer.Warp -> {
                    put(ref, Target(ref, "warp", deformer.id.raw, deformer.parent?.raw, deformer,
                        { coordinates(deformer.geometryGrid, deformer.blendShapes) },
                        { RigGeometryDiagnostics.lattice(deformer.rows, deformer.columns) }, { warpDomain(deformer.rows, deformer.columns) },
                        if (deformer.rows < 1 || deformer.columns < 1) 0 else (deformer.rows + 1) * (deformer.columns + 1)) { validateWarp(deformer) })
                }
                is Deformer.Rotation -> {
                    put(ref, Target(ref, "rotation", deformer.id.raw, deformer.parent?.raw, deformer,
                        { coordinates(deformer.geometryGrid, deformer.blendShapes) }, { IntArray(0) }, { FloatArray(4) }, 2) { validateRotation(deformer) })
                }
            }
        }
    }

    /**
     * [RigGeometryTools.geometry]`(model, kind, id, pose).points`, or null where that throws. Meshes, which
     * dominate the sampling, skip its selector domain and rebuild nothing per coordinate: the same pose check,
     * grid interpolation and blend-shape sum, in the same float operation order, so the points are identical.
     */
    private class Sampler(private val model: PuppetModel) {
        private val parameters by lazy { model.parameters.associateBy { it.id.raw } }
        private val defaults by lazy { model.parameters.associate { it.id to it.default } }
        private val meshes by lazy { model.drawables.groupBy { it.id.raw }.mapNotNull { (id, list) -> list.singleOrNull()?.let { id to it } }.toMap() }

        fun points(target: Target, pose: Map<String, Float>): FloatArray? =
            if (target.kind == "mesh") runCatching { mesh(target.id, pose) }.getOrNull()
            else runCatching { RigGeometryTools.geometry(model, target.kind, target.id, pose).points }.getOrNull()

        private fun mesh(id: String, pose: Map<String, Float>): FloatArray {
            require(pose.entries.none { (parameter, value) -> parameters[parameter]?.let { value.isFinite() && value in it.min..it.max } != true })
            val drawable = meshes.getValue(id)
            val base = requireNotNull(drawable.mesh).positions
            // RigGeometryTools.bounds, which the selector domain needs, rejects these.
            require(base.size >= 2 && base.size % 2 == 0 && base.all(Float::isFinite))
            val points = base.copyOf()
            drawable.geometryGrid?.let { grid ->
                val cells = cellsByLinearIndex(grid)
                val corners = requireNotNull(gridCorners(grid) { parameters[it.raw]?.let { p -> pose[it.raw] ?: p.default } ?: 0f })
                for (corner in corners) {
                    val form = requireNotNull(cells[corner.linearIndex]).form.positionDeltas
                    require(form.size == points.size)
                    for (i in points.indices) points[i] += corner.weight * form[i]
                }
            }
            val blend = meshBlendState(drawable, { pose[it.raw] ?: defaults[it] ?: 0f }, { defaults[it] ?: 0f })
            if (blend != null) {
                for (contribution in blend.contributions) {
                    val deltas = contribution.form.positionDeltas
                    for (i in points.indices) {
                        val reference = blend.referenceDeltas?.getOrElse(i) { 0f } ?: 0f
                        val component = deltas.getOrElse(i) { 0f }
                        points[i] += contribution.weight * (component - reference)
                    }
                }
            }
            return points
        }
    }

    /**
     * [RigGeometryDiagnostics.inspect] against one fixed [reference] and [triangles]: their checks and areas
     * are computed once, then each [inspect] gives the same status the diagnostics would, or null where they throw.
     */
    private class Inspection(private val reference: FloatArray, private val triangles: IntArray) {
        val valid = reference.size % 2 == 0 && reference.all(Float::isFinite) &&
            triangles.size % 3 == 0 && triangles.all { it in 0 until reference.size / 2 }
        private val areas = if (!valid) DoubleArray(0) else DoubleArray(triangles.size / 3) { area(reference, it * 3) }

        private fun area(p: FloatArray, i: Int): Double {
            val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
            return (p[b*2].toDouble()-p[a*2])*(p[c*2+1]-p[a*2+1]) - (p[b*2+1].toDouble()-p[a*2+1])*(p[c*2]-p[a*2])
        }

        fun inspect(points: FloatArray): RigGeometryDiagnostics.TriangleStatus? {
            if (!valid || points.size != reference.size || !points.all(Float::isFinite)) return null
            val flipped = mutableSetOf<Int>()
            val collapsed = mutableSetOf<Int>()
            val degenerate = mutableSetOf<Int>()
            val degenerateReference = mutableSetOf<Int>()
            var minimum: Double? = null
            var maximum: Double? = null
            for (triangle in areas.indices) {
                val a = areas[triangle]
                if (abs(a) < RigGeometryDiagnostics.DEGENERATE_AREA_EPSILON) {
                    degenerateReference += triangle
                    continue
                }
                val b = area(points, triangle * 3)
                val ratio = b / a
                minimum = minimum?.let { minOf(it, ratio) } ?: ratio
                maximum = maximum?.let { maxOf(it, ratio) } ?: ratio
                if (ratio < 0) flipped += triangle
                if (abs(b) < RigGeometryDiagnostics.DEGENERATE_AREA_EPSILON || abs(ratio) <= RigGeometryDiagnostics.DEGENERATE_AREA_RATIO) degenerate += triangle
                else if (abs(ratio) < RigGeometryDiagnostics.COLLAPSE_AREA_RATIO) collapsed += triangle
            }
            return RigGeometryDiagnostics.TriangleStatus(flipped, collapsed, degenerate, degenerateReference, minimum, maximum)
        }
    }

    private fun warpDomain(rows: Int, columns: Int): FloatArray {
        if (rows < 1 || columns < 1) return FloatArray(0)
        return FloatArray((rows + 1) * (columns + 1) * 2).also { domain ->
            for (row in 0..rows) for (column in 0..columns) {
                val index = (row * (columns + 1) + column) * 2
                domain[index] = column.toFloat() / columns
                domain[index + 1] = row.toFloat() / rows
            }
        }
    }

    private fun coordinateKey(coordinate: Map<String, Float>): String = coordinate.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value.toRawBits()}" }

    private fun <T, B : Any> coordinates(grid: KeyformGrid<T>?, blends: List<BlendShapeBinding<B>>): List<Map<String, Float>> {
        val base = if (grid == null || grid.axes.isEmpty()) listOf(emptyMap()) else grid.cells.mapNotNull { cell ->
            if (cell.coordinate.size != grid.axes.size) null else buildMap<String, Float> {
                for (i in grid.axes.indices) {
                    val keyIndex = cell.coordinate[i]
                    val axis = grid.axes[i]
                    if (keyIndex !in axis.keys.indices) return@mapNotNull null
                    put(axis.parameterId.raw, axis.keys[keyIndex])
                }
            }
        }.distinctBy(::coordinateKey).sortedBy(::coordinateKey)
        val axes = linkedMapOf<String, MutableSet<Float>>()
        blends.forEach { blend ->
            axes.getOrPut(blend.parameterId.raw) { linkedSetOf() }.addAll(blend.keys.toList())
            blend.limits.forEach { limit -> axes.getOrPut(limit.parameterId.raw) { linkedSetOf() }.addAll(limit.points.map { it.value }) }
        }
        var result = base
        axes.forEach { (id, values) ->
            require(values.all { it.isFinite() }) { "Blend coordinates must be finite" }
            // Disabled: a diagnostic sampling budget must not reject valid authored rigs.
            // if (result.size.toLong() * values.size > 16384) throw GeometrySafetyRejectedException(
            //     GeometrySafetyReport.noGeometryChange().copy(safe = false, violations = listOf(
            //         GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_SAMPLING_LIMIT, "rig", emptyMap(), detail = "More than 16384 geometry coordinates on one target"))))
            val keys = values.sorted()
            val combinations = result.size.toLong() * keys.size
            result = if (combinations <= 16384) result.flatMap { coordinate -> keys.map { coordinate + (id to it) } }
            else {
                // Deterministic distributed diagnostics instead of materializing an exponential
                // Cartesian product. Both extreme coordinates remain in the sample. This is a
                // bounded diagnostic sample, not a certificate covering every combination.
                fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
                val interior = combinations - 2
                var stride = (interior * .6180339887498949).toLong().coerceAtLeast(1)
                while (gcd(stride, interior) != 1L) stride++
                List(16384) { index ->
                    val flat = when (index) { 0 -> 0L; 16383 -> combinations - 1; else -> 1 + ((index - 1).toLong() * stride) % interior }
                    result[(flat / keys.size).toInt()] + (id to keys[(flat % keys.size).toInt()])
                }
            }.distinctBy(::coordinateKey)
        }
        return result
    }

    private fun validateMesh(drawable: Drawable): String? {
        val mesh = drawable.mesh ?: return "Drawable has no mesh"
        if (mesh.positions.size % 2 != 0) return "Position scalar count must be even"
        if (mesh.uvs.size != mesh.positions.size) return "UV scalar count must equal position scalar count"
        if (mesh.indices.size % 3 != 0) return "Triangle index count must be divisible by three"
        if (mesh.indices.any { it !in 0 until mesh.positions.size / 2 }) return "Triangle index is outside the vertex range"
        return validateGrid(drawable.geometryGrid, mesh.positions.size) { it.positionDeltas }
            ?: validateBlends(drawable.blendShapes, mesh.positions.size) { it.positionDeltas }
    }

    private fun validateWarp(warp: Deformer.Warp): String? {
        if (warp.rows < 1 || warp.columns < 1) return "Warp lattice dimensions must be positive"
        val expected = (warp.rows + 1) * (warp.columns + 1) * 2
        return validateGrid(warp.geometryGrid, expected) { it.controlPoints }
            ?: validateBlends(warp.blendShapes, expected) { it.controlPoints }
    }

    private fun validateRotation(rotation: Deformer.Rotation): String? = validateGrid(rotation.geometryGrid, null) {
        floatArrayOf(it.originX, it.originY, it.angle, it.scale)
    } ?: validateBlends(rotation.blendShapes, 4) { floatArrayOf(it.originX, it.originY, it.angle, it.scale) }

    private fun <T : Any> validateBlends(blends: List<BlendShapeBinding<T>>, count: Int, values: (T) -> FloatArray): String? {
        for (blend in blends) {
            if (blend.keys.isEmpty() || blend.keys.size != blend.forms.size || blend.neutralIndex !in blend.keys.indices ||
                blend.keys.any { !it.isFinite() } || blend.keys.toList().zipWithNext().any { (a, b) -> a >= b }) return "Malformed blend keys"
            if (blend.forms.filterNotNull().any { values(it).size != count }) return "Malformed blend geometry"
        }
        return null
    }

    private fun checkpoint() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Geometry evaluation cancelled") }

    private fun invertibleAffine(before: FloatArray, after: FloatArray, triangles: IntArray): Boolean {
        val triangle = triangles.asSequence().chunked(3).firstOrNull { ids ->
            kotlin.math.abs((before[ids[1]*2]-before[ids[0]*2]).toDouble() * (before[ids[2]*2+1]-before[ids[0]*2+1]) -
                (before[ids[2]*2]-before[ids[0]*2]).toDouble() * (before[ids[1]*2+1]-before[ids[0]*2+1])) > 1e-12
        } ?: return false
        val a = triangle[0]*2; val b = triangle[1]*2; val c = triangle[2]*2
        val ux = (before[b]-before[a]).toDouble(); val uy = (before[b+1]-before[a+1]).toDouble()
        val vx = (before[c]-before[a]).toDouble(); val vy = (before[c+1]-before[a+1]).toDouble()
        val determinant = ux*vy-uy*vx
        val pu = (after[b]-after[a]).toDouble(); val qu = (after[b+1]-after[a+1]).toDouble()
        val pv = (after[c]-after[a]).toDouble(); val qv = (after[c+1]-after[a+1]).toDouble()
        if (kotlin.math.abs(pu*qv-qu*pv) < 1e-12) return false
        // Float points round in proportion to their distance from the origin: canvas pixels on a large canvas are
        // thousands, a Warp's 0..1 square is not.
        val magnitude = maxOf(before.maxOf { kotlin.math.abs(it) }, after.maxOf { kotlin.math.abs(it) }).toDouble()
        val tolerance = maxOf(1e-6 * maxOf(1.0, kotlin.math.abs(pu), kotlin.math.abs(qu), kotlin.math.abs(pv), kotlin.math.abs(qv)), 1e-5 * magnitude)
        for (i in before.indices step 2) {
            checkpoint()
            val x = (before[i]-before[a]).toDouble(); val y = (before[i+1]-before[a+1]).toDouble()
            val u = (x*vy-y*vx)/determinant; val v = (ux*y-uy*x)/determinant
            val dx = after[a]+u*pu+v*pv-after[i]; val dy = after[a+1]+u*qu+v*qv-after[i+1]
            if (kotlin.math.abs(dx) > tolerance || kotlin.math.abs(dy) > tolerance) return false
        }
        return true
    }

    private fun <T> validateGrid(grid: KeyformGrid<T>?, scalarCount: Int?, values: (T) -> FloatArray): String? {
        if (grid == null) return null
        if (grid.axes.any { axis -> axis.keys.isEmpty() || axis.keys.any { !it.isFinite() } }) return "Warp/keyform axis is malformed"
        val seen = mutableSetOf<String>()
        for (cell in grid.cells) {
            if (cell.coordinate.size != grid.axes.size) return "Keyform coordinate rank does not match its axes"
            val coordinate = linkedMapOf<String, Float>()
            for (i in grid.axes.indices) {
                val key = cell.coordinate[i]
                if (key !in grid.axes[i].keys.indices) return "Keyform coordinate index is outside its axis"
                coordinate[grid.axes[i].parameterId.raw] = grid.axes[i].keys[key]
            }
            if (!seen.add(coordinateKey(coordinate))) return "Duplicate native keyform coordinate"
            val scalars = values(cell.form)
            if (scalarCount != null && scalars.size != scalarCount) return "Geometry scalar count does not match the target"
        }
        return null
    }

    /**
     * Whether two targets of the same ref hold the same geometry: the mesh (positions, UVs, triangles), the
     * Warp lattice size, the Rotation's base angle and handle, the geometry keyform grid and the blend shapes.
     * Floats compare by bits as boxed equality does (NaN equals NaN, -0 differs from 0). Unchanged objects are
     * usually the same instance across rebuilds, which answers at once.
     */
    private fun sameGeometry(a: Target, b: Target): Boolean {
        if (a.source === b.source) return true
        val x = a.source; val y = b.source
        return when {
            x is Drawable && y is Drawable -> {
                val m = x.mesh; val n = y.mesh
                (m === n || (m != null && n != null && m.positions.contentEquals(n.positions) && m.uvs.contentEquals(n.uvs) &&
                    m.indices.contentEquals(n.indices))) &&
                    sameGrid(x.geometryGrid, y.geometryGrid) { f, g -> f.positionDeltas.contentEquals(g.positionDeltas) } &&
                    sameBlends(x.blendShapes, y.blendShapes) { f, g -> f.positionDeltas.contentEquals(g.positionDeltas) }
            }
            x is Deformer.Warp && y is Deformer.Warp -> x.rows == y.rows && x.columns == y.columns &&
                sameGrid(x.geometryGrid, y.geometryGrid) { f, g -> f.controlPoints.contentEquals(g.controlPoints) } &&
                sameBlends(x.blendShapes, y.blendShapes) { f, g -> f.controlPoints.contentEquals(g.controlPoints) }
            x is Deformer.Rotation && y is Deformer.Rotation -> same(x.baseAngle, y.baseAngle) && (x.handleLength as Any?) == (y.handleLength as Any?) &&
                sameGrid(x.geometryGrid, y.geometryGrid) { f, g -> samePivot(f.originX, f.originY, f.angle, f.scale, g.originX, g.originY, g.angle, g.scale) } &&
                sameBlends(x.blendShapes, y.blendShapes) { f, g -> samePivot(f.originX, f.originY, f.angle, f.scale, g.originX, g.originY, g.angle, g.scale) }
            else -> false
        }
    }

    private fun same(a: Float, b: Float) = a.toBits() == b.toBits()

    private fun samePivot(ax: Float, ay: Float, aa: Float, ascale: Float, bx: Float, by: Float, ba: Float, bscale: Float) =
        same(ax, bx) && same(ay, by) && same(aa, ba) && same(ascale, bscale)

    private inline fun <T> sameGrid(a: KeyformGrid<T>?, b: KeyformGrid<T>?, form: (T, T) -> Boolean): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.axes.size != b.axes.size || a.cells.size != b.cells.size) return false
        for (i in a.axes.indices) {
            if (a.axes[i].parameterId != b.axes[i].parameterId || !a.axes[i].keys.contentEquals(b.axes[i].keys)) return false
        }
        for (i in a.cells.indices) {
            val c = a.cells[i]; val d = b.cells[i]
            if (c !== d && (!c.coordinate.contentEquals(d.coordinate) || !form(c.form, d.form))) return false
        }
        return true
    }

    private inline fun <T : Any> sameBlends(a: List<BlendShapeBinding<T>>, b: List<BlendShapeBinding<T>>, form: (T, T) -> Boolean): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) {
            val c = a[i]; val d = b[i]
            if (c === d) continue
            if (c.parameterId != d.parameterId || !c.keys.contentEquals(d.keys) || c.neutralIndex != d.neutralIndex ||
                c.forms.size != d.forms.size || c.limits != d.limits) return false
            for (j in c.forms.indices) {
                val f = c.forms[j]; val g = d.forms[j]
                if (f === g) continue
                if (f == null || g == null || !form(f, g)) return false
            }
        }
        return true
    }
}

