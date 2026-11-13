package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*
import kotlin.math.abs

/** Legacy opening motion and the geometry correction on an authored tessellation. */
object MouthOutlineLegacyOwner {
    private val form = ParameterId("ParamMouthForm")
    private val open = ParameterId("ParamMouthOpenY")

    fun grid(
        points: FloatArray,
        frame: ModifierSourceBounds,
        motion: MouthOutlineMotion,
        exactClose: Boolean,
        checkpoint: () -> Unit = {},
    ): KeyformGrid<MeshDeltaForm> {
        val forms = floatArrayOf(-1f, 0f, 1f)
        val openings = floatArrayOf(0f, 0.5f, 1f)
        val width = (frame.right - frame.left).coerceAtLeast(1e-4f)
        val height = (frame.bottom - frame.top).coerceAtLeast(1e-4f)
        val cells = buildList {
            for ((f, formValue) in forms.withIndex()) for ((o, opening) in openings.withIndex()) {
                checkpoint()
                val offsets = FloatArray(points.size)
                for (i in points.indices step 2) {
                    if (i % 1024 == 0) checkpoint()
                    val (x, y) = motion.point(points[i], points[i + 1], formValue, opening, exactClose)
                    offsets[i] = (x - points[i]) / width
                    offsets[i + 1] = (y - points[i + 1]) / height
                }
                add(KeyformCell(intArrayOf(f, o), MeshDeltaForm(offsets)))
            }
        }
        return KeyformGrid(listOf(KeyformAxis(form, forms), KeyformAxis(open, openings)), cells)
    }

    /**
     * Computes the correction in normalized owner coordinates with a shared atlas placement.
     * The caller is responsible for any corresponding parent frame changes.
     * Untouched source geometry adopts the native contour. On an authored tessellation only the
     * automatic motion changes, with both native surfaces sampled at the authored UVs.
     */
    fun merge(
        model: PuppetModel,
        actual: Drawable,
        previous: Drawable,
        desired: Drawable,
        preserveTopology: Boolean,
        checkpoint: () -> Unit = {},
    ): Drawable {
        require(actual.id == previous.id && actual.id == desired.id)
        require(actual.parentDeformerId == previous.parentDeformerId && actual.parentDeformerId == desired.parentDeformerId)
        require(previous.blendShapes.isEmpty() && desired.blendShapes.isEmpty()) {
            "Legacy outline owner does not generate blend shapes"
        }
        val mesh = requireNotNull(actual.mesh)
        val old = requireNotNull(previous.mesh)
        val next = requireNotNull(desired.mesh)
        if (!preserveTopology && actual.blendShapes.isEmpty() &&
            mesh.positions.contentEquals(old.positions) && mesh.uvs.contentEquals(old.uvs) &&
            mesh.indices.contentEquals(old.indices) && PuppetIr.meshToIr(actual).offsets == PuppetIr.meshToIr(previous).offsets
        ) return actual.copy(mesh = next, geometryGrid = desired.geometryGrid)

        val local = model.copy(drawables = listOf(actual.copy(parentDeformerId = null)), deformers = emptyList(), glues = emptyList())
        val oldProjection = local.copy(drawables = listOf(previous.copy(parentDeformerId = null)))
        val newProjection = local.copy(drawables = listOf(desired.copy(parentDeformerId = null)))
        val geometry = GeometryResidual.geometry(local, oldProjection, newProjection, actual.id,
            sources(old, mesh, checkpoint), sources(next, mesh, checkpoint), checkpoint)
        return actual.copy(geometryGrid = geometry)
    }

    private fun sources(from: DrawableMesh, to: DrawableMesh, checkpoint: () -> Unit): List<VertexSource> {
        if (from.uvs.contentEquals(to.uvs)) return List(to.vertexCount) { VertexSource.FromOld(it) }
        return List(to.vertexCount) { vertex ->
            checkpoint()
            val x = to.uvs[vertex * 2]
            val y = to.uvs[vertex * 2 + 1]
            val nearest = (0 until from.vertexCount).minBy { index ->
                val dx = from.uvs[index * 2] - x
                val dy = from.uvs[index * 2 + 1] - y
                dx * dx + dy * dy
            }
            if (abs(from.uvs[nearest * 2] - x) <= 1e-7f && abs(from.uvs[nearest * 2 + 1] - y) <= 1e-7f) {
                VertexSource.FromOld(nearest)
            } else {
                val point = BindingPaths.bind(from.uvs, from.indices, x, y)
                if (minOf(point.wa, point.wb, point.wc) < -1e-4f) VertexSource.FromOld(nearest)
                else {
                    val a = point.wa.coerceAtLeast(0f)
                    val b = point.wb.coerceAtLeast(0f)
                    val c = point.wc.coerceAtLeast(0f)
                    val sum = a + b + c
                    VertexSource.BarycentricOf(point.a, point.b, point.c, a / sum, b / sum, c / sum)
                }
            }
        }
    }
}
