package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates form / side shadows (e.g. cheek crescent shadow) using Live2D's classic
 * Invert-Mask technique: a dark Multiply duplicate of the target silhouette clipped
 * with invertMask = true against an opacity-0 cutter mesh that shifts with world light & head turn.
 */
class FormShadowAlgorithm : ChangingModifierAlgorithmPlugin {
    private companion object {
        const val PARAM_ANGLE_X = "ParamAngleX"
        const val PARAM_ANGLE_Y = "ParamAngleY"
    }

    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        checkpoint()
        val world = ShadowGeometry.resolveWorldConfig(context)
        if (!world.previewEnabled) return ModifierModelChanges()

        val config = FormShadowConfig.fromJson(instance.config)
        val meshById = input.meshes.associateBy { it.id }
        val targets = config.targets.distinct().mapNotNull { meshById[it] }.filter { it.geometry != null }
        if (targets.isEmpty()) return ModifierModelChanges()

        val opacity = ShadowGeometry.resolveOpacity(world, config.opacityOverride)
        val colorRgb = ShadowGeometry.resolveShadowColorRgb(world, config.colorOverride, input, targets.map { it.id }, context)
        val rasters = ArrayList<ModifierRasterOutput>()

        val az = world.azimuth * (PI.toFloat() / 180f)
        val el = world.elevation.coerceIn(8f, 89f) * (PI.toFloat() / 180f)
        val lightDirX = sin(az)
        val lightDirY = -cos(az)

        for (target in targets) {
            checkpoint()
            val geom = target.geometry ?: continue
            val isWarpChild = target.parent != null && input.deformers.any { it.id == target.parent && it is Deformer.Warp }
            val pos = geom.positions.shared()
            val count = pos.size / 2
            if (count == 0) continue

            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                val x = pos[i * 2]
                val y = pos[i * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val spanX = max(1e-3f, maxX - minX)
            val spanY = max(1e-3f, maxY - minY)

            // Cutter shifts toward the light side so the opposite (shadow) side reveals a smooth crescent
            val elevFactor = (1.15f - 0.55f * sin(el)).coerceIn(0.35f, 1.15f)
            val shiftFrac = config.coverage * elevFactor
            val shiftX = lightDirX * spanX * shiftFrac
            val shiftY = lightDirY * spanY * shiftFrac * 0.45f

            val cutterId = instance.outputId("cutter:${target.id}")
            val formId = instance.outputId("form:${target.id}")

            val expandedCutterGeom = ShadowGeometry.expandRadial(geom, config.cutterExpandPx, isWarpChild, input)
            val cutterOffsets = buildCutterOffsets(target, expandedCutterGeom, shiftX, shiftY, spanX, spanY, input)
            val cutterRest = if (cutterOffsets == null) {
                val cPos = expandedCutterGeom.positions.toArray()
                for (i in 0 until count) {
                    cPos[i * 2] += shiftX
                    cPos[i * 2 + 1] += shiftY
                }
                expandedCutterGeom.copy(positions = Floats.wrap(cPos))
            } else expandedCutterGeom

            val cutterMesh = Mesh(
                id = cutterId,
                name = "${target.name} Shadow Cutter",
                parent = target.parent,
                blend = ColorBlend.NORMAL,
                geometry = cutterRest,
                offsets = cutterOffsets,
                drawOrder = target.drawOrder + 0.5f,
                opacity = 0f,
            )

            // Shadow mesh copies target silhouette & offsets so its outer edge matches the face exactly
            val shadowMesh = Mesh(
                id = formId,
                name = "${target.name} Side Shadow",
                parent = target.parent,
                blend = ColorBlend.MULTIPLY,
                maskedBy = listOf(cutterId),
                invertMask = true,
                geometry = geom,
                offsets = target.offsets,
                drawOrder = target.drawOrder + 1f,
                opacity = opacity,
            )

            val swatch = ShadowRasterGen.generateFlatColorSwatch(16, 16, colorRgb)
            val digest = ShadowRasterGen.sha256(swatch)

            // Cutter uses a tiny invisible swatch (or shares target raster, here dedicated swatch keeps ownership clean)
            val cutterLayer = SourceLayer(
                key = instance.outputId("cutter-raster:${target.id}"),
                name = "${target.name} Cutter",
                groupPath = "Shadows",
                left = 0, top = 0, width = 16, height = 16, visible = true,
            )
            val shadowLayer = SourceLayer(
                key = instance.outputId("form-raster:${target.id}"),
                name = "${target.name} Side Shadow",
                groupPath = "Shadows",
                left = 0, top = 0, width = 16, height = 16, visible = true,
            )

            // Remap UVs to [0.25, 0.75] inside the 16x16 flat color swatch so filtering never samples border alpha=0
            val flatUv = FloatArray(geom.uvs.size) { 0.5f }
            val cutterMeshFlat = cutterMesh.copy(geometry = cutterRest.copy(uvs = Floats.wrap(flatUv)))
            val shadowMeshFlat = shadowMesh.copy(geometry = geom.copy(uvs = Floats.wrap(flatUv)))

            rasters += ModifierRasterOutput(
                owner = target,
                mesh = cutterMeshFlat,
                layer = cutterLayer,
                pixels = ModifierAlgorithmResource(swatch),
                uvSpace = ModifierRasterOutput.UvSpace.RASTER_PIXELS,
            )
            rasters += ModifierRasterOutput(
                owner = target,
                mesh = shadowMeshFlat,
                layer = shadowLayer,
                pixels = ModifierAlgorithmResource(swatch),
                uvSpace = ModifierRasterOutput.UvSpace.RASTER_PIXELS,
            )
        }

        return if (rasters.isEmpty()) ModifierModelChanges() else ModifierModelChanges(rasters = rasters)
    }

