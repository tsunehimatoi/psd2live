package io.github.psd2live.core

import org.umamo.edit.MeshElement
import org.umamo.edit.MeshRefinementOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.hypot

/*
 * Glue seams.
 *
 * A glue pair is one point shared by two meshes - Cubism binds "overlapping vertices". The runtime welds a
 * pair by pulling each side toward the other, `A' = A + (B-A)·wA·i`, so a pair whose two vertices sit
 * apart drags the artwork the moment it exists. The brush therefore never pairs vertices that are merely
 * near each other: it makes them coincide first, the way every other Edit tool changes a mesh. A vertex is
 * only created, or slid together with its UV, so the picture does not move, and each pair starts on one
 * point. The weld then does nothing at rest and only holds the seam once the meshes deform differently.
 *
 * For every brushed vertex that lies over the other mesh, or within [tolerance] of its outline:
 * - a free vertex of the other mesh within the tolerance slides onto it;
 * - otherwise the other mesh gains a vertex exactly there, inside a face or on an edge.
 * The silhouette never grows a new triangle, and the new vertices' neighbourhood is re-flipped toward
 * Delaunay so repeated inserts do not leave slivers.
 */

/** The default weld weights: both sides meet halfway, as Cubism's glue does. */
internal const val GLUE_DEFAULT_WEIGHT = 0.5f

/** One planned weld: [seed] on the source mesh meets [existing] on the target mesh, or a new vertex there. */
internal data class GlueWeldPlan(val seed: Int, val x: Float, val y: Float, val existing: Int?)

/**
 * Which of [seeds] on the source mesh can be welded to the target mesh, and how.
 *
 * A seed within [tolerance] of a target vertex takes that vertex, or is skipped when it is already glued,
 * since a second vertex there would only make a sliver. A seed inside the target, or within [tolerance] of
 * its outline, gets a new target vertex at its own position. Seeds are taken in index order so the plan
 * replays identically.
 */
internal fun planGlueWelds(
    source: FloatArray,
    seeds: Collection<Int>,
    target: FloatArray,
    targetIndices: IntArray,
    tolerance: Float,
    targetUsed: Set<Int> = emptySet(),
): List<GlueWeldPlan> {
    val claimed = targetUsed.toHashSet()
    val outline = outlineEdges(targetIndices)
    val plans = ArrayList<GlueWeldPlan>()
    val created = ArrayList<Pair<Float, Float>>()
    for (seed in seeds.distinct().sorted()) {
        if (seed !in 0 until source.size / 2) continue
        val x = source[seed * 2]
        val y = source[seed * 2 + 1]
        val nearest = nearestVertex(target, x, y)
        if (nearest != null && nearest.second <= tolerance) {
            if (nearest.first in claimed) continue
            claimed += nearest.first
            plans += GlueWeldPlan(seed, x, y, nearest.first)
            continue
        }
        if (created.any { hypot(it.first - x, it.second - y) <= tolerance }) continue
        val reachable = pointInsideMesh(x, y, target, targetIndices) ||
            distanceToEdges(target, outline, x, y) <= tolerance
        if (!reachable) continue
        created += x to y
        plans += GlueWeldPlan(seed, x, y, null)
    }
    return plans
}

/** The glue a brush pass produced: the edited model and the new pairs, stored as (A, B). */
internal class GlueWeldResult(val model: PuppetModel, val pairs: List<GluePair>)

/**
 * Welds the stroked vertices of [meshA] and [meshB] into coincident pairs at [pose].
 *
 * Vertices already listed in [occupiedA] / [occupiedB] are left alone, so a second stroke extends the
 * seam instead of redoing it. Only rest meshes change, and only in ways that keep the picture: vertex
 * inserts, UV-carrying slides and UV-safe edge flips. No keyform is written.
 */
internal fun weldGlueSeam(
    model: PuppetModel,
    meshA: DrawableId,
    meshB: DrawableId,
    hitsA: Set<Int>,
    hitsB: Set<Int>,
    pose: Map<ParameterId, Float>,
    tolerance: Float,
    occupiedA: Set<Int> = emptySet(),
    occupiedB: Set<Int> = emptySet(),
): GlueWeldResult {
    val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(model, pose).worldPositions
    val frameA = requireNotNull(world[meshA]) { "First mesh is not visible at this pose" }
    val frameB = requireNotNull(world[meshB]) { "Second mesh is not visible at this pose" }
    val welded = weldGlueFrames(model, meshA, meshB, frameA, frameB, hitsA, hitsB, tolerance, occupiedA, occupiedB)
    return GlueWeldResult(welded.model, welded.pairs)
}

