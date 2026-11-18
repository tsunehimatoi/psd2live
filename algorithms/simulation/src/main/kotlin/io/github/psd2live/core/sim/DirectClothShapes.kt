package io.github.psd2live.core.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Computes displacement keys from independent load cases on the cloth's original mesh. */
internal object DirectClothShapes {
    fun build(scene: SimScene, keys: FloatArray, check: () -> Unit): List<DirectSpatialMode> {
        val cloth = Cloth(scene, check)
        if (cloth.freeCount == 0) return emptyList()
        val neutral = FloatArray(scene.state.count * 2)
        val sway = keys.map { key ->
            check()
            if (key == 0f) neutral.copyOf() else cloth.displace(DirectMotionKind.SWAY, key, check)
        }
        return DirectMotionKind.entries.map { kind ->
            val offsets = if (kind == DirectMotionKind.SWAY) sway else keys.mapIndexed { index, key ->
                check()
                if (key == 0f) neutral.copyOf() else cloth.displace(kind, key, check, sway[index])
            }
            val (frequency, damping) = cloth.response(offsets.first(), offsets.last())
            DirectSpatialMode(kind, keys, offsets, frequency, damping)
        }
    }

    private class Cloth(scene: SimScene, check: () -> Unit) {
        private val solver = scene.solver
        private val state = scene.state
        private val material = scene.edit.material
        private val triangles = solver.triangles
        private val areas = DoubleArray(triangles.size) { t ->
            val a = triangles.a[t]
            val b = triangles.b[t]
            val c = triangles.c[t]
            val dx = (state.x[b] - state.x[a]).toDouble()
            val dy = (state.y[b] - state.y[a]).toDouble()
            dx * (state.y[c] - state.y[a]) - dy * (state.x[c] - state.x[a])
        }
        private val parent = IntArray(state.count) { it }
        private val pinned = BooleanArray(state.count)
        private val row = IntArray(state.count) { -1 }
        private val horizontal = DoubleArray(state.count)
        private val downward = DoubleArray(state.count)
        private val across = DoubleArray(state.count)
        private val depth = DoubleArray(state.count)
        private val grainX = DoubleArray(state.count)
        private val grainY = DoubleArray(state.count) { -1.0 }
        private val representative: IntArray
        private val extent: Double
        private val masses: DoubleArray
        private val damping: DoubleArray
        private val loadGain: DoubleArray
        private val matrix: ElasticMatrix

        val freeCount get() = masses.size

