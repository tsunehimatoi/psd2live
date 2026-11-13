package io.github.psd2live.core.sim

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/*
 * A simulation scene built from the rig: one particle per vertex of every target mesh, in target order.
 *
 * The rig drives it through [SimScene.drive]: each frame the model is evaluated at the frame's pose, and
 * - every vertex's own evaluated position is its goal (the rest shape carried by the rig);
 * - a PIN-group vertex is anchored on that same position;
 * - a vertex glued with role PIN is anchored on its partner's evaluated position, the other mesh's
 *   actual deformation.
 */

/** Where particle anchors come from: the particle's own rig position, or another mesh's vertex. */
private class Anchor(val drawable: DrawableId, val vertex: Int)

class SimScene private constructor(
    val edit: RigSimEdit,
    val solver: XpbdSolver,
    /** Particle offset of each target mesh. */
    val offsets: Map<DrawableId, Int>,
    val vertexCounts: Map<DrawableId, Int>,
    private val anchors: Array<Anchor?>,
    /** Why a part of the setup was skipped; shown in the panel. */
    val notes: List<String>,
    /** Per stretch edge, how much it runs along the grain, 0..1: for measuring how the body bunches up. */
    val edgeAlong: FloatArray,
) {
    private val evaluator = CpuDeformationEvaluator()
    private var posedModel: PuppetModel? = null
    private var posedParameters: Map<ParameterId, Float> = emptyMap()
    private var posedWorld: Map<DrawableId, FloatArray>? = null

    private fun world(model: PuppetModel, pose: Map<ParameterId, Float>): Map<DrawableId, FloatArray> {
        if (posedModel === model && posedParameters == pose) posedWorld?.let { return it }
        return evaluator.evaluate(model, pose).worldPositions.also {
            posedModel = model
            posedParameters = pose.toMap()
            posedWorld = it
        }
    }
    /** Pinned particles that define the body's frame, and where their anchors sit at rest. */
    private val frameParticles = (0 until solver.state.count).filter { solver.pinWeight[it] >= 0.5f }.toIntArray()
    private var frameRest = FloatArray(0)

    val state: SimState get() = solver.state
    class Snapshot(val particles: SimState.Snapshot, val frameRest: FloatArray)
    fun snapshot() = Snapshot(state.snapshot(), frameRest.copyOf())
    fun restore(snapshot: Snapshot) { state.restore(snapshot.particles); frameRest = snapshot.frameRest.copyOf() }

    /**
     * Moves the rig to [pose] and advances [dt] seconds. Returns false when a target mesh is hidden at this
     * pose, in which case nothing moves.
     */
    fun drive(model: PuppetModel, pose: Map<ParameterId, Float>, dt: Float, checkpoint: () -> Unit = {}): Boolean {
        checkpoint()
        val world = world(model, pose)
        if (!place(world)) return false
        solver.step(dt, checkpoint)
        return true
    }

    /** Puts every particle at rest at [pose], with no motion. */
    fun reset(model: PuppetModel, pose: Map<ParameterId, Float>) {
        val world = world(model, pose)
        val rest = FloatArray(state.count * 2)
        for ((id, offset) in offsets) {
            val positions = world[id] ?: continue
            positions.copyInto(rest, offset * 2, 0, minOf(positions.size, vertexCounts.getValue(id) * 2))
        }
        state.reset(rest)
        place(world)
        frameRest = FloatArray(frameParticles.size * 2) { if (it % 2 == 0) state.anchorX[frameParticles[it / 2]] else state.anchorY[frameParticles[it / 2]] }
        state.frameAngle = 0f
        state.settle()
    }

    /**
     * Makes the drawn shape the equilibrium at [pose]: settles under gravity, then moves each goal's
     * offset by what is left between rest and where the vertex settled, a few times over. Vertices gravity
     * already holds (a taut hanging strand) end with no offset and swing as a pure pendulum; a flared hem
     * or a strand drawn sideways ends pre-stressed on its goal. Needs a goal to hold anything: with goal 0
     * the body is pure physics and settles where gravity takes it.
     *
     * Returns the largest distance from rest left after the last pass, in px.
     */
    fun calibrate(model: PuppetModel, pose: Map<ParameterId, Float> = emptyMap(), passes: Int = 6, frames: Int = 90,
                  progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Float {
        val s = state
        s.goalOffsetX.fill(0f); s.goalOffsetY.fill(0f)
        val calm = s.damping.copyOf()
        var residual = 0f
        var lastEnergy = 0.0
        try {
            for (i in 0 until s.count) s.damping[i] = maxOf(calm[i], 8f)
            repeat(passes) { pass ->
                if (cancelled()) throw java.util.concurrent.CancellationException("Simulation calibration cancelled")
                reset(model, pose)
                val rest = s.positions()
                repeat(frames) { frame ->
                    if (cancelled()) throw java.util.concurrent.CancellationException("Simulation calibration cancelled")
                    drive(model, pose, 1f / 60f) {
                        if (cancelled()) throw java.util.concurrent.CancellationException("Simulation calibration cancelled")
                    }
                    progress((pass.toLong() * frames + frame + 1).toFloat() / (passes.toLong() * frames).coerceAtLeast(1))
                }
                // A pass corrects only part of what is left (the body has not quite settled), the same part each
                // time, so what is left shrinks geometrically: the step takes the whole series at that ratio.
                var energy = 0.0
                for (i in 0 until s.count) {
                    if (!solver.goalCompliance[i].isFinite()) continue
                    val dx = (rest[i * 2] - s.x[i]).toDouble(); val dy = (rest[i * 2 + 1] - s.y[i]).toDouble()
                    energy += dx * dx + dy * dy
                }
                // The ratio is read off two plain passes in a row, and only while what is left is more than the body's
                // own unrest, which no step removes.
                var farthest = 0f
                for (i in 0 until s.count) if (solver.goalCompliance[i].isFinite()) farthest = maxOf(farthest, hypot(rest[i * 2] - s.x[i], rest[i * 2 + 1] - s.y[i]))
                val ratio = if (lastEnergy > 0.0) kotlin.math.sqrt(energy / lastEnergy) else 0.0
                val gain = if (ratio in 0.05..MAX_RATIO && farthest > CALIBRATION_NOISE_PX) minOf(MAX_GAIN, 1.0 / (1.0 - ratio)).toFloat() else 1f
                lastEnergy = if (gain == 1f) energy else 0.0
                residual = 0f
                for (i in 0 until s.count) {
                    if (!solver.goalCompliance[i].isFinite()) continue
                    val dx = rest[i * 2] - s.x[i]
                    val dy = rest[i * 2 + 1] - s.y[i]
                    s.goalOffsetX[i] += dx * gain; s.goalOffsetY[i] += dy * gain
                    residual = maxOf(residual, hypot(dx, dy))
                }
            }
        } finally {
            calm.copyInto(s.damping)
            reset(model, pose)
        }
        return residual
    }

    /** Takes [calibrated]'s goal offsets (a scene built from the same rig and edit) instead of calibrating again. */
    fun adopt(calibrated: SimScene) {
        require(calibrated.state.count == state.count) { "Scenes of different bodies" }
        calibrated.state.goalOffsetX.copyInto(state.goalOffsetX)
        calibrated.state.goalOffsetY.copyInto(state.goalOffsetY)
    }

    /** Writes goals, anchors and colliders for this frame. */
    private fun place(world: Map<DrawableId, FloatArray>): Boolean {
        val s = state
        s.colliders = edit.colliders.mapNotNull { c ->
            val positions = world[DrawableId(c.mesh)] ?: return@mapNotNull null
            if (maxOf(c.a, c.b) * 2 + 1 >= positions.size) return@mapNotNull null
            PlacedCollider(positions[c.a * 2], positions[c.a * 2 + 1], positions[c.b * 2], positions[c.b * 2 + 1], c.radius, c.radiusB, c.friction)
        }
        for ((id, offset) in offsets) {
            val positions = world[id] ?: return false
            for (v in 0 until vertexCounts.getValue(id)) {
                val i = offset + v
                s.goalX[i] = positions[v * 2]; s.goalY[i] = positions[v * 2 + 1]
                val anchor = anchors[i]
                val source = if (anchor == null) positions else world[anchor.drawable]
                val index = anchor?.vertex ?: v
                if (source != null && index * 2 + 1 < source.size) {
                    s.anchorX[i] = source[index * 2]; s.anchorY[i] = source[index * 2 + 1]
                } else {
                    s.anchorX[i] = s.goalX[i]; s.anchorY[i] = s.goalY[i]
                }
            }
        }
        s.frameAngle = frameAngle()
        return true
    }

    /** The best-fit rotation of the pinned anchors from rest to now (2D Procrustes about the centroids). */
    private fun frameAngle(): Float {
        val s = state
        if (frameParticles.size < 2 || frameRest.size != frameParticles.size * 2) return 0f
        var rx = 0f; var ry = 0f; var cx = 0f; var cy = 0f
        for ((k, i) in frameParticles.withIndex()) { rx += frameRest[k * 2]; ry += frameRest[k * 2 + 1]; cx += s.anchorX[i]; cy += s.anchorY[i] }
        val count = frameParticles.size.toFloat()
        rx /= count; ry /= count; cx /= count; cy /= count
        var dot = 0f; var cross = 0f
        for ((k, i) in frameParticles.withIndex()) {
            val ax = frameRest[k * 2] - rx; val ay = frameRest[k * 2 + 1] - ry
            val bx = s.anchorX[i] - cx; val by = s.anchorY[i] - cy
            dot += ax * bx + ay * by; cross += ax * by - ay * bx
        }
        return if (dot == 0f && cross == 0f) 0f else kotlin.math.atan2(cross, dot)
    }

    /** The mesh and vertex particle [i]'s pin follows, or null for its own position. */
    fun anchorOf(i: Int): Pair<DrawableId, Int>? = anchors[i]?.let { it.drawable to it.vertex }

    /** The simulated vertices of [id], world space. */
    fun positions(id: DrawableId): FloatArray? {
        val offset = offsets[id] ?: return null
        val count = vertexCounts.getValue(id)
        return FloatArray(count * 2) { if (it % 2 == 0) state.x[offset + it / 2] else state.y[offset + it / 2] }
    }

    companion object {
        /** Pin weights at or above this hold the particle outright, and root the long-range limits. */
        private const val ROOT_PIN = 0.5f
        /** Past this ratio between passes the calibration is not shrinking steadily and takes its plain step. */
        private const val MAX_RATIO = 0.95
        /** The most one calibration step is stretched by. */
        private const val MAX_GAIN = 4.0
        /** Below this (px) what calibration leaves is the body's own unrest, not worth extrapolating. */
        private const val CALIBRATION_NOISE_PX = 1f
        /** The mesh scale the material values are tuned at: the default mesh spacing, and the area one vertex carries there. */
        internal const val REFERENCE_LENGTH = 40f
        internal const val REFERENCE_AREA = REFERENCE_LENGTH * REFERENCE_LENGTH
        /** The least area a vertex is taken to carry, as a share of [REFERENCE_AREA], so a stray vertex is not weightless. */
        private const val MIN_VERTEX_AREA = 0.05f
        /** How much of the stretch and area stiffness is left across the grain at anisotropy 1. */
        private const val ANISOTROPY_SOFTENING = 0.6f
        /** How much more a fold along the grain gives than one across it at anisotropy 1. */
        private const val GRAIN_FOLD = 8f

        /** Stretch compliance for a 0..1 stiffness: 1 is effectively inextensible, 0 rubbery. */
        internal fun compliance(stiffness: Float): Float = 1e-9f * 10f.pow(6f * (1f - stiffness.coerceIn(0f, 1f)))

        /** Log-space mix of [along] and [across] by how much the edge runs along the grain. */
        private fun blend(along: Float, across: Float, u: Float): Float = along.pow(u) * across.pow(1f - u)

        /** The frequency a 0..1 stiffness springs back at per unit mass: 0.5 rad/s at 0 (limp), about 10 at 0.5, 200 at 1. */
        private fun frequency(stiffness: Float): Float = 0.5f * 400f.pow(stiffness.coerceIn(0f, 1f))

        /**
         * Compression compliance of an edge for a 0..1 area stiffness, by frequency: the edges give way
         * softly as the sheet folds in depth, as firmly as it keeps its area.
         */
        internal fun compressionCompliance(stiffness: Float): Float = frequency(stiffness).let { 1f / (it * it) }

        /**
         * Bend compliance for a 0..1 stiffness between two triangles whose centres lie [apart] px apart, so
         * a curve costs alike however finely it is cut: by frequency at [REFERENCE_LENGTH], scaled by the
         * spacing.
         */
        internal fun bendCompliance(stiffness: Float, apart: Float = REFERENCE_LENGTH): Float {
            val omega = frequency(stiffness)
            return apart / REFERENCE_LENGTH / (omega * omega * REFERENCE_AREA)
        }

        /**
         * Area compliance for a 0..1 stiffness of a triangle of [area] px²; 0 keeps no area (only the
         * guard against turning over). Scaled by the area, so a finer mesh holds alike.
         */
        internal fun areaCompliance(stiffness: Float, area: Float): Float {
            if (stiffness <= 0f) return Float.POSITIVE_INFINITY
            val omega = frequency(stiffness)
            return 0.75f * REFERENCE_AREA / (omega * omega) * (area / REFERENCE_AREA)
        }

        /**
         * Goal compliance per unit mass for a 0..1 strength; 0 is no goal at all. The spring's own frequency
         * is 1/sqrt(compliance): about 0.6 Hz at 0.1, 2 Hz at 0.5 and 10 Hz at 1, so a weak goal keeps the
         * drawn shape without damping the swing.
         */
        internal fun goalCompliance(strength: Float): Float {
            if (strength <= 0f) return Float.POSITIVE_INFINITY
            val omega = 3f * 20f.pow(strength.coerceIn(0f, 1f))
            return 1f / (omega * omega)
        }

        private fun centroid(rest: FloatArray, a: List<Int>, b: List<Int>, c: List<Int>, t: Int, axis: Int) =
            (rest[a[t] * 2 + axis] + rest[b[t] * 2 + axis] + rest[c[t] * 2 + axis]) / 3f

        fun build(model: PuppetModel, edit: RigSimEdit, settings: SimSettings = SimSettings(), checkpoint: () -> Unit = {}): SimScene {
            checkpoint()
            val notes = ArrayList<String>()
            val targets = edit.targets.map { raw ->
                checkpoint()
                val drawable = requireNotNull(model.drawables.firstOrNull { it.id.raw == raw }) { "Simulation target not found: $raw" }
                drawable.id to requireNotNull(drawable.mesh) { "Simulation target has no mesh: $raw" }
            }
            val offsets = LinkedHashMap<DrawableId, Int>()
            val counts = LinkedHashMap<DrawableId, Int>()
            var total = 0
            for ((id, mesh) in targets) { offsets[id] = total; counts[id] = mesh.vertexCount; total += mesh.vertexCount }
            val state = SimState(total)

            fun group(id: DrawableId, kind: VertexGroupKind): VertexGroup? {
                val named = edit.groups[kind]
                val candidates = model.vertexGroups.filter { it.drawableId == id && it.kind == kind }
                return (if (named != null) candidates.firstOrNull { it.name == named } else candidates.firstOrNull())
                    ?.takeIf { it.weights.size == counts.getValue(id) }
            }
            fun perVertex(kind: VertexGroupKind, fallback: Float): FloatArray {
                val out = FloatArray(total) { fallback }
                for ((id, offset) in offsets) group(id, kind)?.weights?.forEachIndexed { v, w ->
                    if (v % 256 == 0) checkpoint()
                    out[offset + v] = w
                }
                return out
            }

            val m = edit.material
            val massWeight = perVertex(VertexGroupKind.MASS, 1f)
            val dampingWeight = perVertex(VertexGroupKind.DAMPING, 1f)
            val windWeight = perVertex(VertexGroupKind.WIND, 1f)
            val stiffnessWeight = perVertex(VertexGroupKind.STIFFNESS, 1f)
            val goalWeight = perVertex(VertexGroupKind.GOAL, 1f)
            val pin = perVertex(VertexGroupKind.PIN, 0f)

            // The rest shape at the default pose: triangles, edges and, per edge, the triangles beside it.
            val world = CpuDeformationEvaluator().evaluate(model, emptyMap()).worldPositions
            val rest = FloatArray(total * 2)
            val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>()
            val edgeA = ArrayList<Int>(); val edgeB = ArrayList<Int>()
            /** Per edge, the triangle on each side (-1 for an outline edge). */
            val edgeLeft = ArrayList<Int>(); val edgeRight = ArrayList<Int>()
            for ((id, mesh) in targets) {
                checkpoint()
                val offset = offsets.getValue(id)
                val positions = world[id] ?: mesh.positions.also { notes += "${id.raw} is hidden at the default pose; using its rest mesh" }
                positions.copyInto(rest, offset * 2, 0, mesh.vertexCount * 2)
                val edgeOf = HashMap<Long, Int>()
                for (t in 0 until mesh.indices.size / 3) {
                    if (t % 256 == 0) checkpoint()
                    val triangle = ta.size
                    ta += offset + mesh.indices[t * 3]; tb += offset + mesh.indices[t * 3 + 1]; tc += offset + mesh.indices[t * 3 + 2]
                    for (e in 0..2) {
                        val a = mesh.indices[t * 3 + e]
                        val b = mesh.indices[t * 3 + (e + 1) % 3]
                        val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
                        val edge = edgeOf[key]
                        if (edge == null) {
                            edgeOf[key] = edgeA.size
                            edgeA += offset + a; edgeB += offset + b; edgeLeft += triangle; edgeRight += -1
                        } else if (edgeRight[edge] < 0 && edgeLeft[edge] != triangle) edgeRight[edge] = triangle
                    }
                }
            }
            fun length(i: Int, j: Int) = hypot(rest[i * 2] - rest[j * 2], rest[i * 2 + 1] - rest[j * 2 + 1])
            val triangleArea = FloatArray(ta.size) { t ->
                if (t % 256 == 0) checkpoint()
                val a = ta[t]; val b = tb[t]; val c = tc[t]
                ((rest[b * 2] - rest[a * 2]) * (rest[c * 2 + 1] - rest[a * 2 + 1]) - (rest[c * 2] - rest[a * 2]) * (rest[b * 2 + 1] - rest[a * 2 + 1])) / 2f
            }

            // Mass by the area each vertex carries, a third of every triangle around it: a finer mesh gets
            // more detail, not a heavier cloth.
            val vertexArea = FloatArray(total)
            for (t in ta.indices) { val third = abs(triangleArea[t]) / 3f; vertexArea[ta[t]] += third; vertexArea[tb[t]] += third; vertexArea[tc[t]] += third }
            for (i in 0 until total) {
                if (i % 256 == 0) checkpoint()
                val carried = vertexArea[i].coerceAtLeast(MIN_VERTEX_AREA * REFERENCE_AREA)
                state.invMass[i] = REFERENCE_AREA / (carried * m.mass * massWeight[i].coerceAtLeast(0.1f))
                state.damping[i] = m.damping * dampingWeight[i]
                state.windFactor[i] = windWeight[i]
            }

            // Glue: a PIN role anchors the simulated side on the partner; CONSTRAINT welds two simulated sides.
            val anchors = arrayOfNulls<Anchor>(total)
            val weldA = ArrayList<Int>(); val weldB = ArrayList<Int>(); val weldWA = ArrayList<Float>(); val weldWB = ArrayList<Float>()
            for (glue in model.glues) {
                checkpoint()
                val role = edit.glueRoles[glueKey(glue)] ?: GlueRole.IGNORE
                if (role == GlueRole.IGNORE) continue
                val offsetA = offsets[glue.meshA]
                val offsetB = offsets[glue.meshB]
                when {
                    role == GlueRole.CONSTRAINT && offsetA != null && offsetB != null -> for (pair in glue.pairs) {
                        checkpoint()
                        if (pair.indexA >= counts.getValue(glue.meshA) || pair.indexB >= counts.getValue(glue.meshB)) continue
                        weldA += offsetA + pair.indexA; weldB += offsetB + pair.indexB
                        weldWA += pair.weightA; weldWB += pair.weightB
                    }
                    role == GlueRole.CONSTRAINT -> notes += "Glue ${glueKey(glue)} is a constraint but only one side is simulated"
                    role == GlueRole.PIN && (offsetA == null) == (offsetB == null) ->
                        notes += "Glue ${glueKey(glue)} pins only when exactly one side is simulated"
                    else -> for (pair in glue.pairs) {
                        checkpoint()
                        val simulatedIsA = offsetA != null
                        val own = if (simulatedIsA) pair.indexA else pair.indexB
                        val other = if (simulatedIsA) pair.indexB else pair.indexA
                        val id = if (simulatedIsA) glue.meshA else glue.meshB
                        if (own >= counts.getValue(id)) continue
                        val i = offsets.getValue(id) + own
                        // The default half-and-half weld holds fully: a glue weight of 0.5 is a full pin.
                        val strength = ((if (simulatedIsA) pair.weightA else pair.weightB) * glue.intensity * 2f).coerceIn(0f, 1f)
                        if (strength >= pin[i]) {
                            pin[i] = strength
                            anchors[i] = Anchor(if (simulatedIsA) glue.meshB else glue.meshA, other)
                        }
                    }
                }
            }

            // Path length along the mesh from the nearest firmly pinned particle: it roots the long-range
            // limits and runs the grain away from the pins.
            val neighbours = Array(total) { ArrayList<Int>() }
            for (e in edgeA.indices) { neighbours[edgeA[e]] += edgeB[e]; neighbours[edgeB[e]] += edgeA[e] }
            val roots = (0 until total).filter { pin[it] >= ROOT_PIN }
            val distance = FloatArray(total) { Float.MAX_VALUE }
            val root = IntArray(total) { -1 }
            run {
                val queue = PriorityQueue<Pair<Float, Int>>(compareBy({ it.first }, { it.second }))
                for (r in roots) { distance[r] = 0f; root[r] = r; queue += 0f to r }
                while (queue.isNotEmpty()) {
                    checkpoint()
                    val (d, i) = queue.poll()
                    if (d > distance[i]) continue
                    for (j in neighbours[i]) {
                        val next = d + length(i, j)
                        if (next < distance[j]) { distance[j] = next; root[j] = root[i]; queue += next to j }
                    }
                }
            }
            val lraParticle = ArrayList<Int>(); val lraRoot = ArrayList<Int>(); val lraDistance = ArrayList<Float>()
            if (roots.isNotEmpty() && m.slack < 1f) {
                for (i in 0 until total) if (root[i] >= 0 && root[i] != i) {
                    if (i % 256 == 0) checkpoint()
                    lraParticle += i; lraRoot += root[i]; lraDistance += distance[i] * (1f + m.slack)
                }
            } else if (roots.isEmpty()) {
                notes += "Nothing is pinned: paint a PIN group or set a glue to pin, or the body falls away"
            }

            // The grain per triangle: the way the path length grows across it, else straight down.
            val grainX = FloatArray(ta.size); val grainY = FloatArray(ta.size) { -1f }
            for (t in ta.indices) {
                if (t % 256 == 0) checkpoint()
                val a = ta[t]; val b = tb[t]; val c = tc[t]
                if (distance[a] == Float.MAX_VALUE || distance[b] == Float.MAX_VALUE || distance[c] == Float.MAX_VALUE) continue
                val e1x = rest[b * 2] - rest[a * 2]; val e1y = rest[b * 2 + 1] - rest[a * 2 + 1]
                val e2x = rest[c * 2] - rest[a * 2]; val e2y = rest[c * 2 + 1] - rest[a * 2 + 1]
                val det = e1x * e2y - e1y * e2x
                if (abs(det) < 1e-6f) continue
                val d1 = distance[b] - distance[a]; val d2 = distance[c] - distance[a]
                val gx = (d1 * e2y - d2 * e1y) / det
                val gy = (d2 * e1x - d1 * e2x) / det
                val norm = hypot(gx, gy)
                if (norm < 1e-3f) continue
                grainX[t] = gx / norm; grainY[t] = gy / norm
            }
            /** How much edge [e] runs along the grain of the triangles beside it, 0..1. */
            fun along(e: Int): Float {
                var gx = grainX[edgeLeft[e]]; var gy = grainY[edgeLeft[e]]
                val right = edgeRight[e]
                if (right >= 0 && gx * grainX[right] + gy * grainY[right] > 0f) { gx += grainX[right]; gy += grainY[right] }
                val g = hypot(gx, gy)
                val l = length(edgeA[e], edgeB[e])
                if (g < 1e-6f || l < 1e-6f) return 1f
                val cos = ((rest[edgeB[e] * 2] - rest[edgeA[e] * 2]) * gx + (rest[edgeB[e] * 2 + 1] - rest[edgeA[e] * 2 + 1]) * gy) / (g * l)
                return cos * cos
            }

            // Stretch and compression along every edge, softer across the grain; area per triangle; bend
            // across every inner edge, softer where the sheet folds along the grain.
            val sr = ArrayList<Float>(); val sc = ArrayList<Float>(); val sq = ArrayList<Float>()
            val ba = ArrayList<Int>(); val bb = ArrayList<Int>(); val bc = ArrayList<Float>()
            val across = 1f - ANISOTROPY_SOFTENING * m.anisotropy
            val edgeAlong = FloatArray(edgeA.size)
            for (e in edgeA.indices) {
                if (e % 256 == 0) checkpoint()
                val a = edgeA[e]; val b = edgeB[e]
                val stiffness = (stiffnessWeight[a] + stiffnessWeight[b]) / 2f
                val u = along(e)
                edgeAlong[e] = u
                sr += length(a, b)
                sc += blend(compliance(m.stretch * stiffness), compliance(m.stretch * stiffness * across), u)
                sq += blend(compressionCompliance(m.area * stiffness), compressionCompliance(m.area * stiffness * across), u)
                val right = edgeRight[e]
                if (right < 0) continue
                val left = edgeLeft[e]
                val apart = hypot(centroid(rest, ta, tb, tc, left, 0) - centroid(rest, ta, tb, tc, right, 0),
                    centroid(rest, ta, tb, tc, left, 1) - centroid(rest, ta, tb, tc, right, 1))
                // A fold line along the grain parts two strands; across it, it bends them.
                ba += left; bb += right
                bc += bendCompliance(m.bend * stiffness, apart) * (1f + GRAIN_FOLD * m.anisotropy * u)
            }
            val area = FloatArray(ta.size) { t ->
                if (t % 256 == 0) checkpoint()
                val stiffness = (stiffnessWeight[ta[t]] + stiffnessWeight[tb[t]] + stiffnessWeight[tc[t]]) / 3f
                areaCompliance(m.area * stiffness, abs(triangleArea[t]))
            }

            // Goals per unit mass, so they spring back alike however finely the mesh is cut.
            val goal = FloatArray(total) { goalCompliance(m.goal * goalWeight[it]) * state.invMass[it] }

            state.reset(rest)
            val solver = XpbdSolver(
                state,
                stretch = DistanceConstraints(edgeA.toIntArray(), edgeB.toIntArray(), sr.toFloatArray(), sc.toFloatArray(), sq.toFloatArray()),
                triangles = TriangleConstraints(ta.toIntArray(), tb.toIntArray(), tc.toIntArray(), area),
                bends = BendConstraints(ba.toIntArray(), bb.toIntArray(), bc.toFloatArray()),
                welds = WeldConstraints(weldA.toIntArray(), weldB.toIntArray(), weldWA.toFloatArray(), weldWB.toFloatArray(), FloatArray(weldA.size)),
                longRange = LongRangeConstraints(lraParticle.toIntArray(), lraRoot.toIntArray(), lraDistance.toFloatArray()),
                pinWeight = pin,
                goalCompliance = goal,
                settings = settings,
            )
            checkpoint()
            return SimScene(edit, solver, offsets, counts, anchors, notes, edgeAlong)
        }
    }
}