/** A weld in caller-given frames: the edited model, the new pairs as (A, B) and both meshes' frames after. */
internal class GlueFrameWeld(val model: PuppetModel, val pairs: List<GluePair>, val frameA: FloatArray, val frameB: FloatArray)

/**
 * [weldGlueSeam] with both meshes' vertices already placed in one shared 2D space, [startFrameA] and
 * [startFrameB].
 *
 * A [fixedA] / [fixedB] mesh keeps its topology and rest vertices: it is never inserted into or slid, and
 * only takes seeds that already sit within [tolerance] of one of its vertices.
 */
internal fun weldGlueFrames(
    model: PuppetModel,
    meshA: DrawableId,
    meshB: DrawableId,
    startFrameA: FloatArray,
    startFrameB: FloatArray,
    hitsA: Set<Int>,
    hitsB: Set<Int>,
    tolerance: Float,
    occupiedA: Set<Int> = emptySet(),
    occupiedB: Set<Int> = emptySet(),
    fixedA: Boolean = false,
    fixedB: Boolean = false,
): GlueFrameWeld {
    var frameA = startFrameA
    var frameB = startFrameB
    var working = model
    val usedA = occupiedA.toHashSet()
    val usedB = occupiedB.toHashSet()
    val pairs = ArrayList<GluePair>()

    fun pass(fromA: Boolean, seeds: Set<Int>) {
        val targetId = if (fromA) meshB else meshA
        val source = if (fromA) frameA else frameB
        val usedSource = if (fromA) usedA else usedB
        val usedTarget = if (fromA) usedB else usedA
        val targetMesh = working.drawables.first { it.id == targetId }.mesh ?: return
        val targetFrame = if (fromA) frameB else frameA
        val fixed = if (fromA) fixedB else fixedA
        val plans = planGlueWelds(source, seeds - usedSource, targetFrame, targetMesh.indices, tolerance, usedTarget)
            .filter { !fixed || it.existing != null }
        if (plans.isEmpty()) return
        val partners = if (fixed) plans.map { it.existing } else {
            val placed = GlueTargetEdit(targetMesh, targetFrame).apply(plans, tolerance)
            if (placed.edit != null) working = working.withMeshTopologyEdit(targetId, placed.edit)
            working = working.copy(drawables = working.drawables.map { if (it.id == targetId) it.copy(mesh = placed.mesh) else it })
            if (fromA) frameB = placed.frame else frameA = placed.frame
            placed.partners
        }
        for ((plan, partner) in plans.zip(partners)) {
            if (partner == null) continue
            usedSource += plan.seed
            usedTarget += partner
            pairs += if (fromA) {
                GluePair(plan.seed, partner, GLUE_DEFAULT_WEIGHT, GLUE_DEFAULT_WEIGHT)
            } else {
                GluePair(partner, plan.seed, GLUE_DEFAULT_WEIGHT, GLUE_DEFAULT_WEIGHT)
            }
        }
    }
    pass(fromA = true, seeds = hitsA)
    pass(fromA = false, seeds = hitsB)
    return GlueFrameWeld(working, pairs, frameA, frameB)
}

/**
 * The target side of one pass: insert the new vertices in one composed topology edit, slide every planned
 * vertex exactly onto its seed, then flip the touched neighbourhood toward Delaunay.
 */
private class GlueTargetEdit(private val original: DrawableMesh, private val originalFrame: FloatArray) {
    class Placed(
        val edit: org.umamo.edit.MeshTopologyEdit?,
        val mesh: DrawableMesh,
        val frame: FloatArray,
        val partners: List<Int?>,
    )