        init {
            // Material at a welded seam moves together. Eliminating the duplicate unknowns keeps
            // every key on the seam, including keys made from opposite loads.
            val welds = solver.welds
            for (edge in 0 until welds.size) {
                if (edge % 256 == 0) check()
                if (welds.weightA[edge] <= 0f && welds.weightB[edge] <= 0f) continue
                join(parent, welds.a[edge], welds.b[edge])
            }
            for (p in parent.indices) {
                val held = solver.pinWeight[p] >= 0.999f ||
                    (scene.anchorOf(p) != null && solver.pinWeight[p] >= 0.5f)
                if (held) pinned[root(parent, p)] = true
            }

            var free = 0
            for (p in parent.indices) if (root(parent, p) == p && !pinned[p]) row[p] = free++
            for (p in parent.indices) row[p] = row[root(parent, p)]
            representative = IntArray(free)
            for (p in row.indices) if (row[p] >= 0) representative[row[p]] = p
            val particleMass = DoubleArray(state.count) { 1.0 / state.invMass[it].coerceAtLeast(1e-9f) }
            masses = DoubleArray(free)
            damping = DoubleArray(free)
            for (p in row.indices) {
                val j = row[p]
                if (j < 0) continue
                masses[j] += particleMass[p]
                damping[j] += particleMass[p] * state.damping[p]
            }

            val path = DoubleArray(state.count)
            val anchor = IntArray(state.count) { -1 }
            val attachments = solver.longRange
            for (i in 0 until attachments.size) {
                val p = attachments.particle[i]
                path[p] = attachments.maxDistance[i] / (1.0 + material.slack)
                anchor[p] = attachments.root[i]
            }
            val stretch = solver.stretch
            // Two panels welded into one garment rotate about the same waist, while disconnected
            // garments and islands retain their own roots.
            val connected = parent.copyOf()
            for (edge in 0 until stretch.size) {
                if (edge % 256 == 0) check()
                join(connected, stretch.a[edge], stretch.b[edge])
            }
            val island = IntArray(state.count) { root(connected, it) }
            val top = DoubleArray(state.count) { Double.NEGATIVE_INFINITY }
            val bottom = DoubleArray(state.count) { Double.POSITIVE_INFINITY }
            val pinMass = DoubleArray(state.count)
            val pivotX = DoubleArray(state.count)
            val pivotY = DoubleArray(state.count)
            for (p in island.indices) {
                val component = island[p]
                val y = state.y[p].toDouble()
                top[component] = maxOf(top[component], y)
                bottom[component] = minOf(bottom[component], y)
                if (solver.pinWeight[p] >= 0.5f) {
                    val weight = solver.pinWeight[p].toDouble()
                    pinMass[component] += weight
                    pivotX[component] += weight * state.anchorX[p]
                    pivotY[component] += weight * state.anchorY[p]
                }
            }
            val topCount = IntArray(state.count)
            for (p in island.indices) {
                val component = island[p]
                val band = maxOf(1.0, (top[component] - bottom[component]) * 0.08)
                if (pinMass[component] == 0.0 && state.y[p] >= top[component] - band) {
                    topCount[component]++
                    pivotX[component] += state.x[p]
                }
            }
            var maximum = 1.0
            for (p in island.indices) {
                if (p % 256 == 0) check()
                val component = island[p]
                val pinnedWeight = pinMass[component]
                val x = pivotX[component] / if (pinnedWeight > 0.0) pinnedWeight else topCount[component].toDouble()
                val y = if (pinnedWeight > 0.0) pivotY[component] / pinnedWeight else top[component]
                horizontal[p] = state.x[p] - x
                downward[p] = y - state.y[p]
                val span = (top[component] - bottom[component]).coerceAtLeast(1.0)
                depth[p] = if (solver.pinWeight[p] >= 0.5f) 0.0
                else path[p].takeIf { it > 0.0 } ?: maxOf(downward[p], span * 0.1)
                maximum = maxOf(maximum, depth[p])
            }
            extent = maximum
            val width = DoubleArray(state.count) { 1.0 }
            for (p in island.indices) width[island[p]] = maxOf(width[island[p]], abs(horizontal[p]))
            for (p in island.indices) across[p] = horizontal[p] / width[island[p]]
            val averageEdge = stretch.rest.takeIf { it.isNotEmpty() }?.average()?.coerceAtLeast(1.0) ?: extent
            // The pinned portion shortens the effective freely bending span.
            val effectiveSpan = maxOf(extent * 0.11, averageEdge * 0.6, 1.0)
            val gravity = hypot(solver.settings.gravityX.toDouble(), solver.settings.gravityY.toDouble())
            val baseStiffness = (gravity / effectiveSpan + 0.25 * 400.0.pow(2.0 * material.goal)).coerceAtLeast(4.0)
            loadGain = DoubleArray(state.count) { particleMass[it] * baseStiffness }
            val diagonal = DoubleArray(free)
            val lower = Array(free) { sortedMapOf<Int, Double>() }
            for (p in row.indices) {
                val j = row[p]
                if (j < 0) continue
                val pin = solver.pinWeight[p].toDouble()
                val anchor = 4.0 * pin / (1.0 - pin).coerceAtLeast(0.001)
                diagonal[j] += loadGain[p] * (1.0 + anchor)
            }
            for (edge in 0 until stretch.size) {
                if (edge % 256 == 0) check()
                val a = stretch.a[edge]
                val b = stretch.b[edge]
                val i = row[a]
                val j = row[b]
                if (i == j) continue
                val along = scene.edgeAlong[edge].toDouble()
                val crossGrain = 1.0 - material.anisotropy * (1.0 - along) * 0.65
                val length = stretch.rest[edge].toDouble().coerceAtLeast(averageEdge * 0.35)
                val relativeLength = (averageEdge / length).coerceIn(0.3, 3.0)
                val stiffness = (loadGain[a] + loadGain[b]) * 0.5 * relativeLength * relativeLength *
                    crossGrain * (0.45 + 1.25 * material.bend + 0.65 * material.stretch)
                if (i >= 0) diagonal[i] += stiffness
                if (j >= 0) diagonal[j] += stiffness
                if (i >= 0 && j >= 0) lower[maxOf(i, j)].merge(minOf(i, j), -stiffness, Double::plus)
            }
            matrix = ElasticMatrix(diagonal, Array(free) { lower[it].keys.toIntArray() },
                Array(free) { lower[it].values.toDoubleArray() }, check)
            resolveGrain(anchor, check)
        }

