package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpForm
import kotlin.math.cos
import kotlin.math.sin

/** Native arm swing in the scaffold warp's parent-local frame. */
object ArmSwingMotion {
    fun shape(side: String, rows: Int, columns: Int, frame: Bounds, parent: Bounds, shoulderX: Float): BlendShapeBinding<WarpForm> {
        fun turned(degrees: Float): WarpForm {
            val points = FloatArray((rows + 1) * (columns + 1) * 2)
            for (row in 0..rows) for (column in 0..columns) {
                val i = (row * (columns + 1) + column) * 2
                val v = row.toFloat() / rows
                val x = frame.left + column.toFloat() / columns * frame.width
                val y = frame.top + v * frame.height
                val angle = Math.toRadians(degrees * (0.6f + 0.4f * v).toDouble()).toFloat()
                val dx = x - shoulderX
                val dy = y - frame.top
                val rx = shoulderX + dx * cos(angle) - dy * sin(angle)
                val ry = frame.top + dx * sin(angle) + dy * cos(angle)
                points[i] = (rx - parent.left) / parent.width.coerceAtLeast(1e-4f)
                points[i + 1] = (ry - parent.top) / parent.height.coerceAtLeast(1e-4f)
            }
            return WarpForm(points)
        }
        return BlendShapeBinding(ParameterId("ParamArm${side}Swing"), floatArrayOf(-1f, 0f, 1f), 1,
            listOf(turned(-7f), null, turned(7f)))
    }

    fun physics(available: Set<String>, name: (String) -> String): List<RigPhysicsEdit> = listOf("L", "R").mapNotNull { side ->
        val swing = "ParamArm${side}Swing"
        if (swing !in available) return@mapNotNull null
        val inputs = listOf(PhysicsInput("ParamBodyAngleX", 60f, PhysicsSourceType.X),
            PhysicsInput("ParamBodyAngleZ", 40f, PhysicsSourceType.ANGLE),
            PhysicsInput("ParamAngleX", 15f, PhysicsSourceType.X)).filter { it.parameter in available }
        if (inputs.isEmpty()) return@mapNotNull null
        RigPhysicsEdit("PhysicsArm${side}Swing", name(side), inputs, listOf(PhysicsOutput(swing, 1, 1.2f)),
            listOf(PhysicsSegment(11f, 0.88f, 1.1f, 1f)), PhysicsNormalization(angleMin = -20f, angleMax = 20f))
    }
}
