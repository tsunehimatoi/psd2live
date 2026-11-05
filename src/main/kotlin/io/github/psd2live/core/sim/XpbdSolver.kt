package io.github.psd2live.core.sim

import io.github.psd2live.core.fastAtan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A 2D XPBD solver ("small steps": many substeps, one constraint pass each, Macklin et al. 2019).
 *
 * Space is the evaluator's world space: canvas px with y negated, so y points up and gravity is -y. Everything is a flat
 * FloatArray / IntArray and every loop runs in a fixed order: two runs from the same state are bit-identical,
 * which the bake relies on to replay. Each substep sweeps the coupled constraints the other way round from
 * the one before, so Gauss-Seidel's order does not pull the body to one side.
 *
 * The rig moves the scene through two per-frame targets the caller writes before [XpbdSolver.step]:
 * - [SimState.anchorX]/[SimState.anchorY]: where each pinned particle's anchor is now (the pin follows it);
 * - [SimState.goalX]/[SimState.goalY]: the rest shape carried by the rig to this pose. The goal spring pulls
 *   toward it, and it is the rest shape of every edge, triangle and bend: what the rig reshapes on purpose
 *   the material takes on, instead of fighting it with the default pose's lengths.
 * Both are interpolated across the substeps from the previous frame's values, so a fast rig motion does not
 * arrive as one jump. So are the [SimState.colliders] the particles keep out of.
 */

/**
 * A circle at [ax], [ay] of [radiusA], or a capsule from there to [bx], [by] whose radius runs to [radiusB], placed
 * in world space for one frame. [friction] 0..1 is the share of a touching particle's slide it takes away.
 */
class PlacedCollider(val ax: Float, val ay: Float, val bx: Float, val by: Float, val radiusA: Float, val radiusB: Float, val friction: Float) {
    fun lerp(to: PlacedCollider, t: Float): PlacedCollider {
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return PlacedCollider(mix(ax, to.ax), mix(ay, to.ay), mix(bx, to.bx), mix(by, to.by), to.radiusA, to.radiusB, to.friction)
    }
}

/** Particle state. Positions are world px (y up); [invMass] 0 is kinematic. */
class SimState(val count: Int) {
    internal class Snapshot(val arrays: List<FloatArray>, val angle: Float, val lastAngle: Float,
                            val colliders: List<PlacedCollider>, val lastColliders: List<PlacedCollider>)
    private fun arrays() = listOf(x, y, vx, vy, invMass, damping, windFactor, anchorX, anchorY, goalX, goalY,
        goalOffsetX, goalOffsetY, px, py, lastAnchorX, lastAnchorY, lastGoalX, lastGoalY, stepAnchorX, stepAnchorY, stepGoalX, stepGoalY)
    internal fun snapshot() = Snapshot(arrays().map { it.copyOf() }, frameAngle, lastFrameAngle, colliders, lastColliders)
    internal fun restore(snapshot: Snapshot) {
        arrays().zip(snapshot.arrays).forEach { (target, saved) -> saved.copyInto(target) }
        frameAngle = snapshot.angle; lastFrameAngle = snapshot.lastAngle
        colliders = snapshot.colliders; lastColliders = snapshot.lastColliders
    }
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val invMass = FloatArray(count) { 1f }
    /** Per-particle damping rate (1/s) and force-field factor. */
    val damping = FloatArray(count)
    val windFactor = FloatArray(count) { 1f }
    val anchorX = FloatArray(count)
    val anchorY = FloatArray(count)
    val goalX = FloatArray(count)
    val goalY = FloatArray(count)
    /**
     * Rest-frame offset added to each goal, turned with the body: the pre-stress that makes the drawn shape
     * the equilibrium under gravity (see SimScene.calibrate). Zero for a shape gravity already holds.
     */
    val goalOffsetX = FloatArray(count)
    val goalOffsetY = FloatArray(count)
    /** How far the body's frame has turned from rest this frame, radians; goal offsets turn with it. */
    var frameAngle = 0f
    internal var lastFrameAngle = 0f
    internal val px = FloatArray(count)
    internal val py = FloatArray(count)
    internal val lastAnchorX = FloatArray(count)
    internal val lastAnchorY = FloatArray(count)
    internal val lastGoalX = FloatArray(count)
    internal val lastGoalY = FloatArray(count)
    internal val stepAnchorX = FloatArray(count)
    internal val stepAnchorY = FloatArray(count)
    internal val stepGoalX = FloatArray(count)
    internal val stepGoalY = FloatArray(count)
    /** Where the colliders are this frame; the caller writes them with the goals. */
    var colliders: List<PlacedCollider> = emptyList()
    internal var lastColliders: List<PlacedCollider> = emptyList()
    internal var stepColliders: List<PlacedCollider> = emptyList()

