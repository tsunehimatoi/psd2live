package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm

/** A fitted-mouth provider samples this ribbon's centerline in aligned canvas pixels. */
class MouthOutlineFittedMotion(
    columns: List<MouthColumn>,
    side: Int,
    thickness: Float,
    private val frame: ModifierSourceBounds,
) {
    private val stroke = MouthOutlineStroke(columns, side, thickness)
    val sourcePath = MouthContour.crossedPath(columns, side)
    private val rest = stroke.parentPositions(frame)

    /** Absolute XY at each centerline sample in [sourcePath], in the same order and coordinate space. */
    data class PathPose(val points: List<Pair<Float, Float>>)

    /** Null opacity inherits rest opacity. A supplied value is a share of the ribbon's source opacity. */
    data class ShapePose(val pose: PathPose, val openingOpacity: Float? = null)

    data class Input(
        val geometry: KeyformGrid<PathPose>,
        val shapes: List<BlendShapeBinding<ShapePose>> = emptyList(),
    )

    data class RestChannels(
        val sourceOpacity: Float,
        val opacity: Float,
        val drawOrder: Float,
        val multiplyColor: ColorRgb,
        val screenColor: ColorRgb,
    )

    data class Result(
        val geometry: KeyformGrid<MeshDeltaForm>,
        val shapes: List<BlendShapeBinding<MeshForm>>,
    )

    fun geometry(input: KeyformGrid<PathPose>, checkpoint: () -> Unit = {}): KeyformGrid<MeshDeltaForm> {
        val axes = input.axes
        require(axes.map { it.parameterId }.distinct().size == axes.size)
        require(axes.all { axis -> axis.keys.isNotEmpty() && ordered(axis.keys) })
        val expected = axes.fold(1L) { count, axis ->
            (count * axis.keys.size).also { require(it <= 262144) }
        }
        require(input.cells.size.toLong() == expected)
        val seen = HashSet<List<Int>>()
        val cells = input.cells.map { cell ->
            checkpoint()
            val key = cell.coordinate
            require(key.size == axes.size && key.indices.all { key[it] in axes[it].keys.indices } && seen.add(key.toList()))
            KeyformCell(key.copyOf(), MeshDeltaForm(delta(cell.form)))
        }
        return KeyformGrid(axes.map { KeyformAxis(it.parameterId, it.keys.copyOf()) }, cells)
    }

    fun evaluate(input: Input, channels: RestChannels, checkpoint: () -> Unit = {}): Result {
        val geometry = geometry(input.geometry, checkpoint)
        val shapes = input.shapes.map { binding ->
            checkpoint()
            require(ordered(binding.keys) && binding.forms.size == binding.keys.size && binding.neutralIndex in binding.keys.indices)
            BlendShapeBinding(binding.parameterId, binding.keys.copyOf(), binding.neutralIndex,
                binding.forms.map { sample ->
                    sample?.let {
                        checkpoint()
                        val opening = it.openingOpacity
                        require(opening == null || opening in 0f..1f)
                        MeshForm(delta(it.pose), channels.drawOrder,
                            opening?.times(channels.sourceOpacity) ?: channels.opacity,
                            channels.multiplyColor, channels.screenColor)
                    }
                }, binding.limits.toList())
        }
        return Result(geometry, shapes)
    }

    private fun delta(pose: PathPose): FloatArray {
        require(pose.points.size == sourcePath.size && pose.points.all { (x, y) -> x.isFinite() && y.isFinite() }) {
            "Expected a finite fitted pose for every ribbon centerline sample"
        }
        val points = pose.points.iterator()
        return stroke.delta(frame, rest) { _, _ -> points.next() }
    }

    private fun ordered(keys: FloatArray) =
        keys.indices.all { keys[it].isFinite() && (it == 0 || keys[it] > keys[it - 1]) }
}