        /** Grain follows the smoothed gradient of distance from the seam, including slanted fabric panels. */
        private fun resolveGrain(anchor: IntArray, check: () -> Unit) {
            val gx = DoubleArray(state.count)
            val gy = DoubleArray(state.count)
            for (t in areas.indices) {
                if (t % 256 == 0) check()
                val area = areas[t]
                if (abs(area) < 0.01) continue
                val a = triangles.a[t]
                val b = triangles.b[t]
                val c = triangles.c[t]
                val alongB = depth[b] - depth[a]
                val alongC = depth[c] - depth[a]
                val x = (alongB * (state.y[c] - state.y[a]) - alongC * (state.y[b] - state.y[a])) / area
                val y = (alongC * (state.x[b] - state.x[a]) - alongB * (state.x[c] - state.x[a])) / area
                val length = hypot(x, y)
                if (length < 1e-7) continue
                val weight = abs(area) / length
                val wx = weight * x
                val wy = weight * y
                gx[a] += wx
                gx[b] += wx
                gx[c] += wx
                gy[a] += wy
                gy[b] += wy
                gy[c] += wy
            }
            val rhsX = DoubleArray(freeCount)
            val rhsY = DoubleArray(freeCount)
            for (p in row.indices) {
                if (p % 256 == 0) check()
                val j = row[p]
                if (j < 0) continue
                val a = anchor[p]
                val dx = if (a >= 0) (state.x[p] - state.anchorX[a]).toDouble() else horizontal[p]
                val dy = if (a >= 0) (state.y[p] - state.anchorY[a]).toDouble() else -downward[p]
                val chord = hypot(dx, dy)
                val fallX = if (chord > 1e-5) dx / chord else 0.0
                val fallY = if (chord > 1e-5) dy / chord else -1.0
                val grad = hypot(gx[p], gy[p])
                val useGradient = grad > 1e-7 && gx[p] * fallX + gy[p] * fallY > 0.0
                val px = if (useGradient) 0.78 * gx[p] / grad + 0.22 * fallX else fallX
                val py = if (useGradient) 0.78 * gy[p] / grad + 0.22 * fallY else fallY
                rhsX[j] += loadGain[p] * px
                rhsY[j] += loadGain[p] * py
            }
            val sx = matrix.solve(rhsX, check)
            val sy = matrix.solve(rhsY, check)
            for (p in row.indices) {
                val j = row[p]
                if (j < 0) continue
                val length = hypot(sx[j], sy[j])
                if (length < 1e-7) continue
                grainX[p] = sx[j] / length
                grainY[p] = sy[j] / length
            }
        }

        private fun join(parent: IntArray, a: Int, b: Int) {
            val first = root(parent, a)
            val second = root(parent, b)
            parent[maxOf(first, second)] = minOf(first, second)
        }

        private fun root(parent: IntArray, particle: Int): Int {
            var canonical = particle
            while (parent[canonical] != canonical) canonical = parent[canonical]
            var p = particle
            while (p != canonical) {
                val next = parent[p]
                parent[p] = canonical
                p = next
            }
            return canonical
        }