    /** Places every particle at [positions] at rest, with anchors and goals there too. */
    fun reset(positions: FloatArray) {
        require(positions.size == count * 2) { "Expected ${count * 2} coordinates" }
        for (i in 0 until count) {
            x[i] = positions[i * 2]; y[i] = positions[i * 2 + 1]
            vx[i] = 0f; vy[i] = 0f
            anchorX[i] = x[i]; anchorY[i] = y[i]; goalX[i] = x[i]; goalY[i] = y[i]
            lastAnchorX[i] = x[i]; lastAnchorY[i] = y[i]; lastGoalX[i] = x[i]; lastGoalY[i] = y[i]
            stepGoalX[i] = x[i]; stepGoalY[i] = y[i]
        }
        frameAngle = 0f; lastFrameAngle = 0f
    }

    /**
     * Takes the current anchors and goals as the last frame's, and puts fully pinned particles on their
     * anchors, so the next step starts from rest instead of sweeping in from where [reset] left them.
     */
    fun settle() {
        for (i in 0 until count) {
            lastAnchorX[i] = anchorX[i]; lastAnchorY[i] = anchorY[i]
            lastGoalX[i] = goalX[i]; lastGoalY[i] = goalY[i]
        }
        lastFrameAngle = frameAngle
        lastColliders = colliders
    }

    fun positions(): FloatArray = FloatArray(count * 2) { if (it % 2 == 0) x[it / 2] else y[it / 2] }
}

/**
 * Distance constraints `|a - b| = rest`, with one compliance when stretched and another when compressed
 * (XPBD compliance; 0 is rigid). [rest] is the length at the default pose; the solver measures the live
 * rest length on the goals.
 *
 * The split is what lets a 2D mesh fold at all: seen from the front, cloth and hair fold and foreshorten in
 * depth but never grow longer, so edges resist stretching hard and compression only softly. How far a
 * triangle may shrink and how sharply the sheet may turn are [TriangleConstraints] and [BendConstraints].
 */
