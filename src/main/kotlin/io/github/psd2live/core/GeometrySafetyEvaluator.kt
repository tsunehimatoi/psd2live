package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Stable machine-readable reasons returned by the Agent geometry commit gate. */
internal enum class GeometrySafetyReason {
    GEOMETRY_NON_FINITE,
    GEOMETRY_INVALID_TOPOLOGY,
    GEOMETRY_NEW_FLIP,
    GEOMETRY_NEW_DEGENERATE,
    GEOMETRY_NEW_COLLAPSE,
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
 * The evaluator is deliberately parent-local. It proves finite, well-formed native geometry and
 * prevents newly inverted/degenerate/collapsed triangles at affected native key coordinates. It is
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
        put("diagnostics", JsonArray(diagnostics))
        put("scope", "Affected parent-local native key coordinates only; no parent composition, masks, painted coverage, physics, or aesthetics.")
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

internal class GeometrySafetyRejectedException(val safetyReport: GeometrySafetyReport) :
    IllegalArgumentException("Geometry safety gate rejected the candidate: " +
        safetyReport.violations.map { it.reason.name }.distinct().joinToString(","))

internal object GeometrySafetyEvaluator {
    private data class Target(
        val ref: String,
        val kind: String,
        val id: String,
        val parent: String?,
        val signature: Int,
        val coordinates: List<Map<String, Float>>,
        val triangles: IntArray,
        val reference: FloatArray,
        val pointCount: Int,
        val rawValidation: () -> String?,
    )