    fun apply(plans: List<GlueWeldPlan>, tolerance: Float): Placed {
        val fresh = plans.filter { it.existing == null }
        // A point within the tolerance of an edge splits that edge and then slides the last hair, rather
        // than cutting a face into a sliver beside it; a point outside the mesh can only land on its edge.
        val insertion = if (fresh.isEmpty()) null else MeshRefinementOps.insertPoints(
            original, originalFrame, fresh.map { it.x to it.y }, extend = false, edgeSnap = tolerance,
        )
        var mesh = insertion?.result?.edit?.newMesh ?: original
        var frame = insertion?.frame ?: originalFrame
        val insertedAt = HashMap<GlueWeldPlan, Int?>()
        fresh.forEachIndexed { index, plan -> insertedAt[plan] = insertion?.placed?.getOrNull(index) }
        val partners = ArrayList<Int?>(plans.size)
        for (plan in plans) {
            val vertex = plan.existing ?: insertedAt[plan]
            if (vertex == null) {
                partners += null
                continue
            }
            val slid = slideVertex(mesh, frame, vertex, plan.x, plan.y)
            if (slid != null) {
                mesh = slid.first
                frame = slid.second
                partners += vertex
            } else {
                // A slide that would fold a triangle is refused, and so is the pair: a weld between two
                // vertices that do not coincide would pull the artwork at rest.
                partners += null
            }
        }
        val touched = partners.filterNotNullTo(HashSet())
        if (touched.isNotEmpty()) {
            mesh = DrawableMesh(mesh.positions, mesh.uvs, MeshRefinementOps.flipTowardDelaunay(mesh, frame, touched))
        }
        val edit = insertion?.result?.edit?.let { org.umamo.edit.MeshTopologyEdit(mesh, it.vertexSources) }
        return Placed(edit, mesh, frame, partners)
    }
}


/**
 * [vertex] moved to (x, y) in [frame] space, carrying its UV.
 *
 * The new rest position and UV are one affine combination of an incident triangle's corners - the one
 * that best contains the target - so the texel under the vertex is the texel that was already drawn
 * there. Null when the slide would flip or collapse an incident triangle.
 */
internal fun slideVertex(mesh: DrawableMesh, frame: FloatArray, vertex: Int, x: Float, y: Float): Pair<DrawableMesh, FloatArray>? {
    if (vertex !in 0 until mesh.positions.size / 2 || vertex * 2 + 1 >= frame.size) return null
    val dx = x - frame[vertex * 2]
    val dy = y - frame[vertex * 2 + 1]
    if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return mesh to frame
    val incident = (0 until mesh.indices.size / 3).filter { t -> (0..2).any { mesh.indices[t * 3 + it] == vertex } }
    if (incident.isEmpty()) return null
    var best: Pair<IntArray, FloatArray>? = null
    var bestScore = -Float.MAX_VALUE
    for (t in incident) {
        val corners = IntArray(3) { mesh.indices[t * 3 + it] }
        val weights = barycentric(frame, corners, x, y) ?: continue
        val score = weights.min()
        if (score > bestScore) {
            bestScore = score
            best = corners to weights
        }
    }
    val (corners, weights) = best ?: return null
    val movedFrame = frame.copyOf()
    movedFrame[vertex * 2] = x
    movedFrame[vertex * 2 + 1] = y
    for (t in incident) {
        val corners2 = IntArray(3) { mesh.indices[t * 3 + it] }
        val before = signedArea(frame, corners2)
        val after = signedArea(movedFrame, corners2)
        if (abs(after) < abs(before) * 1e-3f + 1e-9f || before * after < 0f) return null
    }
    fun mix(values: FloatArray, component: Int): Float =
        corners.indices.sumOf { (values[corners[it] * 2 + component] * weights[it]).toDouble() }.toFloat()
    val positions = mesh.positions.copyOf()
    val uvs = mesh.uvs.copyOf()
    val px = mix(mesh.positions, 0)
    val py = mix(mesh.positions, 1)
    val u = if (uvs.size == positions.size) mix(mesh.uvs, 0) else 0f
    val v = if (uvs.size == positions.size) mix(mesh.uvs, 1) else 0f
    positions[vertex * 2] = px
    positions[vertex * 2 + 1] = py
    if (uvs.size == positions.size) {
        uvs[vertex * 2] = u
        uvs[vertex * 2 + 1] = v
    }
    return DrawableMesh(positions, uvs, mesh.indices) to movedFrame
}

/** Weight brush. [delta] is added; negative values erase. A:B paints both sides of a touched pair. */
internal enum class GlueWeightPaint { BALANCE, A, B }

internal fun paintGlueWeights(
    pairs: List<GluePair>,
    hitsA: Set<Int>,
    hitsB: Set<Int>,
    mode: GlueWeightPaint,
    delta: Float,
): List<GluePair> {
    if (delta == 0f) return pairs
    return pairs.map { pair ->
        val touchA = pair.indexA in hitsA
        val touchB = pair.indexB in hitsB
        val paintA = when (mode) {
            GlueWeightPaint.A -> touchA
            GlueWeightPaint.B -> false
            GlueWeightPaint.BALANCE -> touchA || touchB
        }
        val paintB = when (mode) {
            GlueWeightPaint.A -> false
            GlueWeightPaint.B -> touchB
            GlueWeightPaint.BALANCE -> touchA || touchB
        }
        if (!paintA && !paintB) pair
        else GluePair(
            pair.indexA,
            pair.indexB,
            if (paintA) (pair.weightA + delta).coerceIn(0f, 1f) else pair.weightA,
            if (paintB) (pair.weightB + delta).coerceIn(0f, 1f) else pair.weightB,
        )
    }
}

