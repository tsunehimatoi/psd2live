package io.github.psd2live.core.sim

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Fixed spatial quadrature of a loaded cantilever, evaluated independently at each key. */
internal object DirectHairShapes {
    private const val HARD_PIN = 0.5f
    private const val EPS = 1e-4f
    private const val AXIAL_NEUTRAL_RANGE = 0.25
    private const val TURN_NEUTRAL_RANGE = 0.20f
    private val nodes = doubleArrayOf(0.1127016654, 0.5, 0.8872983346)
    private val weights = doubleArrayOf(5.0 / 18.0, 8.0 / 18.0, 5.0 / 18.0)

    private class Strand(
        val vertices: List<Int>,
        val rootX: Float,
        val rootY: Float,
        val reach: Float,
        val linearX: Double,
        val linearY: Double,
        val curveX: Double,
        val curveY: Double,
    )
    private class Response(val hz: Float, val damping: Float)

    fun build(scene: SimScene, keys: FloatArray, check: () -> Unit): List<DirectSpatialMode> {
        val state = scene.state
        val pins = scene.solver.pinWeight
        val links = scene.solver.stretch
        val topology = Components(state.count)
        for (e in links.a.indices) {
            if ((e and 255) == 0) check()
            topology.join(links.a[e], links.b[e])
        }
        val seams = weldedVertices(scene)
        val depth = FloatArray(state.count)
        val distance = scene.solver.longRange
        for (e in distance.particle.indices) {
            depth[distance.particle[e]] = distance.maxDistance[e] / (1f + scene.edit.material.slack)
        }
        val strands = (0 until state.count).groupBy(topology::root).values.map { vertices ->
            check()
            val roots = vertices.filter { pins[it] >= HARD_PIN }.ifEmpty {
                // A goal-held strand may have no painted pins. Its highest painted point is the reference for bending.
                listOf(vertices.maxWith(compareBy({ pins[it] }, { state.goalY[it] })))
            }
            val rootX = roots.map { state.anchorX[it] }.average().toFloat()
            val rootY = roots.map { state.anchorY[it] }.average().toFloat()
            for (i in vertices) {
                if (depth[i] <= EPS && pins[i] < HARD_PIN && i != roots.first()) {
                    depth[i] = hypot(state.goalX[i] - rootX, state.goalY[i] - rootY)
                }
            }
            val length = vertices.maxOf { depth[it] }.coerceAtLeast(1f)
            centerline(state, vertices, depth, rootX, rootY, length)
        }
        return DirectMotionKind.entries.map { kind ->
            check()
            val response = response(scene, strands, depth, kind)
            val smoothTurn = kind == DirectMotionKind.TURN && keys.size == 5
            val neutralRange = when {
                smoothTurn -> TURN_NEUTRAL_RANGE
                kind == DirectMotionKind.COMPRESSION && keys.size == 5 -> AXIAL_NEUTRAL_RANGE.toFloat()
                else -> 0f
            }
            val axisKeys = if (neutralRange > 0f) floatArrayOf(keys.first(), -neutralRange, 0f, neutralRange, keys.last()) else keys
            val forms = axisKeys.mapIndexed { index, key ->
                val offsets = FloatArray(state.count * 2)
                if (key != 0f && (!smoothTurn || index == 0 || index == axisKeys.lastIndex)) {
                    check()
                    for (strand in strands) writeStrand(scene, strand, depth, kind, key, offsets)
                    enforceWelds(offsets, seams, pins)
                }
                offsets
            }
            if (smoothTurn) {
                // The odd component follows one angular tangent. Distribute the even shortening
                // and curl so the three changes of slope have the same magnitude.
                val negative = forms.first()
                val positive = forms.last()
                val angularScale = TURN_NEUTRAL_RANGE * 0.5f
                val curlScale = angularScale / (3f - 2f * TURN_NEUTRAL_RANGE)
                for (i in negative.indices) {
                    if ((i and 255) == 0) check()
                    val turn = (positive[i] - negative[i]) * angularScale
                    val curl = (positive[i] + negative[i]) * curlScale
                    forms[1][i] = curl - turn
                    forms[3][i] = curl + turn
                }
            }
            DirectSpatialMode(kind, axisKeys, forms, response.hz, response.damping)
        }
    }