        fun displace(kind: DirectMotionKind, key: Float, check: () -> Unit, sway: FloatArray? = null): FloatArray {
            val x = DoubleArray(freeCount)
            val y = DoubleArray(freeCount)
            val u = key.toDouble()
            val u2 = u * u
            val flexibility = (1.18 - 0.42 * material.bend + 0.24 * material.slack).coerceIn(0.7, 1.3)
            for (p in row.indices) {
                if (p % 256 == 0) check()
                val j = row[p]
                if (j < 0 || depth[p] == 0.0) continue
                val t = (depth[p] / extent).coerceIn(0.0, 1.0)
                val width = horizontal[p]
                val drop = downward[p]
                when (kind) {
                    DirectMotionKind.SWAY -> {
                        // The section advances as it tilts; the hem takes the largest arc.
                        val angle = u * 0.22 * flexibility * t
                        x[j] += loadGain[p] * (depth[p] * sin(angle) + width * (cos(angle) - 1.0))
                        y[j] += loadGain[p] * (width * sin(angle) * 0.36 + drop * (1.0 - cos(angle)))
                    }
                    DirectMotionKind.TURN -> {
                        val hem = t * t * (3.0 - 2.0 * t)
                        val side = across[p] * if (u < 0.0) -1.0 else 1.0
                        val loose = maxOf(0.0, -side)
                        val taut = maxOf(0.0, side)
                        val softness = 1.0 - material.area
                        val pinch = 0.065 + 0.26 * softness * loose * loose - 0.045 * taut
                        // As the turn deepens, slack fabric gathers along its grain before the
                        // section rotates. The trailing side gathers farther than the tense edge.
                        val gather = depth[p] * hem * u2 * (0.45 + 0.55 * u2) * softness *
                            (0.34 + 0.14 * loose - 0.08 * taut)
                        val section = width * (1.0 - u2 * hem * pinch) - grainX[p] * gather
                        val shortenedDrop = drop + grainY[p] * gather
                        val angle = u * (0.54 + 0.07 * u2) * flexibility * t.pow(0.85)
                        val edgeTravel = u * u2 * (2.0 - u2) * extent * (0.12 + 0.12 * softness) *
                            t * t * (1.3 - 0.3 * t) * loose * loose
                        val edgeLift = extent * 0.026 * softness * u2 * hem * side
                        x[j] += loadGain[p] * (section * cos(angle) - width + shortenedDrop * sin(angle) + edgeTravel)
                        y[j] += loadGain[p] * (section * sin(angle) + drop - shortenedDrop * cos(angle) + edgeLift)
                    }
                    DirectMotionKind.COMPRESSION -> {
                        // Closing a fold travels farther than opening it. An even, fourth-order term
                        // keeps the different extremes while tapering the asymmetry near neutral.
                        val softness = 1.0 - material.area
                        val u4 = u2 * u2
                        val fold = (0.09 + 0.025 * softness) * u + (0.009 + 0.006 * softness) * u4
                        val flare = 0.055 * u + 0.027 * u4
                        // Concentrate the shortening in deep folds and keep the upper panel under tension.
                        val ease = t * (0.42 + 0.58 * t)
                        val foldDepth = t * t * (0.55 + 0.45 * t)
                        val edge = 0.43 + 0.82 * across[p] * across[p]
                        val along = extent * fold * foldDepth * edge * flexibility
                        val gx = grainX[p]
                        val gy = grainY[p]
                        val normalWidth = -width * gy - drop * gx
                        val acrossGrain = normalWidth * flare * ease * (1.15 - 0.55 * material.area)
                        x[j] += loadGain[p] * (-gx * along - gy * acrossGrain)
                        y[j] += loadGain[p] * (-gy * along + gx * acrossGrain)
                    }
                    DirectMotionKind.FOLLOW_THROUGH -> {
                        // A second response bends the free hem below the main panel. Its two sides
                        // lift and fall relative to the centre, like a loose edge unwinding after a sweep.
                        val tip = ((t - 0.32) / 0.68).coerceIn(0.0, 1.0)
                        val hinge = tip * tip * (3.0 - 2.0 * tip)
                        val side = across[p]
                        val angle = u * 0.17 * flexibility * hinge
                        val drag = u * extent * hinge * (0.026 - 0.018 * side * side)
                        val hemCurl = extent * 0.032 * u2 * hinge * (side * side - 0.33)
                        x[j] += loadGain[p] * (drag + width * (cos(angle) - 1.0))
                        y[j] += loadGain[p] * (width * sin(angle) + hemCurl)
                    }
                }
            }
            val dx = matrix.solve(x, check)
            val dy = matrix.solve(y, check)
            if (kind == DirectMotionKind.FOLLOW_THROUGH && sway != null) removeSharedSweep(dx, sway)
            preserveArea(dx, dy, check)
            return FloatArray(row.size * 2) { coordinate ->
                val j = row[coordinate / 2]
                if (j < 0) 0f else (if (coordinate % 2 == 0) dx[j] else dy[j]).toFloat()
            }
        }