/**
 * Per-vertex weld weight of [drawableId] across every glue it takes part in, 0 where it is not glued.
 * A vertex glued twice shows the stronger weld.
 */
internal fun glueVertexWeights(model: PuppetModel, drawableId: DrawableId, vertexCount: Int): FloatArray {
    val weights = FloatArray(vertexCount)
    for (glue in model.glues) {
        for (pair in glue.pairs) {
            if (glue.meshA == drawableId && pair.indexA in 0 until vertexCount) weights[pair.indexA] = maxOf(weights[pair.indexA], pair.weightA)
            if (glue.meshB == drawableId && pair.indexB in 0 until vertexCount) weights[pair.indexB] = maxOf(weights[pair.indexB], pair.weightB)
        }
    }
    return weights
}

/** The outline vertices of a mesh, holes included; every vertex when it has no open edge. */
internal fun outlineVertices(indices: IntArray, vertexCount: Int): Set<Int> {
    val edges = outlineEdges(indices)
    if (edges.isEmpty()) return (0 until vertexCount).toSet()
    return edges.flatMapTo(HashSet()) { listOf(it.endpointLow, it.endpointHigh) }
}

private fun outlineEdges(indices: IntArray): List<MeshElement.Edge> {
    val counts = HashMap<MeshElement.Edge, Int>()
    for (triangle in 0 until indices.size / 3) {
        for (edge in MeshTopology.edgesOfTriangle(indices, triangle)) {
            counts[edge] = (counts[edge] ?: 0) + 1
        }
    }
    return counts.filterValues { it == 1 }.keys.toList()
}

private fun nearestVertex(positions: FloatArray, x: Float, y: Float): Pair<Int, Float>? {
    var nearest = -1
    var best = Float.MAX_VALUE
    for (i in 0 until positions.size / 2) {
        val d = hypot(positions[i * 2] - x, positions[i * 2 + 1] - y)
        if (d < best) {
            nearest = i
            best = d
        }
    }
    return if (nearest < 0) null else nearest to best
}

private fun distanceToEdges(positions: FloatArray, edges: List<MeshElement.Edge>, x: Float, y: Float): Float {
    var best = Float.MAX_VALUE
    for (edge in edges) {
        val a = edge.endpointLow
        val b = edge.endpointHigh
        if (a * 2 + 1 >= positions.size || b * 2 + 1 >= positions.size) continue
        val ax = positions[a * 2]
        val ay = positions[a * 2 + 1]
        val abx = positions[b * 2] - ax
        val aby = positions[b * 2 + 1] - ay
        val len2 = abx * abx + aby * aby
        val t = if (len2 < 1e-12f) 0f else (((x - ax) * abx + (y - ay) * aby) / len2).coerceIn(0f, 1f)
        val d = hypot(ax + abx * t - x, ay + aby * t - y)
        if (d < best) best = d
    }
    return best
}

private fun pointInsideMesh(x: Float, y: Float, positions: FloatArray, indices: IntArray): Boolean {
    for (triangle in 0 until indices.size / 3) {
        val corners = IntArray(3) { indices[triangle * 3 + it] }
        if (corners.any { it * 2 + 1 >= positions.size }) continue
        val weights = barycentric(positions, corners, x, y) ?: continue
        if (weights.all { it >= -1e-5f }) return true
    }
    return false
}

private fun barycentric(positions: FloatArray, corners: IntArray, x: Float, y: Float): FloatArray? {
    val ax = positions[corners[0] * 2]
    val ay = positions[corners[0] * 2 + 1]
    val bx = positions[corners[1] * 2]
    val by = positions[corners[1] * 2 + 1]
    val cx = positions[corners[2] * 2]
    val cy = positions[corners[2] * 2 + 1]
    val denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if (abs(denominator) < 1e-12f) return null
    val wa = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator
    val wb = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator
    return floatArrayOf(wa, wb, 1f - wa - wb)
}

private fun signedArea(positions: FloatArray, corners: IntArray): Float {
    val ax = positions[corners[0] * 2]
    val ay = positions[corners[0] * 2 + 1]
    return ((positions[corners[1] * 2] - ax) * (positions[corners[2] * 2 + 1] - ay) -
        (positions[corners[1] * 2 + 1] - ay) * (positions[corners[2] * 2] - ax)) * 0.5f
}