class DistanceConstraints(
    val a: IntArray,
    val b: IntArray,
    val rest: FloatArray,
    val compliance: FloatArray,
    val compressionCompliance: FloatArray = compliance,
) {
    init { require(a.size == b.size && b.size == rest.size && rest.size == compliance.size && compliance.size == compressionCompliance.size) }
    val size: Int get() = a.size

    companion object {
        val Empty = DistanceConstraints(IntArray(0), IntArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Triangles of the mesh, wound as the mesh winds them. Each keeps its signed area from collapsing below
 * [MIN_AREA] of the rest area, so it never turns over - always and nearly rigidly - and holds its area
 * with [areaCompliance] ([Float.POSITIVE_INFINITY] is none): a heavy part keeps its shape, a light cloth
 * may bunch up.
 */
class TriangleConstraints(val a: IntArray, val b: IntArray, val c: IntArray, val areaCompliance: FloatArray) {
    init { require(a.size == b.size && b.size == c.size && c.size == areaCompliance.size) }
    val size: Int get() = a.size

    companion object {
        /** Below this share of its rest area a triangle is pushed back out. */
        const val MIN_AREA = 0.2f
        val Empty = TriangleConstraints(IntArray(0), IntArray(0), IntArray(0), FloatArray(0))
    }
}

/**
 * Bending between two triangles [t1] and [t2] (indices into the solver's [TriangleConstraints]) that share
 * an edge: how far each has turned from its rest shape, as the rotation of its deformation gradient, is
 * kept alike. Shrinking or shearing turns neither, so this holds the sheet's curve without resisting the
 * fold that [DistanceConstraints] lets it make.
 */
class BendConstraints(val t1: IntArray, val t2: IntArray, val compliance: FloatArray) {
    init { require(t1.size == t2.size && t2.size == compliance.size) }
    val size: Int get() = t1.size

    companion object {
        val Empty = BendConstraints(IntArray(0), IntArray(0), FloatArray(0))
    }
}

/**
 * Two particles held on one point (a simulated glue pair, or two welded strips). [weightA] / [weightB]
 * are the share of the correction each side takes, as Cubism's glue pulls each side by its own weight.
 */
class WeldConstraints(val a: IntArray, val b: IntArray, val weightA: FloatArray, val weightB: FloatArray, val compliance: FloatArray) {
    init { require(a.size == b.size && b.size == weightA.size && weightA.size == weightB.size && weightB.size == compliance.size) }
    val size: Int get() = a.size

    companion object {
        val Empty = WeldConstraints(IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Long-range attachments (Kim et al. 2012): particle [particle] stays within [maxDistance] of the anchor
 * of pinned particle [root]. It keeps hair and hems from stretching under a hard jerk, which is most of
 * what makes cloth read as cloth.
 */
class LongRangeConstraints(val particle: IntArray, val root: IntArray, val maxDistance: FloatArray) {
    init { require(particle.size == root.size && root.size == maxDistance.size) }
    val size: Int get() = particle.size

    companion object {
        val Empty = LongRangeConstraints(IntArray(0), IntArray(0), FloatArray(0))
    }
}

/** Global settings of one solve. */
data class SimSettings(
    /** Gravity in px/s², world space (y up). */
    val gravityX: Float = 0f,
    val gravityY: Float = -980f,
    /** Uniform wind acceleration in px/s², scaled per particle by [SimState.windFactor]. */
    val windX: Float = 0f,
    val windY: Float = 0f,
    val substeps: Int = 16,
    /** Compliance of a pin with weight 1 is 0 (rigid); a softer pin scales this by (1 - w) / w per unit mass. */
    val pinCompliance: Float = 1e-4f,
) {
    init {
        require(substeps in 1..128) { "Substeps must be within 1..128" }
        require(listOf(gravityX, gravityY, windX, windY, pinCompliance).all(Float::isFinite))
        require(pinCompliance >= 0f)
    }
}

/**
 * The solver. [pinWeight] and [goalCompliance] are per particle (0 / [Float.POSITIVE_INFINITY] = none). A
 * pin weight of 1 makes the particle kinematic on its anchor; below that the pin is a spring.
 */
class XpbdSolver(
    val state: SimState,
    val stretch: DistanceConstraints = DistanceConstraints.Empty,
    val triangles: TriangleConstraints = TriangleConstraints.Empty,
    val bends: BendConstraints = BendConstraints.Empty,
    val welds: WeldConstraints = WeldConstraints.Empty,
    val longRange: LongRangeConstraints = LongRangeConstraints.Empty,
    val pinWeight: FloatArray = FloatArray(state.count),
    /** Compliance toward the goal shape per particle; [Float.POSITIVE_INFINITY] or no entry is none. */
    val goalCompliance: FloatArray = FloatArray(state.count) { Float.POSITIVE_INFINITY },
    var settings: SimSettings = SimSettings(),
) {
    private val n = state.count
    private val effectiveMass = FloatArray(n)
    private val stretchLambda = FloatArray(stretch.size)
    private val areaLambda = FloatArray(triangles.size)
    private val flipLambda = FloatArray(triangles.size)
    private val bendLambda = FloatArray(bends.size)
    private val weldLambda = FloatArray(welds.size * 2)
    private val pinLambda = FloatArray(n * 2)
    private val goalLambda = FloatArray(n * 2)
    /** The live rest shape, measured on the goals each substep. */
    private val restLength = stretch.rest.copyOf()
    private val restArea = FloatArray(triangles.size)
    /** Per triangle, the inverse of its rest edge matrix, row-major (p, q, r, t). */
    private val restInverse = FloatArray(triangles.size * 4)
    /** Scratch for one bend: up to six corners, merged where the triangles share one. */
    private val bendIndex = IntArray(6)
    private val bendGx = FloatArray(6)
    private val bendGy = FloatArray(6)
    private val bendCorners = IntArray(bends.size * 6)
    private val bendSlots = IntArray(bends.size * 6)
    private val bendFirst = BooleanArray(bends.size * 6)
    private val bendCounts = IntArray(bends.size)
    private val turnGrad = FloatArray(6)
    /** The last [turn]'s rotation as an unnormalized (cos, sin): a bend takes one angle between two of them. */
    private var turnCos = 0f
    private var turnSin = 0f

    init {
        require(pinWeight.size == n && goalCompliance.size == n) { "Per-particle arrays must match the particle count" }
        // Triangle connectivity is immutable; merge their shared corners once, not at every substep.
        for (k in 0 until bends.size) {
            val t1 = bends.t1[k]; val t2 = bends.t2[k]
            val corners = intArrayOf(triangles.a[t1], triangles.b[t1], triangles.c[t1], triangles.a[t2], triangles.b[t2], triangles.c[t2])
            val unique = corners.distinct()
            bendCounts[k] = unique.size
            unique.forEachIndexed { j, vertex -> bendCorners[k * 6 + j] = vertex }
            corners.forEachIndexed { j, vertex ->
                bendSlots[k * 6 + j] = unique.indexOf(vertex)
                bendFirst[k * 6 + j] = corners.indexOf(vertex) == j
            }
        }
    }

    /** Pinned particles are those with a full pin; they move only with their anchor. */
    private fun kinematic(i: Int) = effectiveMass[i] == 0f

    private fun mobility(i: Int) = effectiveMass[i]

    /** Advances one frame of [dt] seconds. */
    fun step(dt: Float, checkpoint: () -> Unit = {}) {
        require(dt.isFinite() && dt > 0f) { "Frame time must be positive" }
        val s = state
        val substeps = settings.substeps
        val h = dt / substeps
        // Pin weights and masses stay fixed during a solve. Refresh for every frame so interactive
        // weight edits and restored training snapshots are observed without per-constraint branches.
        for (i in 0 until n) effectiveMass[i] = if (pinWeight[i] >= 0.999f) 0f else s.invMass[i]
        for (sub in 1..substeps) {
            checkpoint()
            val t = sub.toFloat() / substeps
            for (i in 0 until n) {
                s.stepAnchorX[i] = s.lastAnchorX[i] + (s.anchorX[i] - s.lastAnchorX[i]) * t
                s.stepAnchorY[i] = s.lastAnchorY[i] + (s.anchorY[i] - s.lastAnchorY[i]) * t
                s.stepGoalX[i] = s.lastGoalX[i] + (s.goalX[i] - s.lastGoalX[i]) * t
                s.stepGoalY[i] = s.lastGoalY[i] + (s.goalY[i] - s.lastGoalY[i]) * t
            }
            s.stepColliders = s.colliders.mapIndexed { k, c -> s.lastColliders.getOrNull(k)?.lerp(c, t) ?: c }
            val angle = s.lastFrameAngle + (s.frameAngle - s.lastFrameAngle) * t
            val reverse = sub % 2 == 0
            measureRest()
            integrate(h)
            stretchLambda.fill(0f); areaLambda.fill(0f); flipLambda.fill(0f); bendLambda.fill(0f)
            weldLambda.fill(0f); pinLambda.fill(0f); goalLambda.fill(0f)
            solvePins(h)
            solveDistances(h, reverse)
            checkpoint()
            solveTriangles(h, reverse)
            solveBends(h, reverse)
            solveWelds(h, reverse)
            checkpoint()
            solveGoals(h, angle)
            solveCollisions()
            solveLongRange(reverse)
            // Goals and long-range limits pull particles after the edges are done: one more sweep the
            // other way puts the length back, which is what keeps hair from stretching under a hard drag.
            stretchLambda.fill(0f)
            solveDistances(h, !reverse)
            updateVelocities(h)
        }
        s.settle()
        checkpoint()
    }

    /** The rest shape this substep: lengths, areas and edge matrices of the goals. */
    private fun measureRest() {
        val s = state
        for (k in 0 until stretch.size) {
            val dx = s.stepGoalX[stretch.a[k]] - s.stepGoalX[stretch.b[k]]
            val dy = s.stepGoalY[stretch.a[k]] - s.stepGoalY[stretch.b[k]]
            // What the rig lengthens the material takes on; what it shortens is the part turning away in
            // depth, which the soft compression already gives. Taken as a new rest length instead, it would
            // be pulled in at once by the stiff stretch and ring.
            restLength[k] = maxOf(stretch.rest[k], sqrt(dx * dx + dy * dy))
        }
        for (k in 0 until triangles.size) {
            val a = triangles.a[k]; val b = triangles.b[k]; val c = triangles.c[k]
            val m00 = s.stepGoalX[b] - s.stepGoalX[a]; val m01 = s.stepGoalX[c] - s.stepGoalX[a]
            val m10 = s.stepGoalY[b] - s.stepGoalY[a]; val m11 = s.stepGoalY[c] - s.stepGoalY[a]
            val det = m00 * m11 - m01 * m10
            restArea[k] = det / 2f
            val o = k * 4
            if (det * det < 1e-12f) { restInverse[o] = 0f; restInverse[o + 1] = 0f; restInverse[o + 2] = 0f; restInverse[o + 3] = 0f; continue }
            restInverse[o] = m11 / det; restInverse[o + 1] = -m01 / det
            restInverse[o + 2] = -m10 / det; restInverse[o + 3] = m00 / det
        }
    }

    private fun integrate(h: Float) {
        val s = state
        val g = settings
        for (i in 0 until n) {
            s.px[i] = s.x[i]; s.py[i] = s.y[i]
            if (kinematic(i)) {
                s.x[i] = s.stepAnchorX[i]; s.y[i] = s.stepAnchorY[i]
                continue
            }
            val decay = exp(-s.damping[i] * h)
            s.vx[i] = (s.vx[i] + (g.gravityX + g.windX * s.windFactor[i]) * h) * decay
            s.vy[i] = (s.vy[i] + (g.gravityY + g.windY * s.windFactor[i]) * h) * decay
            s.x[i] += s.vx[i] * h
            s.y[i] += s.vy[i] * h
        }
    }

    private fun solveDistances(h: Float, reverse: Boolean) {
        val s = state
        val c = stretch
        val h2 = h * h
        for (step in 0 until c.size) {
            val k = if (reverse) c.size - 1 - step else step
            val a = c.a[k]
            val b = c.b[k]
            val wa = mobility(a)
            val wb = mobility(b)
            val w = wa + wb
            if (w == 0f) continue
            val dx = s.x[a] - s.x[b]
            val dy = s.y[a] - s.y[b]
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1e-9f) continue
            val constraint = length - restLength[k]
            val alpha = (if (constraint < 0f) c.compressionCompliance[k] else c.compliance[k]) / h2
            val delta = (-constraint - alpha * stretchLambda[k]) / (w + alpha)
            stretchLambda[k] += delta
            val nx = dx / length
            val ny = dy / length
            s.x[a] += wa * delta * nx; s.y[a] += wa * delta * ny
            s.x[b] -= wb * delta * nx; s.y[b] -= wb * delta * ny
        }
    }

    private fun solveTriangles(h: Float, reverse: Boolean) {
        val s = state
        val h2 = h * h
        for (step in 0 until triangles.size) {
            val k = if (reverse) triangles.size - 1 - step else step
            val rest = restArea[k]
            if (rest * rest < 1e-8f) continue
            val sign = if (rest > 0f) 1f else -1f
            val a = triangles.a[k]; val b = triangles.b[k]; val c = triangles.c[k]
            val wa = mobility(a); val wb = mobility(b); val wc = mobility(c)
            if (wa + wb + wc == 0f) continue
            val compliance = triangles.areaCompliance[k]
            if (compliance.isFinite()) {
                area(k, a, b, c, wa, wb, wc, sign, abs(rest), compliance / h2, areaLambda, unilateral = false)
            }
            area(k, a, b, c, wa, wb, wc, sign, abs(rest) * TriangleConstraints.MIN_AREA, 0f, flipLambda, unilateral = true)
        }
    }

    /** Pulls triangle [k]'s area (signed by [sign]) toward [target]; [unilateral] only pushes it up to it. */
    private fun area(k: Int, a: Int, b: Int, c: Int, wa: Float, wb: Float, wc: Float, sign: Float, target: Float, alpha: Float,
                     lambda: FloatArray, unilateral: Boolean) {
        val s = state
        val xa = s.x[a]; val ya = s.y[a]; val xb = s.x[b]; val yb = s.y[b]; val xc = s.x[c]; val yc = s.y[c]
        val value = sign * ((xb - xa) * (yc - ya) - (xc - xa) * (yb - ya)) / 2f
        val constraint = value - target
        if (unilateral && constraint >= 0f) return
        // d(area)/d corner: half the opposite edge turned a quarter.
        val gax = sign * (yb - yc) / 2f; val gay = sign * (xc - xb) / 2f
        val gbx = sign * (yc - ya) / 2f; val gby = sign * (xa - xc) / 2f
        val gcx = sign * (ya - yb) / 2f; val gcy = sign * (xb - xa) / 2f
        val w = wa * (gax * gax + gay * gay) + wb * (gbx * gbx + gby * gby) + wc * (gcx * gcx + gcy * gcy)
        if (w < 1e-12f) return
        val delta = (-constraint - alpha * lambda[k]) / (w + alpha)
        lambda[k] += delta
        s.x[a] += wa * delta * gax; s.y[a] += wa * delta * gay
        s.x[b] += wb * delta * gbx; s.y[b] += wb * delta * gby
        s.x[c] += wc * delta * gcx; s.y[c] += wc * delta * gcy
    }

    /**
     * How far triangle [k] has turned from its rest shape, as [turnCos] and [turnSin], with the gradient of
     * its angle over its corners a, b, c in [turnGrad] (x, y each); false when it has no rest shape.
     */
    private fun turn(k: Int): Boolean {
        val s = state
        val o = k * 4
        val p = restInverse[o]; val q = restInverse[o + 1]; val r = restInverse[o + 2]; val t = restInverse[o + 3]
        if (p == 0f && q == 0f && r == 0f && t == 0f) return false
        val a = triangles.a[k]; val b = triangles.b[k]; val c = triangles.c[k]
        val e1x = s.x[b] - s.x[a]; val e1y = s.y[b] - s.y[a]
        val e2x = s.x[c] - s.x[a]; val e2y = s.y[c] - s.y[a]
        // F = [e1 e2]·Dm⁻¹; its rotation is atan2(F10 - F01, F00 + F11).
        val cs = e1x * p + e2x * r + e1y * q + e2y * t
        val sn = e1y * p + e2y * r - e1x * q - e2x * t
        val norm = cs * cs + sn * sn
        if (norm < 1e-12f) return false
        // dθ = (cs·d sn - sn·d cs) / (cs² + sn²), both linear in the corners.
        val bx = (cs * -q - sn * p) / norm; val by = (cs * p - sn * q) / norm
        val cx = (cs * -t - sn * r) / norm; val cy = (cs * r - sn * t) / norm
        turnGrad[0] = -(bx + cx); turnGrad[1] = -(by + cy)
        turnGrad[2] = bx; turnGrad[3] = by
        turnGrad[4] = cx; turnGrad[5] = cy
        turnCos = cs; turnSin = sn
        return true
    }

    private fun solveBends(h: Float, reverse: Boolean) {
        val s = state
        val h2 = h * h
        for (step in 0 until bends.size) {
            val k = if (reverse) bends.size - 1 - step else step
            val t1 = bends.t1[k]; val t2 = bends.t2[k]
            val count = bendCounts[k]
            for (j in 0 until count) bendIndex[j] = bendCorners[k * 6 + j]
            if (!turn(t1)) continue
            accumulateBend(k, 0, 1f)
            val c1 = turnCos; val s1 = turnSin
            if (!turn(t2)) continue
            accumulateBend(k, 3, -1f)
            // The first rotation less the second, within ±π: one atan2 of the two (cos, sin) pairs' product
            // instead of one per triangle; the library's atan2 was most of a step's time.
            val constraint = fastAtan2(s1 * turnCos - c1 * turnSin, c1 * turnCos + s1 * turnSin)
            var w = 0f
            for (j in 0 until count) w += mobility(bendIndex[j]) * (bendGx[j] * bendGx[j] + bendGy[j] * bendGy[j])
            if (w < 1e-12f) continue
            val alpha = bends.compliance[k] / h2
            val delta = (-constraint - alpha * bendLambda[k]) / (w + alpha)
            bendLambda[k] += delta
            for (j in 0 until count) {
                val i = bendIndex[j]
                val wi = mobility(i)
                s.x[i] += wi * delta * bendGx[j]; s.y[i] += wi * delta * bendGy[j]
            }
        }
    }

    private fun accumulateBend(k: Int, offset: Int, sign: Float) {
        for (j in 0..2) {
            val at = k * 6 + offset + j
            val slot = bendSlots[at]
            val gx = turnGrad[j * 2] * sign; val gy = turnGrad[j * 2 + 1] * sign
            if (bendFirst[at]) { bendGx[slot] = gx; bendGy[slot] = gy }
            else { bendGx[slot] += gx; bendGy[slot] += gy }
        }
    }

    private fun solveWelds(h: Float, reverse: Boolean) {
        val s = state
        val h2 = h * h
        for (step in 0 until welds.size) {
            val k = if (reverse) welds.size - 1 - step else step
            val a = welds.a[k]
            val b = welds.b[k]
            // Each side's share of the pull, as Cubism weights it, limited by whether it can move at all.
            val wa = mobility(a) * welds.weightA[k]
            val wb = mobility(b) * welds.weightB[k]
            val w = wa + wb
            if (w == 0f) continue
            val alpha = welds.compliance[k] / h2
            for (axis in 0..1) {
                val offset = if (axis == 0) s.x[a] - s.x[b] else s.y[a] - s.y[b]
                val slot = k * 2 + axis
                val delta = (-offset - alpha * weldLambda[slot]) / (w + alpha)
                weldLambda[slot] += delta
                if (axis == 0) { s.x[a] += wa * delta; s.x[b] -= wb * delta } else { s.y[a] += wa * delta; s.y[b] -= wb * delta }
            }
        }
    }

    private fun solvePins(h: Float) {
        val s = state
        val h2 = h * h
        for (i in 0 until n) {
            val weight = pinWeight[i]
            if (weight <= 0f || kinematic(i)) continue
            // Per unit mass, so a soft pin springs back alike however finely the mesh is cut.
            val compliance = settings.pinCompliance * (1f - weight) / weight * s.invMass[i]
            attach(i, s.stepAnchorX[i], s.stepAnchorY[i], compliance / h2, pinLambda)
        }
    }

    private fun solveGoals(h: Float, angle: Float) {
        val s = state
        val h2 = h * h
        val cosA = cos(angle)
        val sinA = sin(angle)
        for (i in 0 until n) {
            val compliance = goalCompliance[i]
            if (!compliance.isFinite() || kinematic(i)) continue
            val ox = s.goalOffsetX[i] * cosA - s.goalOffsetY[i] * sinA
            val oy = s.goalOffsetX[i] * sinA + s.goalOffsetY[i] * cosA
            attach(i, s.stepGoalX[i] + ox, s.stepGoalY[i] + oy, compliance / h2, goalLambda)
        }
    }

    /** A zero-length spring from particle [i] to a moving point, one XPBD update per axis. */
    private fun attach(i: Int, tx: Float, ty: Float, alpha: Float, lambda: FloatArray) {
        val s = state
        val w = s.invMass[i]
        if (w == 0f) return
        val dxDelta = (-(s.x[i] - tx) - alpha * lambda[i * 2]) / (w + alpha)
        lambda[i * 2] += dxDelta
        s.x[i] += w * dxDelta
        val dyDelta = (-(s.y[i] - ty) - alpha * lambda[i * 2 + 1]) / (w + alpha)
        lambda[i * 2 + 1] += dyDelta
        s.y[i] += w * dyDelta
    }

    /**
     * Every free particle pushed out of every collider to its surface, rigidly; a collider with friction then
     * takes that share of the particle's slide along the surface this substep away.
     */
    private fun solveCollisions() {
        val s = state
        if (s.stepColliders.isEmpty()) return
        for (i in 0 until n) {
            if (kinematic(i)) continue
            for (c in s.stepColliders) {
                val ex = c.bx - c.ax; val ey = c.by - c.ay
                val length2 = ex * ex + ey * ey
                val t = if (length2 > 1e-12f) (((s.x[i] - c.ax) * ex + (s.y[i] - c.ay) * ey) / length2).coerceIn(0f, 1f) else 0f
                val cx = c.ax + ex * t; val cy = c.ay + ey * t
                val radius = c.radiusA + (c.radiusB - c.radiusA) * t
                val dx = s.x[i] - cx; val dy = s.y[i] - cy
                val distance = sqrt(dx * dx + dy * dy)
                if (distance >= radius) continue
                val nx = if (distance > 1e-6f) dx / distance else 0f
                val ny = if (distance > 1e-6f) dy / distance else 1f
                val depth = radius - distance
                s.x[i] += nx * depth; s.y[i] += ny * depth
                if (c.friction > 0f) {
                    val mx = s.x[i] - s.px[i]; val my = s.y[i] - s.py[i]
                    val along = mx * -ny + my * nx
                    s.x[i] -= -ny * along * c.friction; s.y[i] -= nx * along * c.friction
                }
            }
        }
    }

    private fun solveLongRange(reverse: Boolean) {
        val s = state
        for (step in 0 until longRange.size) {
            val k = if (reverse) longRange.size - 1 - step else step
            val i = longRange.particle[k]
            if (kinematic(i)) continue
            val r = longRange.root[k]
            val dx = s.x[i] - s.x[r]
            val dy = s.y[i] - s.y[r]
            val length = sqrt(dx * dx + dy * dy)
            val limit = longRange.maxDistance[k]
            if (length <= limit || length < 1e-9f) continue
            val k2 = limit / length
            s.x[i] = s.x[r] + dx * k2
            s.y[i] = s.y[r] + dy * k2
        }
    }

    private fun updateVelocities(h: Float) {
        val s = state
        for (i in 0 until n) {
            s.vx[i] = (s.x[i] - s.px[i]) / h
            s.vy[i] = (s.y[i] - s.py[i]) / h
        }
    }

    /** Largest relative stretch over [stretch] constraints against their live rest length, for tests and the bake report. */
    fun maxStretch(): Float {
        var worst = 0f
        for (k in 0 until stretch.size) {
            val dx = state.x[stretch.a[k]] - state.x[stretch.b[k]]
            val dy = state.y[stretch.a[k]] - state.y[stretch.b[k]]
            val rest = restLength[k]
            if (rest > 1e-6f) worst = max(worst, sqrt(dx * dx + dy * dy) / rest - 1f)
        }
        return worst
    }

    /** The smallest signed area over [triangles] as a share of its rest area (below 0 is turned over), for tests. */
    fun minAreaRatio(): Float {
        var least = Float.MAX_VALUE
        val s = state
        for (k in 0 until triangles.size) {
            val rest = restArea[k]
            if (rest * rest < 1e-8f) continue
            val a = triangles.a[k]; val b = triangles.b[k]; val c = triangles.c[k]
            val value = ((s.x[b] - s.x[a]) * (s.y[c] - s.y[a]) - (s.x[c] - s.x[a]) * (s.y[b] - s.y[a])) / 2f
            least = minOf(least, value / rest)
        }
        return if (least == Float.MAX_VALUE) 1f else least
    }

    /** Triangle [k]'s turn from rest and its gradient over corners a, b, c; for tests. */
    internal fun turnOf(k: Int): Pair<Float, FloatArray> { measureRest(); val angle = if (turn(k)) fastAtan2(turnSin, turnCos) else Float.NaN; return angle to turnGrad.copyOf() }
}