    fun evaluate(before: PuppetModel, candidate: PuppetModel): GeometrySafetyReport {
        val beforeTargets = targets(before)
        val afterTargets = targets(candidate)
        val direct = (beforeTargets.keys + afterTargets.keys).filterTo(mutableSetOf()) { ref ->
            beforeTargets[ref]?.let { old -> afterTargets[ref]?.let { next -> old.signature != next.signature || old.parent != next.parent } ?: true } ?: true
        }
        if (direct.isEmpty()) return GeometrySafetyReport.noGeometryChange()

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

        for (ref in affected.sorted()) {
            val old = beforeTargets[ref]
            val next = afterTargets[ref]
            if (next == null) {
                coordinateEvidence[ref] = emptyList()
                diagnostics += buildJsonObject { put("target", ref); put("status", "removed") }
                continue
            }
            coordinateEvidence[ref] = next.coordinates
            next.rawValidation()?.let { detail ->
                invalid++
                violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, emptyMap(), detail = detail)
                diagnostics += buildJsonObject { put("target", ref); put("status", "invalid_topology"); put("detail", detail) }
                continue
            }

            val oldCoordinates = old?.coordinates.orEmpty().associateBy(::coordinateKey)
            for (coordinate in next.coordinates) {
                val key = coordinateKey(coordinate)
                val candidatePoints = sample(candidate, next, coordinate)
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

                val oldComparable = old?.takeIf {
                    it.pointCount == next.pointCount && it.triangles.contentEquals(next.triangles)
                }?.let { comparable ->
                    oldCoordinates[key]?.let { sample(before, comparable, it) }
                }
                val reference = if (oldComparable != null) old.reference else next.reference
                val referenceStatus = runCatching { RigGeometryDiagnostics.inspect(reference, reference, next.triangles) }.getOrNull()
                val candidateStatus = runCatching { RigGeometryDiagnostics.inspect(reference, candidatePoints, next.triangles) }.getOrNull()
                if (referenceStatus == null || candidateStatus == null) {
                    invalid++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Geometry scalar count or triangle indices are malformed")
                    continue
                }
                val oldStatus = oldComparable?.takeIf { it.all(Float::isFinite) }?.let {
                    runCatching { RigGeometryDiagnostics.inspect(reference, it, next.triangles) }.getOrNull()
                }
                if (oldComparable != null && oldStatus == null) {
                    invalid++
                    violations += GeometrySafetyViolation(GeometrySafetyReason.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Baseline geometry cannot be compared")
                    continue
                }

                oldFlips += oldStatus?.flippedTriangles?.size ?: 0
                oldDegenerates += oldStatus?.let { (it.degenerateTriangles + it.degenerateReferenceTriangles).size } ?: 0
                oldCollapses += oldStatus?.collapsedTriangles?.size ?: 0

                val newlyFlipped = candidateStatus.flippedTriangles.filter { it !in oldStatus?.flippedTriangles.orEmpty() }
                val oldDegenerateTriangles = oldStatus?.let { it.degenerateTriangles + it.degenerateReferenceTriangles }.orEmpty()
                val candidateDegenerateTriangles = candidateStatus.degenerateTriangles + candidateStatus.degenerateReferenceTriangles
                val newlyDegenerate = candidateDegenerateTriangles.filter { it !in oldDegenerateTriangles }
                val newlyCollapsed = candidateStatus.collapsedTriangles.filter {
                    it !in oldStatus?.collapsedTriangles.orEmpty() && it !in newlyFlipped && it !in newlyDegenerate
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
        val safe = violations.isEmpty()
        return GeometrySafetyReport(safe, affected.sorted(), coordinateEvidence, newFlips, newDegenerates,
            invalid, nonFinite, oldFlips, oldDegenerates, oldCollapses, newCollapses, violations, diagnostics)
    }

    private fun coordinateDiagnostic(ref: String, coordinate: Map<String, Float>, status: String, points: Int) = buildJsonObject {
        put("target", ref)
        putJsonObject("coordinate") { coordinate.toSortedMap().forEach { (id, value) -> put(id, value) } }
        put("status", status)
        put("pointCount", points)
    }

    private fun targets(model: PuppetModel): Map<String, Target> = buildMap {
        model.drawables.filter { it.mesh != null }.forEach { drawable ->
            val mesh = drawable.mesh!!
            val ref = "mesh:${drawable.id.raw}"
            put(ref, Target(ref, "mesh", drawable.id.raw, drawable.parentDeformerId?.raw,
                drawableGeometrySignature(drawable), coordinates(drawable.geometryGrid), mesh.indices, mesh.positions,
                mesh.positions.size / 2) { validateMesh(drawable) })
        }
        model.deformers.forEach { deformer ->
            when (deformer) {
                is Deformer.Warp -> {
                    val ref = "warp:${deformer.id.raw}"
                    val domain = warpDomain(deformer.rows, deformer.columns)
                    put(ref, Target(ref, "warp", deformer.id.raw, deformer.parent?.raw,
                        warpGeometrySignature(deformer), coordinates(deformer.geometryGrid),
                        RigGeometryDiagnostics.lattice(deformer.rows, deformer.columns), domain, domain.size / 2) { validateWarp(deformer) })
                }
                is Deformer.Rotation -> {
                    val ref = "rotation:${deformer.id.raw}"
                    put(ref, Target(ref, "rotation", deformer.id.raw, deformer.parent?.raw,
                        rotationGeometrySignature(deformer), coordinates(deformer.geometryGrid), IntArray(0), FloatArray(4), 2) { validateRotation(deformer) })
                }
            }
        }
    }

    private fun sample(model: PuppetModel, target: Target, coordinate: Map<String, Float>): FloatArray? =
        runCatching { RigGeometryTools.geometry(model, target.kind, target.id, coordinate).points }.getOrNull()

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

    private fun <T> coordinates(grid: KeyformGrid<T>?): List<Map<String, Float>> {
        if (grid == null || grid.axes.isEmpty()) return listOf(emptyMap())
        return grid.cells.mapNotNull { cell ->
            if (cell.coordinate.size != grid.axes.size) null else buildMap<String, Float> {
                for (i in grid.axes.indices) {
                    val keyIndex = cell.coordinate[i]
                    val axis = grid.axes[i]
                    if (keyIndex !in axis.keys.indices) return@mapNotNull null
                    put(axis.parameterId.raw, axis.keys[keyIndex])
                }
            }
        }.distinctBy(::coordinateKey).sortedBy(::coordinateKey)
    }

    private fun validateMesh(drawable: Drawable): String? {
        val mesh = drawable.mesh ?: return "Drawable has no mesh"
        if (mesh.positions.size % 2 != 0) return "Position scalar count must be even"
        if (mesh.uvs.size != mesh.positions.size) return "UV scalar count must equal position scalar count"
        if (mesh.indices.size % 3 != 0) return "Triangle index count must be divisible by three"
        if (mesh.indices.any { it !in 0 until mesh.positions.size / 2 }) return "Triangle index is outside the vertex range"
        return validateGrid(drawable.geometryGrid, mesh.positions.size) { it.positionDeltas }
    }

    private fun validateWarp(warp: Deformer.Warp): String? {
        if (warp.rows < 1 || warp.columns < 1) return "Warp lattice dimensions must be positive"
        val expected = (warp.rows + 1) * (warp.columns + 1) * 2
        return validateGrid(warp.geometryGrid, expected) { it.controlPoints }
    }

    private fun validateRotation(rotation: Deformer.Rotation): String? = validateGrid(rotation.geometryGrid, null) {
        floatArrayOf(it.originX, it.originY, it.angle, it.scale)
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

    private fun drawableGeometrySignature(drawable: Drawable): Int {
        var result = drawable.parentDeformerId?.raw.hashCode()
        val mesh = drawable.mesh ?: return result
        result = 31 * result + mesh.positions.contentHashCode()
        result = 31 * result + mesh.uvs.contentHashCode()
        result = 31 * result + mesh.indices.contentHashCode()
        result = 31 * result + gridSignature(drawable.geometryGrid) { it.positionDeltas.contentHashCode() }
        return result
    }

    private fun warpGeometrySignature(warp: Deformer.Warp): Int = listOf(
        warp.parent?.raw.hashCode(), warp.rows, warp.columns,
        gridSignature(warp.geometryGrid) { it.controlPoints.contentHashCode() },
    ).fold(1) { acc, value -> 31 * acc + value }

    private fun rotationGeometrySignature(rotation: Deformer.Rotation): Int = listOf(
        rotation.parent?.raw.hashCode(),
        gridSignature(rotation.geometryGrid) { listOf(it.originX.toRawBits(), it.originY.toRawBits(), it.angle.toRawBits(), it.scale.toRawBits()).hashCode() },
    ).fold(1) { acc, value -> 31 * acc + value }

    private fun <T> gridSignature(grid: KeyformGrid<T>?, form: (T) -> Int): Int {
        if (grid == null) return 0
        var result = 1
        grid.axes.forEach { axis -> result = 31 * result + axis.parameterId.raw.hashCode(); result = 31 * result + axis.keys.contentHashCode() }
        grid.cells.forEach { cell -> result = 31 * result + cell.coordinate.contentHashCode(); result = 31 * result + form(cell.form) }
        return result
    }
}