    private fun buildCutterOffsets(
        target: Mesh,
        cutterGeom: MeshGeometry,
        baseShiftX: Float,
        baseShiftY: Float,
        spanX: Float,
        spanY: Float,
        rig: RigIR,
    ): KeyGrid<MeshOffsets>? {
        val count = cutterGeom.positions.size / 2
        val existing = target.offsets
        val hasAngleX = rig.parameters.any { it.id == PARAM_ANGLE_X }
        val hasAngleY = rig.parameters.any { it.id == PARAM_ANGLE_Y }

        if (existing != null && existing.axes.isNotEmpty()) {
            val axIdxX = existing.axes.indexOfFirst { it.parameter == PARAM_ANGLE_X }
            val axIdxY = existing.axes.indexOfFirst { it.parameter == PARAM_ANGLE_Y }
            val cells = existing.cells.map { cell ->
                val coord = cell.coordinate.shared()
                val axValX = if (axIdxX >= 0) existing.axes[axIdxX].keys.shared()[coord[axIdxX]] else 0f
                val axValY = if (axIdxY >= 0) existing.axes[axIdxY].keys.shared()[coord[axIdxY]] else 0f
                val normX = (axValX / 30f).coerceIn(-1f, 1f)
                val normY = (axValY / 30f).coerceIn(-1f, 1f)
                val baseDeltas = cell.form.deltas.shared()
                val next = FloatArray(count * 2)
                for (i in 0 until count) {
                    val bx = if (i * 2 < baseDeltas.size) baseDeltas[i * 2] else 0f
                    val by = if (i * 2 + 1 < baseDeltas.size) baseDeltas[i * 2 + 1] else 0f
                    next[i * 2] = bx + baseShiftX - normX * spanX * 0.08f
                    next[i * 2 + 1] = by + baseShiftY + normY * spanY * 0.05f
                }
                KeyCell(cell.coordinate, MeshOffsets(Floats.wrap(next)))
            }
            return KeyGrid(existing.axes, cells)
        }

        val axes = buildList {
            if (hasAngleX) add(KeyAxis(PARAM_ANGLE_X, Floats.wrap(floatArrayOf(-30f, 0f, 30f))))
            if (hasAngleY) add(KeyAxis(PARAM_ANGLE_Y, Floats.wrap(floatArrayOf(-30f, 0f, 30f))))
        }
        if (axes.isEmpty()) return null

        val cells = ArrayList<KeyCell<MeshOffsets>>()
        val dims = axes.map { it.keys.size }
        fun emit(depth: Int, coord: IntArray) {
            if (depth == dims.size) {
                val axValX = if (hasAngleX) axes[0].keys.shared()[coord[0]] else 0f
                val yIdx = if (hasAngleX) 1 else 0
                val axValY = if (hasAngleY) axes[yIdx].keys.shared()[coord[yIdx]] else 0f
                val normX = (axValX / 30f).coerceIn(-1f, 1f)
                val normY = (axValY / 30f).coerceIn(-1f, 1f)
                val deltas = FloatArray(count * 2)
                for (i in 0 until count) {
                    deltas[i * 2] = baseShiftX - normX * spanX * 0.08f
                    deltas[i * 2 + 1] = baseShiftY + normY * spanY * 0.05f
                }
                cells += KeyCell(Ints.wrap(coord.copyOf()), MeshOffsets(Floats.wrap(deltas)))
                return
            }
            for (k in 0 until dims[depth]) {
                coord[depth] = k
                emit(depth + 1, coord)
            }
        }
        emit(0, IntArray(dims.size))
        return KeyGrid(axes, cells)
    }
}
