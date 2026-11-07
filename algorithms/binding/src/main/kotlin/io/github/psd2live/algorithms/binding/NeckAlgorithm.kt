package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Adds neck tilt and the shadow's default clipping to the existing meshes. */
class NeckAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" }) { "Invalid neck invocation" }
        val targets = instance.targets.toSet()
        for (capability in listOf("rig.scaffold", "face/legacy")) {
            require(input.providers(capability).any { it.targets.containsAll(targets) }) { "Missing neck provider: $capability" }
        }
        val context = input.context
        val source = NeckInputs.decode(context)
        if (source.targets.isEmpty()) return ModifierModelChanges()
        require(input.parameter(ANGLE_Z) != null) { "Missing head tilt parameter" }
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(NeckInputs.SOURCE_MESHES), checkpoint)
        val availableMeshes by lazy { input.meshIds.toHashSet() }
        val updates = instance.targets.mapNotNull { reference ->
            checkpoint()
            val target = source.targets[reference.id] ?: return@mapNotNull null
            val mesh = requireNotNull(input.mesh(reference.id)) { "Missing neck mesh: ${reference.id}" }
            val geometry = mesh.geometry ?: return@mapNotNull null
            val original = raw.getValue(target.sourceMesh)
            val correspondence = correspondence(original, geometry, checkpoint)
            val drawable = PuppetIr.toPuppet(RigIR(input.canvas, emptyList(), meshes = listOf(mesh))).drawables.single()
            val defaults = { id: org.umamo.runtime.model.ParameterId -> input.parameter(id.raw)?.default ?: 0f }
            val base = meshGridDefaultDeltas(drawable, defaults) ?: FloatArray(geometry.positions.size)
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, mesh.drawOrder, defaults)
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, mesh.opacity, defaults)
            fun rgb(channel: FormChannel, fallback: org.umamo.runtime.model.ColorRgb): Rgb =
                drawable.channelGrids.colorAt(channel, fallback, defaults).let { Rgb(it.red, it.green, it.blue) }
            val multiply = rgb(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor)
            val screen = rgb(FormChannel.SCREEN_COLOR, drawable.screenColor)
            val coordinates by lazy { BindingCoordinates.forMesh(input, mesh.id, checkpoint = checkpoint) }
            val local by lazy { FloatArray(base.size) { geometry.positions[it] + base[it] } }
            val canvas by lazy { coordinates.toCanvas(local) }
            fun motion(degrees: Float): FloatArray {
                val delta = deltas(original.rigPositions, target.frame, source.neck ?: target.bounds,
                    source.pivotX, source.pivotY, target.shadow, degrees, checkpoint)
                val moved = if (correspondence == null) delta else BindingVertices.transfer(delta, correspondence)
                if (mesh.parent == target.parent) return moved
                val shifted = FloatArray(moved.size) { i ->
                    val size = if (i % 2 == 0) target.frame.width else target.frame.height
                    canvas[i] + moved[i] * size.coerceAtLeast(1e-4f)
                }
                val mapped = coordinates.fromCanvas(shifted, local)
                return FloatArray(mapped.size) { mapped[it] - local[it] }
            }
            val low = motion(-30f)
            val high = motion(30f)
            val keys = Floats.values(-30f, 0f, 30f)
            fun form(delta: FloatArray) = MeshShape(Floats.wrap(FloatArray(base.size) { delta[it] + base[it] }),
                order, opacity, multiply, screen)
            val matching = mesh.shapes.indexOfFirst {
                it.parameter == ANGLE_Z && it.keys == keys && it.neutralIndex == 1 && it.limits.isEmpty()
            }
            val shapes = if (matching < 0) mesh.shapes + BlendBinding(ANGLE_Z, keys, 1, listOf(form(low), null, form(high)))
                else mesh.shapes.mapIndexed { index, binding ->
                    if (index != matching) binding else binding.copy(shapes = binding.shapes.mapIndexed shapeAtKey@{ key, shape ->
                        val delta = when (key) { 0 -> low; 2 -> high; else -> return@shapeAtKey shape }
                        if (shape == null) form(delta)
                        else shape.copy(deltas = Floats.wrap(FloatArray(base.size) { shape.deltas[it] + delta[it] }))
                    })
                }
            val masks = if (target.shadow && mesh.maskedBy.isEmpty() && !mesh.invertMask && source.masks.isNotEmpty()) {
                require(source.masks.all { it in availableMeshes }) { "Missing captured neck mask source" }
                source.masks
            } else mesh.maskedBy
            ModifierObjectUpdate(mesh, mesh.copy(shapes = shapes, maskedBy = masks))
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    private fun correspondence(original: ScaffoldMesh.RawMesh, geometry: MeshGeometry,
                               checkpoint: () -> Unit): List<VertexSource>? {
        val uvs = geometry.uvs.shared()
        if (original.atlasUvs.contentEquals(uvs)) return null
        return (uvs.indices step 2).map { i ->
            checkpoint()
            val x = uvs[i]
            val y = uvs[i + 1]
            val source = original.atlasUvs
            val nearest = (source.indices step 2).minBy { j ->
                val dx = x - source[j]
                val dy = y - source[j + 1]
                dx * dx + dy * dy
            }
            if (abs(x - source[nearest]) <= 1e-6f && abs(y - source[nearest + 1]) <= 1e-6f) {
                VertexSource.FromOld(nearest / 2)
            } else {
                val point = BindingPaths.bind(source, original.triangles, x, y)
                if (minOf(point.wa, point.wb, point.wc) < -1e-4f) VertexSource.FromOld(nearest / 2)
                else {
                    val a = point.wa.coerceAtLeast(0f)
                    val b = point.wb.coerceAtLeast(0f)
                    val c = point.wc.coerceAtLeast(0f)
                    val total = a + b + c
                    VertexSource.BarycentricOf(point.a, point.b, point.c, a / total, b / total, c / total)
                }
            }
        }
    }

    companion object {
        const val TYPE = "face.neck"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.666163652e6e65636b.v2/tilt"
        const val ANGLE_Z = "ParamAngleZ"

        fun deltas(positions: FloatArray, frame: Bounds, neck: Bounds, pivotX: Float, pivotY: Float,
                   shadow: Boolean, degrees: Float, checkpoint: () -> Unit = {}): FloatArray {
            val angle = Math.toRadians(degrees.toDouble()).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            val width = frame.width.coerceAtLeast(1e-4f)
            val height = frame.height.coerceAtLeast(1e-4f)
            return FloatArray(positions.size).also { result ->
                for (i in positions.indices step 2) {
                    if (i % 1024 == 0) checkpoint()
                    val y = positions[i + 1]
                    val w = if (shadow) 1f else ((neck.bottom - y) / neck.height.coerceAtLeast(1f))
                        .coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                    val dx = positions[i] - pivotX
                    val dy = y - pivotY
                    result[i] = (dx * c + dy * s - dx) * w / width
                    result[i + 1] = (-dx * s + dy * c - dy) * w / height
                }
            }
        }
    }
}