        /** Leave the free-edge mode with its own shape instead of replaying the primary horizontal sweep. */
        private fun removeSharedSweep(x: DoubleArray, sway: FloatArray) {
            var projection = 0.0
            var energy = 0.0
            for (j in x.indices) {
                val sweep = sway[representative[j] * 2].toDouble()
                projection += masses[j] * x[j] * sweep
                energy += masses[j] * sweep * sweep
            }
            if (energy <= 1e-10) return
            val scale = projection / energy
            for (j in x.indices) x[j] -= scale * sway[representative[j] * 2]
        }

        /** Fix thin triangle folds locally so one small pleat does not damp the whole garment. */
        private fun preserveArea(x: DoubleArray, y: DoubleArray, check: () -> Unit) {
            val joints = IntArray(3)
            val gx = DoubleArray(3)
            val gy = DoubleArray(3)

            fun pointX(p: Int) = state.x[p] + if (row[p] >= 0) x[row[p]] else 0.0
            fun pointY(p: Int) = state.y[p] + if (row[p] >= 0) y[row[p]] else 0.0

            repeat(6) { pass ->
                check()
                var corrected = false
                for (k in areas.indices) {
                    val t = if (pass % 2 == 0) k else areas.lastIndex - k
                    val neutral = areas[t]
                    if (abs(neutral) < 0.01) continue
                    val a = triangles.a[t]
                    val b = triangles.b[t]
                    val c = triangles.c[t]
                    val ax = pointX(a)
                    val ay = pointY(a)
                    val bx = pointX(b)
                    val by = pointY(b)
                    val cx = pointX(c)
                    val cy = pointY(c)
                    val sign = if (neutral < 0.0) -1.0 else 1.0
                    val area = sign * ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
                    val deficit = 0.42 * abs(neutral) - area
                    if (deficit <= 0.0) continue

                    joints[0] = row[a]
                    joints[1] = row[b]
                    joints[2] = row[c]
                    gx[0] = sign * (by - cy)
                    gx[1] = sign * (cy - ay)
                    gx[2] = sign * (ay - by)
                    gy[0] = sign * (cx - bx)
                    gy[1] = sign * (ax - cx)
                    gy[2] = sign * (bx - ax)
                    for (i in joints.indices) {
                        if (joints[i] < 0) continue
                        for (j in 0 until i) if (joints[i] == joints[j]) {
                            // The seam endpoints share a displacement unknown.
                            gx[j] += gx[i]
                            gy[j] += gy[i]
                            joints[i] = -1
                            break
                        }
                    }
                    var denominator = 0.0
                    for (i in joints.indices) {
                        val j = joints[i]
                        if (j >= 0) denominator += (gx[i] * gx[i] + gy[i] * gy[i]) / masses[j]
                    }
                    if (denominator <= 1e-12) continue
                    val correction = deficit / denominator
                    for (i in joints.indices) {
                        val j = joints[i]
                        if (j < 0) continue
                        x[j] += correction * gx[i] / masses[j]
                        y[j] += correction * gy[i] / masses[j]
                    }
                    corrected = true
                }
                if (!corrected) return
            }
        }

