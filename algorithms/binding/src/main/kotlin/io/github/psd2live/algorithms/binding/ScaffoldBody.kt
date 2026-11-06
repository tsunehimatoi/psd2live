package io.github.psd2live.algorithms.binding

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import org.umamo.runtime.model.*

/** Body and legs at the source pose, without evaluating motion keys. All measurements are canvas pixels. */
object ScaffoldBody {
    data class Joint(val x: Double, val y: Double)
    data class Leg(val hip: Joint, val knee: Joint, val ankle: Joint, val floorY: Double) {
        internal val thigh get() = distance(hip, knee).coerceAtLeast(1.0)
        internal val shin get() = distance(knee, ankle).coerceAtLeast(1.0)
        internal val reach get() = thigh + shin
    }

    data class Standing(val frame: Bounds, val legs: List<Leg>, val kneesInRest: Double) {
        init { require(legs.isNotEmpty()) { "A standing frame requires at least one leg" } }
    }

    val bodyId = DeformerId("DeformBodyXY")
    val leanId = DeformerId("DeformBodyLean")
    val legsId = DeformerId("DeformLegs")
    private val bodyPart = PartId("PartBody")

    fun build(
        body: Bounds,
        standing: Standing?,
        label: (String) -> String,
        checkpoint: () -> Unit = {},
    ): List<Deformer.Warp> = buildList {
        if (standing != null) {
            val pose = RestStance(standing)
            add(warp(legsId, label("model.deformer.legs"), null, pose.rows, 8, checkpoint) { u, v ->
                pose.legPoint(canvasPoint(standing.frame, u, v))
            })
            add(warp(bodyId, label("model.deformer.body"), null, 14, 12, checkpoint) { u, v ->
                pose.pelvis(canvasPoint(body, u, v))
            })
        } else {
            add(warp(bodyId, label("model.deformer.body"), null, 14, 12, checkpoint) { u, v -> canvasPoint(body, u, v) })
        }
        add(warp(leanId, label("model.deformer.bodyLean"), bodyId, 12, 8, checkpoint) { u, v ->
            val point = canvasPoint(body, u, v)
            Joint((point.x - body.left) / body.width, (point.y - body.top) / body.height)
        })
    }

    private class RestStance(standing: Standing) {
        private val legs = standing.legs
        private val length = legs.map { it.reach }.average()
        private val hipX = legs.map { it.hip.x }.average()
        private val hipY = legs.map { it.hip.y }.average()
        val rows = (standing.frame.height / (length * 0.06)).toInt().coerceIn(10, 20)
        private val hipBand = 0.05 * length
        private val kneeBand = 0.07 * length
        private val ankleBand = 0.025 * length
        private val hipAttachmentY = legs.maxOf { it.hip.y } + standing.frame.height.toDouble() / rows
        private val knees = legs.map { restKnee(it, pelvis(it.hip), standing.kneesInRest) }

        // At neutral the drawn hip-to-ankle distance already satisfies the pelvis reach constraint.
        fun pelvis(point: Joint) = Joint(hipX + (point.x - hipX), hipY + (point.y - hipY))

        fun legPoint(point: Joint): Joint {
            val t = smooth((point.y - hipAttachmentY) / hipBand)
            if (t <= 0.0) return pelvis(point)
            val leg = acrossLegs(point)
            return if (t >= 1.0) leg else interpolate(pelvis(point), leg, t)
        }

        private fun acrossLegs(point: Joint): Joint {
            if (legs.size == 1) return onLeg(0, point)
            val (left, right) = if (legs[0].hip.x <= legs[1].hip.x) 0 to 1 else 1 to 0
            val xl = lineX(legs[left], point.y)
            val xr = lineX(legs[right], point.y)
            val mid = (xl + xr) / 2
            val half = (abs(xr - xl) / 2).coerceAtLeast(1.0)
            val t = smooth(((point.x - mid) / (half * 0.6) + 1.0) / 2.0)
            return when {
                t <= 0.0 -> onLeg(left, point)
                t >= 1.0 -> onLeg(right, point)
                else -> interpolate(onLeg(left, point), onLeg(right, point), t)
            }
        }

        private fun onLeg(index: Int, point: Joint): Joint {
            val leg = legs[index]
            val at = chain(leg, point)
            val pastHip = smooth((at + hipBand) / (2 * hipBand))
            val pastKnee = smooth((at - leg.thigh + kneeBand) / (2 * kneeBand))
            val pastAnkle = smooth((at - leg.reach + ankleBand) / (2 * ankleBand))
            var x = 0.0
            var y = 0.0
            fun add(weight: Double, transform: () -> Joint) {
                if (weight <= 0.0) return
                val moved = transform()
                x += weight * moved.x
                y += weight * moved.y
            }
            val knee = knees[index]
            add(1 - pastHip) { pelvis(point) }
            add(pastHip - pastKnee) { bone(leg.hip, leg.knee, pelvis(leg.hip), knee, point) }
            add(pastKnee - pastAnkle) { bone(leg.knee, leg.ankle, knee, leg.ankle, point) }
            add(pastAnkle) { Joint(point.x + leg.ankle.x - leg.ankle.x, point.y + leg.ankle.y - leg.ankle.y) }
            return Joint(x, y)
        }