    /** Area-weighted quadratic through the rest sections of the strand, rooted at its painted support. */
    private fun centerline(state: SimState, vertices: List<Int>, depth: FloatArray, x: Float, y: Float, reach: Float): Strand {
        var m11 = 0.0
        var m12 = 0.0
        var m22 = 0.0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in vertices) {
            val t = (depth[i] / reach).toDouble()
            val w = 1.0 / state.invMass[i].coerceAtLeast(EPS)
            val t2 = t * t
            val rx = state.goalX[i] - x
            val ry = state.goalY[i] - y
            m11 += w * t2
            m12 += w * t2 * t
            m22 += w * t2 * t2
            x1 += w * t * rx
            x2 += w * t2 * rx
            y1 += w * t * ry
            y2 += w * t2 * ry
        }
        // Regularize the fitted curvature when only one free row determines a strand.
        m22 += 0.08 * m11
        val det = m11 * m22 - m12 * m12
        if (det <= 1e-9) return Strand(vertices, x, y, reach, 0.0, -reach.toDouble(), 0.0, 0.0)
        return Strand(vertices, x, y, reach,
            (m22 * x1 - m12 * x2) / det, (m22 * y1 - m12 * y2) / det,
            (m11 * x2 - m12 * x1) / det, (m11 * y2 - m12 * y1) / det)
    }

    private fun writeStrand(scene: SimScene, strand: Strand, depth: FloatArray, kind: DirectMotionKind, key: Float, offsets: FloatArray) {
        val state = scene.state
        val pins = scene.solver.pinWeight
        // Give the turn most of the section's change of orientation. Sway and rebound mainly move
        // the section center, so concurrent channels do not rotate the same curl twice.
        val section = when (kind) {
            DirectMotionKind.SWAY -> 0.38
            DirectMotionKind.TURN -> 1.0
            DirectMotionKind.FOLLOW_THROUGH -> 0.15
            DirectMotionKind.COMPRESSION -> 0.0
        }
        val axial = if (kind == DirectMotionKind.COMPRESSION) axialTravel(key.toDouble(), scene.edit.material.area) else 0.0
        val spreadProgress = ((key - AXIAL_NEUTRAL_RANGE) / (1.0 - AXIAL_NEUTRAL_RANGE)).coerceIn(0.0, 1.0)
        val spreadWeight = spreadProgress * spreadProgress * (3.0 - 2.0 * spreadProgress)
        for (index in strand.vertices) {
            val u = (depth[index] / strand.reach).toDouble().coerceIn(0.0, 1.0)
            val free = (1f - pins[index] / HARD_PIN).coerceIn(0f, 1f)
            if (u <= EPS || free <= 0f) continue
            val fade = free * free * (3f - 2f * free)
            val centerX = u * (strand.linearX + u * strand.curveX)
            val centerY = u * (strand.linearY + u * strand.curveY)
            val crossX = state.goalX[index] - strand.rootX - centerX
            val crossY = state.goalY[index] - strand.rootY - centerY
            val dx: Double
            val dy: Double
            if (kind == DirectMotionKind.COMPRESSION) {
                val shorten = axial * u * u
                val compressed = u * (1.0 - shorten)
                val spread = 1.4 * shorten * (1.0 - 0.5 * u) * spreadWeight
                dx = compressed * (strand.linearX + compressed * strand.curveX) - centerX + crossX * spread
                dy = compressed * (strand.linearY + compressed * strand.curveY) - centerY + crossY * spread
            } else {
                var centerShiftX = 0.0
                var centerShiftY = 0.0
                for (k in nodes.indices) {
                    val s = u * nodes[k]
                    val tangentX = strand.linearX + 2.0 * s * strand.curveX
                    val tangentY = strand.linearY + 2.0 * s * strand.curveY
                    val a = angle(kind, key, s, tangentX, tangentY)
                    val c = cos(a) - 1.0
                    val sine = sin(a)
                    centerShiftX += u * weights[k] * (c * tangentX - sine * tangentY)
                    centerShiftY += u * weights[k] * (sine * tangentX + c * tangentY)
                }
                val tangentX = strand.linearX + 2.0 * u * strand.curveX
                val tangentY = strand.linearY + 2.0 * u * strand.curveY
                val a = section * angle(kind, key, u, tangentX, tangentY)
                val c = cos(a) - 1.0
                val sine = sin(a)
                // As a descending lock rolls across the image plane its projected length shortens.
                // The inner side bunches more while the outer edge opens around its own section.
                val outer = if (kind == DirectMotionKind.TURN) {
                    val normal = (-tangentY * crossX + tangentX * crossY) / hypot(tangentX, tangentY).coerceAtLeast(1e-6)
                    val reach = ((kotlin.math.abs(normal) / strand.reach - 0.08) / 0.28).coerceIn(0.0, 1.0)
                    Math.copySign(reach * reach * (3.0 - 2.0 * reach), normal) * sine
                } else 0.0
                val inset = if (kind == DirectMotionKind.TURN) -u * c * (0.88 - 0.28 * outer) else 0.0
                val breadth = if (kind == DirectMotionKind.TURN) 1.0 + 0.18 * outer * -c else 1.0
                val folded = u - inset
                dx = centerShiftX + (c * crossX - sine * crossY) * breadth +
                    (breadth - 1.0) * crossX + folded * (strand.linearX + folded * strand.curveX) - centerX
                dy = centerShiftY + (sine * crossX + c * crossY) * breadth +
                    (breadth - 1.0) * crossY + folded * (strand.linearY + folded * strand.curveY) - centerY
            }
            offsets[2 * index] = (fade * dx).toFloat()
            offsets[2 * index + 1] = (fade * dy).toFloat()
        }
    }

    /** Exact rest tangent on both sides, followed by a monotone cubic to the asymmetric endpoints. */
    private fun axialTravel(key: Double, area: Float): Double {
        val soft = 1.0 - area
        val contraction = 0.095 + 0.045 * soft
        val release = 0.037 + 0.018 * soft
        val neutralSlope = (contraction + release) * 0.5
        val reach = kotlin.math.abs(key)
        if (reach <= AXIAL_NEUTRAL_RANGE) return key * neutralSlope
        val outer = if (key > 0.0) contraction else release
        val interval = 1.0 - AXIAL_NEUTRAL_RANGE
        val inner = AXIAL_NEUTRAL_RANGE * neutralSlope
        val t = (reach - AXIAL_NEUTRAL_RANGE) / interval
        val blend = t * t * (3.0 - 2.0 * t)
        val fromSlope = t * (1.0 - t) * (1.0 - t) * interval * neutralSlope
        val toSlope = t * t * (t - 1.0) * (outer - inner) * 0.5
        return Math.copySign(inner + (outer - inner) * blend + fromSlope + toSlope, key)
    }

    private fun angle(kind: DirectMotionKind, key: Float, t: Double, tangentX: Double, tangentY: Double): Double {
        // Lateral acceleration primarily excites bending in sections oriented across the load.
        val vertical = -tangentY / hypot(tangentX, tangentY).coerceAtLeast(1e-6)
        val amplitude = when (kind) {
            DirectMotionKind.SWAY -> 0.31 * vertical
            DirectMotionKind.TURN -> 0.72
            DirectMotionKind.FOLLOW_THROUGH -> 0.53 * vertical
            DirectMotionKind.COMPRESSION -> error("Compression uses axial travel")
        }
        return key * amplitude * profile(kind, t.toFloat())
    }

    private fun profile(kind: DirectMotionKind, t: Float): Float = when (kind) {
        // Bernoulli beam slope for a uniform transverse load, normalized at the tip.
        DirectMotionKind.SWAY -> t * (3f - t * (3f - t))
        // The scalp holds the tangent while the loose end can continue turning as the head changes direction.
        DirectMotionKind.TURN -> t * t * (3f - 2f * t)
        // Slope of t²(t - 0.7), normalized at the tip. The clamped root has zero offset and slope.
        // Its small inner counterbend leaves the loose tip to finish a reversal on its own.
        DirectMotionKind.FOLLOW_THROUGH -> t * (3f * t - 1.4f) / 1.6f
        DirectMotionKind.COMPRESSION -> t * t
    }

    private fun response(scene: SimScene, strands: List<Strand>, depth: FloatArray, kind: DirectMotionKind): Response {
        val state = scene.state
        val pins = scene.solver.pinWeight
        val gravity = hypot(scene.solver.settings.gravityX, scene.solver.settings.gravityY)
        var inertia = 0.0
        var stiffness = 0.0
        var drag = 0.0
        for (strand in strands) for (i in strand.vertices) {
            val inverseMass = state.invMass[i].toDouble()
            if (inverseMass <= 0.0 || pins[i] >= HARD_PIN || depth[i] <= EPS) continue
            val alpha = profile(kind, (depth[i] / strand.reach).coerceIn(0f, 1f)).toDouble()
            val radius = depth[i].toDouble()
            val arm = radius * alpha
            val moment = arm * arm / inverseMass
            inertia += moment
            val height = (strand.rootY - state.goalY[i]).coerceAtLeast(0f)
            // Gravity tension provides angular stiffness. The rig's goal springs provide the foundation stiffness.
            stiffness += moment * gravity * height / (radius * radius)
            val compliance = scene.solver.goalCompliance[i].toDouble()
            if (compliance.isFinite() && compliance > 0.0) stiffness += arm * arm / compliance
            drag += moment * state.damping[i]
        }
        val foundation = if (inertia > 0.0) stiffness / inertia else 0.0
        val compression = if (kind == DirectMotionKind.COMPRESSION) {
            1.0 / SimScene.compressionCompliance(scene.edit.material.area)
        } else {
            0.0
        }
        val radians = sqrt(foundation + compression).coerceIn(2.2, 19.0)
        val decayRate = if (inertia > 0.0) drag / inertia else scene.edit.material.damping.toDouble()
        return Response((radians / (2.0 * PI)).toFloat(), (decayRate / (2.0 * radians)).coerceIn(0.04, 0.75).toFloat())
    }

    private fun weldedVertices(scene: SimScene): List<List<Int>> {
        val links = scene.solver.welds
        val vertices = LinkedHashSet<Int>()
        val welds = Components(scene.state.count)
        for (i in links.a.indices) {
            welds.join(links.a[i], links.b[i])
            vertices += links.a[i]
            vertices += links.b[i]
        }
        return vertices.groupBy(welds::root).values.toList()
    }

    /** Equal displacements keep every welded seam continuous, including junctions of several pieces. */
    private fun enforceWelds(offsets: FloatArray, seams: List<List<Int>>, pins: FloatArray) {
        for (vertices in seams) {
            val fixed = vertices.any { pins[it] >= HARD_PIN }
            val dx = if (fixed) 0f else vertices.map { offsets[2 * it] }.average().toFloat()
            val dy = if (fixed) 0f else vertices.map { offsets[2 * it + 1] }.average().toFloat()
            for (i in vertices) {
                offsets[2 * i] = dx
                offsets[2 * i + 1] = dy
            }
        }
    }

    private class Components(count: Int) {
        private val parent = IntArray(count) { it }
        fun root(vertex: Int): Int {
            var i = vertex
            while (parent[i] != i) {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }

        fun join(a: Int, b: Int) {
            parent[root(b)] = root(a)
        }
    }
}