        fun response(first: FloatArray, last: FloatArray): Pair<Float, Float> {
            val x = DoubleArray(freeCount)
            val y = DoubleArray(freeCount)
            for (p in row.indices) {
                val j = row[p]
                if (j < 0) continue
                x[j] = (last[p * 2] - first[p * 2]).toDouble()
                y[j] = (last[p * 2 + 1] - first[p * 2 + 1]).toDouble()
            }
            var mass = 0.0
            var drag = 0.0
            for (j in x.indices) {
                val amplitude = x[j] * x[j] + y[j] * y[j]
                mass += masses[j] * amplitude
                drag += damping[j] * amplitude
            }
            if (mass <= 1e-12) return 1f to 0.25f
            val radPerSecond = sqrt(((matrix.energy(x) + matrix.energy(y)) / mass).coerceAtLeast(1e-12))
            val hz = (radPerSecond / (2 * PI)).coerceIn(0.35, 4.0)
            val ratio = (drag / (2.0 * mass * radPerSecond)).coerceIn(0.06, 0.9)
            return hz.toFloat() to ratio.toFloat()
        }
    }

    /** The same SPD system describes both coordinates of every load case. Factor its fixed sparse pattern once. */
    private class ElasticMatrix(
        private val diagonal: DoubleArray,
        private val columns: Array<IntArray>,
        private val lower: Array<DoubleArray>,
        check: () -> Unit,
    ) {
        private val l = Array(lower.size) { lower[it].copyOf() }
        private val d = diagonal.copyOf()

        init {
            // Incomplete LDL^T over existing edge slots. It bounds memory and serves every key.
            for (i in diagonal.indices) {
                if (i % 128 == 0) check()
                for ((slot, j) in columns[i].withIndex()) {
                    var value = lower[i][slot]
                    var a = 0
                    var b = 0
                    while (a < slot && b < columns[j].size) {
                        val c0 = columns[i][a]
                        val c1 = columns[j][b]
                        if (c0 == c1) {
                            value -= l[i][a] * d[c0] * l[j][b]
                            a++
                            b++
                        } else if (c0 < c1) a++
                        else b++
                    }
                    val multiplier = value / d[j]
                    l[i][slot] = multiplier
                    d[i] -= multiplier * value
                }
                d[i] = maxOf(d[i], diagonal[i] * 1e-8)
            }
        }

        private fun multiply(x: DoubleArray, out: DoubleArray) {
            for (i in diagonal.indices) out[i] = diagonal[i] * x[i]
            for (i in columns.indices) for ((slot, j) in columns[i].withIndex()) {
                val value = lower[i][slot]
                out[i] += value * x[j]
                out[j] += value * x[i]
            }
        }

        private fun precondition(r: DoubleArray, out: DoubleArray) {
            r.copyInto(out)
            for (i in columns.indices) for ((slot, j) in columns[i].withIndex()) out[i] -= l[i][slot] * out[j]
            for (i in d.indices) out[i] /= d[i]
            for (i in columns.indices.reversed()) for ((slot, j) in columns[i].withIndex()) out[j] -= l[i][slot] * out[i]
        }

        fun solve(rhs: DoubleArray, check: () -> Unit): DoubleArray {
            val x = DoubleArray(rhs.size)
            val norm = dot(rhs, rhs)
            if (norm <= 1e-20) return x
            val r = rhs.copyOf()
            val z = DoubleArray(rhs.size)
            precondition(r, z)
            val p = z.copyOf()
            val mp = DoubleArray(rhs.size)
            var rz = dot(r, z)
            repeat(24) {
                check()
                multiply(p, mp)
                val denominator = dot(p, mp)
                if (denominator <= 1e-24 || !denominator.isFinite()) return x
                val step = rz / denominator
                for (i in x.indices) {
                    x[i] += step * p[i]
                    r[i] -= step * mp[i]
                }
                if (dot(r, r) <= norm * 1e-12) return x
                precondition(r, z)
                val next = dot(r, z)
                for (i in p.indices) p[i] = z[i] + (next / rz) * p[i]
                rz = next
            }
            return x
        }

        fun energy(x: DoubleArray): Double {
            val product = DoubleArray(x.size)
            multiply(x, product)
            return dot(x, product)
        }

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
