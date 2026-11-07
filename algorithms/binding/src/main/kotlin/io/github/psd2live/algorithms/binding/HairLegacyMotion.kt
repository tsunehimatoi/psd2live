package io.github.psd2live.algorithms.binding

import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.*

/** Source measurements are in aligned canvas pixels, except for the roll pivot in canvas pixels. */
object HairLegacyMotion {
    enum class Plane(val token: String, val rows: Int) {
        FRONT("Front", 4), BACK("Back", 6);

        val follow = DeformerId("DeformHair${token}Follow")
        val sway = DeformerId("DeformHair${token}Physics")
        val parameter = ParameterId("ParamHair$token")
    }

    data class Tuning(val turn: Float, val tilt: Float, val perspective: Float, val sway: Float, val curl: Float, val roll: Float)
    data class Frame(val hair: Bounds, val head: Bounds, val pivot: ScaffoldHead.Point, val chinY: Float)

    val turnAxes = setOf(ParameterId("ParamAngleX"), ParameterId("ParamAngleY"), ParameterId("ParamAngleZ"))
    private val headAxes = turnAxes.mapIndexed { index, id ->
        val limit = if (index == 0) 45f else 30f
        KeyformAxis(id, floatArrayOf(-limit, 0f, limit))
    }

    fun follow(frame: Frame, tuning: Tuning, activeAxes: Set<ParameterId>, holdTurn: Boolean,
               checkpoint: () -> Unit = {}): KeyformGrid<WarpLatticeForm> {
        val (hair, head, pivot, chin) = frame
        fun x(value: Float) = (value - head.left) / head.width.coerceAtLeast(1e-4f)
        fun y(value: Float) = (value - head.top) / head.height.coerceAtLeast(1e-4f)
        val inHead = Bounds(x(hair.left), y(hair.top), x(hair.right), y(hair.bottom))
        val hold = tuning.roll / 100f
        val bends = hold > 0f && hair.bottom > chin + hair.height * 0.1f
        val axes = if (bends) headAxes else headAxes.take(2)
        val roll = HairFollowMotion.Roll(pivot.x, pivot.y, chin, hold)
        val grid = lattice(4, axes, checkpoint) { u, v, values ->
            val (px, py) = HairFollowMotion.point(inHead, u, v, if (holdTurn) 0f else values[0],
                if (holdTurn) 0f else values[1], -tuning.turn / 100f, -tuning.tilt / 100f, tuning.perspective / 100f)
            if (!bends) px to py else {
                val (dx, dy) = roll.turnBack(hair.left + u * hair.width, hair.top + v * hair.height, hair.bottom, values[2])
                px + dx / head.width to py + dy / head.height
            }
        }
        return requireNotNull(BindingProjection.restrict(grid, activeAxes, emptyMap(), WarpLatticeInterpolator, checkpoint))
    }

    fun sway(plane: Plane, frame: Bounds, tuning: Tuning, active: Boolean,
             checkpoint: () -> Unit = {}): KeyformGrid<WarpLatticeForm> {
        val scale = minOf(frame.width, frame.height).coerceAtLeast(1f)
        val sway = scale / frame.width.coerceAtLeast(1f) * (tuning.sway / 100f)
        val curl = scale / frame.height.coerceAtLeast(1f) * (tuning.curl / 100f)
        val axis = KeyformAxis(plane.parameter, floatArrayOf(-1f, 0f, 1f))
        val grid = lattice(plane.rows, listOf(axis), checkpoint) { u, v, values ->
            val swing = values[0]
            val weight = v * v * v
            (u + swing * sway * weight) to (v - swing * swing * curl * weight)
        }
        return if (active) grid else requireNotNull(BindingProjection.restrict(grid, emptySet(), emptyMap(), WarpLatticeInterpolator, checkpoint))
    }

    private fun lattice(rows: Int, axes: List<KeyformAxis>, checkpoint: () -> Unit,
                        point: (Float, Float, FloatArray) -> Pair<Float, Float>): KeyformGrid<WarpLatticeForm> =
        KeyformGrid(axes, BindingAxes.coordinates(axes.asReversed()).map { reversed ->
            checkpoint()
            val coordinate = reversed.reversedArray()
            val values = FloatArray(axes.size) { axes[it].keys[coordinate[it]] }
            val points = FloatArray((rows + 1) * 4 * 2)
            var index = 0
            for (row in 0..rows) for (column in 0..3) {
                val (x, y) = point(column.toFloat() / 3, row.toFloat() / rows, values)
                points[index++] = x
                points[index++] = y
            }
            KeyformCell(coordinate, WarpLatticeForm(points))
        })
}