        private fun restKnee(leg: Leg, hip: Joint, kneeIn: Double): Joint {
            val restDx = leg.ankle.x - leg.hip.x
            val restDy = leg.ankle.y - leg.hip.y
            val restLength = hypot(restDx, restDy).coerceAtLeast(1e-6)
            val restLateral = ((leg.knee.x - leg.hip.x) * -restDy + (leg.knee.y - leg.hip.y) * restDx) / restLength
            val dx = leg.ankle.x - hip.x
            val dy = leg.ankle.y - hip.y
            val reach = hypot(dx, dy).coerceAtLeast(1e-6)
            val stretch = (reach / (leg.reach - 1e-6)).coerceAtLeast(1.0)
            val thigh = leg.thigh * stretch
            val shin = leg.shin * stretch
            val ux = dx / reach
            val uy = dy / reach
            val px = -uy
            val py = ux
            val d = reach.coerceIn(abs(thigh - shin) + 1e-6, thigh + shin - 1e-6)
            val along = (thigh * thigh - shin * shin + d * d) / (2 * d)
            val bend = sqrt((thigh * thigh - along * along).coerceAtLeast(0.0))
            val inward = sign((hipX - leg.hip.x) * px).let { if (it == 0.0) 1.0 else it }
            val s = sin(Math.toRadians(kneeIn)) * inward
            val lateral = if (bend <= abs(restLateral)) {
                sign(restLateral) * bend
            } else {
                val e = -restLateral * s + sqrt((restLateral * s) * (restLateral * s) - (restLateral * restLateral - bend * bend))
                restLateral + e * s
            }
            return Joint(hip.x + ux * along + px * lateral, hip.y + uy * along + py * lateral)
        }
    }

    private fun chain(leg: Leg, point: Joint): Double {
        val points = listOf(leg.hip, leg.knee, leg.ankle, Joint(leg.ankle.x, leg.floorY.coerceAtLeast(leg.ankle.y + 1.0)))
        var best = Double.MAX_VALUE
        var at = 0.0
        var start = 0.0
        for (index in 0..2) {
            val a = points[index]
            val b = points[index + 1]
            val length = distance(a, b).coerceAtLeast(1e-6)
            var t = ((point.x - a.x) * (b.x - a.x) + (point.y - a.y) * (b.y - a.y)) / (length * length)
            if (index > 0) t = t.coerceAtLeast(0.0)
            if (index < 2) t = t.coerceAtMost(1.0)
            val distance = distance(point, interpolate(a, b, t))
            if (distance < best - 1e-9) {
                best = distance
                at = start + t * length
            }
            start += length
        }
        return at
    }

    private fun bone(a: Joint, b: Joint, c: Joint, d: Joint, point: Joint): Joint {
        val l0 = distance(a, b).coerceAtLeast(1e-6)
        val l1 = distance(c, d).coerceAtLeast(1e-6)
        val u0x = (b.x - a.x) / l0
        val u0y = (b.y - a.y) / l0
        val u1x = (d.x - c.x) / l1
        val u1y = (d.y - c.y) / l1
        val along = (point.x - a.x) * u0x + (point.y - a.y) * u0y
        val across = (point.x - a.x) * -u0y + (point.y - a.y) * u0x
        val scaled = along * l1 / l0
        return Joint(c.x + u1x * scaled - u1y * across, c.y + u1y * scaled + u1x * across)
    }

    private fun lineX(leg: Leg, y: Double): Double = when {
        y <= leg.hip.y -> leg.hip.x
        y <= leg.knee.y -> leg.hip.x + (leg.knee.x - leg.hip.x) * (y - leg.hip.y) / (leg.knee.y - leg.hip.y).coerceAtLeast(1e-6)
        y <= leg.ankle.y -> leg.knee.x + (leg.ankle.x - leg.knee.x) * (y - leg.knee.y) / (leg.ankle.y - leg.knee.y).coerceAtLeast(1e-6)
        else -> leg.ankle.x
    }

    private fun distance(a: Joint, b: Joint) = hypot(b.x - a.x, b.y - a.y)

    private fun interpolate(a: Joint, b: Joint, t: Double) = Joint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    private fun canvasPoint(frame: Bounds, u: Float, v: Float) =
        Joint((frame.left + u * frame.width).toDouble(), (frame.top + v * frame.height).toDouble())

    private fun warp(
        id: DeformerId, name: String, parent: DeformerId?, rows: Int, columns: Int, checkpoint: () -> Unit,
        point: (Float, Float) -> Joint,
    ): Deformer.Warp {
        val points = FloatArray((columns + 1) * (rows + 1) * 2)
        var index = 0
        for (row in 0..rows) {
            checkpoint()
            for (column in 0..columns) {
                val p = point(column.toFloat() / columns, row.toFloat() / rows)
                points[index++] = p.x.toFloat()
                points[index++] = p.y.toFloat()
            }
        }
        val grid = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(points))))
        return Deformer.Warp(id, name, parent, bodyPart, rows, columns, true, grid)
    }
}
