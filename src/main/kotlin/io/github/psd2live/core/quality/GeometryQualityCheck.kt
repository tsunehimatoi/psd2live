package io.github.psd2live.core.quality

import io.github.psd2live.core.RigGeometryTools

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

internal data class GeometryEvidence(
    val reason: QualityRule,
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
 * The check is deliberately parent-local. Within its declared sampling scope it checks native geometry and
 * reports changed triangles at affected native key coordinates. Classification and commit decisions
 * belong to the shared quality rule registry and fence. It is
 * not a visual, mask, painted-coverage, physics, or aesthetic oracle.
 */
internal data class GeometryInspectionReport(
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
    val evidence: List<GeometryEvidence>,
    val diagnostics: List<JsonObject>,
) {
    val quality: QualityReport = QualityInspection.combine(QualityFence.AUTHORING_COMMIT, listOf(
        QualityCheckResult("geometry.candidate", SCOPE, evidence.map { finding ->
            QualityFinding(finding.reason, finding.target, JsonObject(finding.toJson() - setOf("reason", "target")))
        })))
    val safe: Boolean get() = quality.canCommit
    val violations get() = evidence.filter { it.reason.severity == QualitySeverity.ERROR }
    val warnings get() = evidence.filter { it.reason.severity == QualitySeverity.WARNING }
    val information get() = evidence.filter { it.reason.severity == QualitySeverity.INFO }

    fun toJson(): JsonObject = buildJsonObject {
        put("safe", safe)
        put("quality", quality.toJson())
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
        put("information", JsonArray(information.map { it.toJson() }))
        put("diagnostics", JsonArray(diagnostics))
        put("scope", SCOPE)
    }

    companion object {
        const val SCOPE = "Affected parent-local native key coordinates only; no interpolation sweep, parent composition, masks, painted coverage, physics, or aesthetics."
        fun noGeometryChange(): GeometryInspectionReport = GeometryInspectionReport(
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
            evidence = emptyList(),
            diagnostics = emptyList(),
        )
    }
}

internal object GeometryQualityCheck {
    private class GeometrySamplingLimit : RuntimeException()
    private data class Target(
        val ref: String,
        val kind: String,
        val id: String,
        val parent: String?,
        val signature: Any,
        val coordinateSource: () -> List<Map<String, Float>>,
        val triangles: IntArray,
        val reference: FloatArray,
        val pointCount: Int,
        val rawFinite: () -> Boolean,
        val rawValidation: () -> String?,
    ) {
        val coordinates by lazy(coordinateSource)
    }

    fun evaluate(before: PuppetModel, candidate: PuppetModel): GeometryInspectionReport {
        val beforeTargets = targets(before)
        val afterTargets = targets(candidate)
        val beforeParameters = before.parameters.mapTo(HashSet()) { it.id.raw }
        val direct = (beforeTargets.keys + afterTargets.keys).filterTo(mutableSetOf()) { ref ->
            beforeTargets[ref]?.let { old -> afterTargets[ref]?.let { next -> old.signature != next.signature || old.parent != next.parent } ?: true } ?: true
        }
        if (direct.isEmpty()) return GeometryInspectionReport.noGeometryChange()

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
        val evidence = mutableListOf<GeometryEvidence>()
        val diagnostics = mutableListOf<JsonObject>()
        val coordinateEvidence = linkedMapOf<String, List<Map<String, Float>>>()

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
                evidence += GeometryEvidence(QualityRule.GEOMETRY_INVALID_TOPOLOGY, ref, emptyMap(), detail = detail)
                diagnostics += buildJsonObject { put("target", ref); put("status", "invalid_topology"); put("detail", detail) }
                continue
            }
            if (!next.rawFinite()) {
                nonFinite++
                evidence += GeometryEvidence(QualityRule.GEOMETRY_NON_FINITE, ref, emptyMap(),
                    detail = "Non-finite native geometry or UV data")
                continue
            }
            val coordinates = try { next.coordinates } catch (_: GeometrySamplingLimit) {
                coordinateEvidence[ref] = emptyList()
                evidence += GeometryEvidence(QualityRule.GEOMETRY_SAMPLING_LIMIT, ref, emptyMap(),
                    detail = "More than 16384 geometry coordinates; this target was not sampled")
                diagnostics += buildJsonObject { put("target", ref); put("status", "not_sampled") }
                continue
            }
            coordinateEvidence[ref] = coordinates
            for (coordinate in coordinates) {
                checkpoint()
                val candidatePoints = sample(candidate, next, coordinate)
                if (candidatePoints == null) {
                    invalid++
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Geometry cannot be evaluated at native coordinate")
                    continue
                }
                if (!candidatePoints.all(Float::isFinite)) {
                    nonFinite++
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_NON_FINITE, ref, coordinate)
                    continue
                }
                if (next.triangles.isEmpty()) {
                    diagnostics += coordinateDiagnostic(ref, coordinate, "finite", candidatePoints.size / 2)
                    continue
                }

                val oldComparable = old?.takeIf {
                    it.pointCount == next.pointCount && it.triangles.contentEquals(next.triangles)
                }?.let { comparable -> sample(before, comparable, coordinate.filterKeys { it in beforeParameters }) }
                val reference = if (oldComparable != null) old.reference else next.reference
                val referenceStatus = runCatching { RigGeometryDiagnostics.inspect(reference, reference, next.triangles) }.getOrNull()
                val candidateStatus = runCatching { RigGeometryDiagnostics.inspect(reference, candidatePoints, next.triangles) }.getOrNull()
                if (referenceStatus == null || candidateStatus == null) {
                    invalid++
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Geometry scalar count or triangle indices are malformed")
                    continue
                }
                val oldStatus = oldComparable?.takeIf { it.all(Float::isFinite) }?.let {
                    runCatching { RigGeometryDiagnostics.inspect(reference, it, next.triangles) }.getOrNull()
                }
                if (oldComparable != null && oldStatus == null) {
                    invalid++
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_INVALID_TOPOLOGY, ref, coordinate, detail = "Baseline geometry cannot be compared")
                    continue
                }

                oldFlips += oldStatus?.flippedTriangles?.size ?: 0
                oldDegenerates += oldStatus?.let { (it.degenerateTriangles + it.degenerateReferenceTriangles).size } ?: 0
                oldCollapses += oldStatus?.collapsedTriangles?.size ?: 0

                // A whole-surface invertible affine mirror or compression is a valid authoring edit.
                // Local shape changes are retained as evidence and classified by the shared rule registry.
                val affine = oldComparable != null && invertibleAffine(oldComparable, candidatePoints, next.triangles)
                val newlyFlipped = candidateStatus.flippedTriangles.filter { !affine && it !in oldStatus?.flippedTriangles.orEmpty() }
                val oldDegenerateTriangles = oldStatus?.let { it.degenerateTriangles + it.degenerateReferenceTriangles }.orEmpty()
                val candidateDegenerateTriangles = candidateStatus.degenerateTriangles + candidateStatus.degenerateReferenceTriangles
                val newlyDegenerate = candidateDegenerateTriangles.filter { it !in oldDegenerateTriangles }
                val newlyCollapsed = candidateStatus.collapsedTriangles.filter {
                    !affine && it !in oldStatus?.collapsedTriangles.orEmpty() && it !in newlyFlipped && it !in newlyDegenerate
                }
                if (newlyFlipped.isNotEmpty()) {
                    newFlips += newlyFlipped.size
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_NEW_FLIP, ref, coordinate, newlyFlipped)
                }
                if (newlyDegenerate.isNotEmpty()) {
                    newDegenerates += newlyDegenerate.size
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_NEW_DEGENERATE, ref, coordinate, newlyDegenerate)
                }
                if (newlyCollapsed.isNotEmpty()) {
                    newCollapses += newlyCollapsed.size
                    evidence += GeometryEvidence(QualityRule.GEOMETRY_NEW_COLLAPSE, ref, coordinate, newlyCollapsed)
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
        return GeometryInspectionReport(affected.sorted(), coordinateEvidence, newFlips, newDegenerates,
            invalid, nonFinite, oldFlips, oldDegenerates, oldCollapses, newCollapses, evidence, diagnostics)
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
                listOf(drawableGeometrySignature(drawable), blendIdentity(drawable.blendShapes) { it.positionDeltas }),
                { coordinates(drawable.geometryGrid, drawable.blendShapes) }, mesh.indices, mesh.positions,
                mesh.positions.size / 2, {
                    mesh.positions.all(Float::isFinite) && mesh.uvs.all(Float::isFinite) &&
                        formsFinite(drawable.geometryGrid, drawable.blendShapes, { it.positionDeltas }, { it.positionDeltas })
                }) { validateMesh(drawable) })
        }
        model.deformers.forEach { deformer ->
            when (deformer) {
                is Deformer.Warp -> {
                    val ref = "warp:${deformer.id.raw}"
                    val domain = warpDomain(deformer.rows, deformer.columns)
                    put(ref, Target(ref, "warp", deformer.id.raw, deformer.parent?.raw,
                        listOf(warpGeometrySignature(deformer), blendIdentity(deformer.blendShapes) { it.controlPoints }), { coordinates(deformer.geometryGrid, deformer.blendShapes) },
                        RigGeometryDiagnostics.lattice(deformer.rows, deformer.columns), domain, domain.size / 2, {
                            formsFinite(deformer.geometryGrid, deformer.blendShapes, { it.controlPoints }, { it.controlPoints })
                        }) { validateWarp(deformer) })
                }
                is Deformer.Rotation -> {
                    val ref = "rotation:${deformer.id.raw}"
                    put(ref, Target(ref, "rotation", deformer.id.raw, deformer.parent?.raw,
                        listOf(rotationGeometrySignature(deformer), blendIdentity(deformer.blendShapes) { floatArrayOf(it.originX, it.originY, it.angle, it.scale) }),
                        { coordinates(deformer.geometryGrid, deformer.blendShapes) }, IntArray(0), FloatArray(4), 2, {
                            deformer.baseAngle.isFinite() && (deformer.handleLength?.isFinite() != false) && formsFinite(deformer.geometryGrid, deformer.blendShapes,
                                { floatArrayOf(it.originX, it.originY, it.angle, it.scale) }, { floatArrayOf(it.originX, it.originY, it.angle, it.scale) })
                        }) { validateRotation(deformer) })
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
        if (base.size > 16384) throw GeometrySamplingLimit()
        val axes = linkedMapOf<String, MutableSet<Float>>()
        blends.forEach { blend ->
            axes.getOrPut(blend.parameterId.raw) { linkedSetOf() }.addAll(blend.keys.toList())
            blend.limits.forEach { limit -> axes.getOrPut(limit.parameterId.raw) { linkedSetOf() }.addAll(limit.points.map { it.value }) }
        }
        var result = base
        axes.forEach { (id, values) ->
            require(values.all { it.isFinite() }) { "Blend coordinates must be finite" }
            if (result.size.toLong() * values.size > 16384) throw GeometrySamplingLimit()
            result = result.flatMap { coordinate -> values.sorted().map { coordinate + (id to it) } }.distinctBy(::coordinateKey)
        }
        return result
    }

    private fun <T, B : Any> formsFinite(grid: KeyformGrid<T>?, blends: List<BlendShapeBinding<B>>,
                                         gridValues: (T) -> FloatArray, blendValues: (B) -> FloatArray): Boolean =
        grid?.cells.orEmpty().all { gridValues(it.form).all(Float::isFinite) } &&
            blends.all { blend -> blend.forms.filterNotNull().all { blendValues(it).all(Float::isFinite) } }

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
            if (blend.limits.any { limit -> limit.points.any { !it.value.isFinite() || !it.weight.isFinite() } }) return "Malformed blend limit coordinates or weights"
            if (blend.forms.filterNotNull().any { values(it).size != count }) return "Malformed blend geometry"
        }
        return null
    }

    private fun <T : Any> blendIdentity(blends: List<BlendShapeBinding<T>>, values: (T) -> FloatArray): Any =
        blends.map { listOf(it.parameterId.raw, it.keys.toList(), it.neutralIndex, it.forms.map { form -> form?.let { values(it).toList() } }, it.limits) }

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
        val tolerance = 1e-6 * maxOf(1.0, kotlin.math.abs(pu), kotlin.math.abs(qu), kotlin.math.abs(pv), kotlin.math.abs(qv))
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

    private fun drawableGeometrySignature(drawable: Drawable): Any = listOf(drawable.parentDeformerId?.raw,
        drawable.mesh?.positions?.toList(), drawable.mesh?.uvs?.toList(), drawable.mesh?.indices?.toList(),
        gridSignature(drawable.geometryGrid) { it.positionDeltas })

    private fun warpGeometrySignature(warp: Deformer.Warp): Any = listOf(
        warp.parent?.raw, warp.rows, warp.columns, gridSignature(warp.geometryGrid) { it.controlPoints })

    private fun rotationGeometrySignature(rotation: Deformer.Rotation): Any = listOf(
        rotation.parent?.raw, rotation.baseAngle, rotation.handleLength,
        gridSignature(rotation.geometryGrid) { floatArrayOf(it.originX, it.originY, it.angle, it.scale) })

    private fun <T> gridSignature(grid: KeyformGrid<T>?, form: (T) -> FloatArray): Any = listOf(
        grid?.axes?.map { listOf(it.parameterId.raw, it.keys.toList()) },
        grid?.cells?.map { listOf(it.coordinate.toList(), form(it.form).toList()) })
}
