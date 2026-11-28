package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.jsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class ShadowOffset2D(val dx: Float, val dy: Float)

internal object ShadowGeometry {
    private const val PARAM_ANGLE_X = "ParamAngleX"
    private const val PARAM_ANGLE_Y = "ParamAngleY"

    fun resolveWorldConfig(context: ModifierAlgorithmContext): ShadowWorldConfig {
        val raw = context.values[ShadowWorldConfig.CONTEXT_KEY]?.jsonObject ?: return ShadowWorldConfig.Default
        return ShadowWorldConfig.fromJson(raw)
    }

    /**
     * Compute 2D shadow cast offset in canvas pixels (y down).
     * azimuth = 0 -> top light -> shadow shifts down (+y).
     * azimuth > 0 -> top-right light -> shadow shifts left-down (-x, +y).
     * Lower elevation produces longer cast shadows (clamped smoothly).
     */
    fun lightOffset(azimuthDeg: Float, elevationDeg: Float, distance: Float): ShadowOffset2D {
        val az = azimuthDeg * (PI.toFloat() / 180f)
        val el = elevationDeg.coerceIn(8f, 89f) * (PI.toFloat() / 180f)
        // Length multiplier: at 45 deg -> 1.0x; lower angle -> longer (up to ~2.4x); steep top -> shorter (~0.35x)
        val lengthScale = (cos(el) / max(0.28f, sin(el))).coerceIn(0.35f, 2.4f)
        val len = distance * lengthScale
        val dx = -sin(az) * len
        val dy = cos(az) * len * 0.85f
        return ShadowOffset2D(dx, dy)
    }

    fun resolveShadowColorRgb(
        world: ShadowWorldConfig,
        colorOverride: String?,
        rig: RigIR,
        sampleMeshIds: List<String>,
        context: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty,
    ): Int {
        if (!colorOverride.isNullOrBlank()) {
            return ShadowRasterGen.parseHexRgb(colorOverride)
        }
        if (world.colorMode == ShadowColorMode.CUSTOM) {
            return ShadowRasterGen.parseHexRgb(world.customColor)
        }
        for (id in sampleMeshIds) {
            val raster = ShadowRasterGen.findMeshRgba(rig, id, context) ?: continue
            return ShadowRasterGen.deriveShadowColorRgb(raster.second.second)
        }
        return ShadowRasterGen.parseHexRgb(world.customColor)
    }

    fun resolveSoftness(world: ShadowWorldConfig, softnessOverride: Float?): Float =
        (softnessOverride ?: world.style.blurPx).coerceIn(0f, 48f)

    fun resolveOpacity(world: ShadowWorldConfig, opacityOverride: Float?): Float =
        ((opacityOverride ?: world.style.defaultOpacity) * world.intensity).coerceIn(0f, 1f)

    /**
     * Expand mesh rest positions radially from mesh center to accommodate blurred raster padding,
     * and remap UVs to [0, 1] over the padded raster.
     */
    fun expandGeometryForPadding(
        geometry: MeshGeometry,
        srcWidth: Int,
        srcHeight: Int,
        raster: ShadowRasterResult,
    ): MeshGeometry {
        val pos = geometry.positions.toArray()
        val uv = geometry.uvs.toArray()
        val count = pos.size / 2
        if (count == 0) return geometry

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
        val meshW = max(1e-4f, maxX - minX)
        val meshH = max(1e-4f, maxY - minY)

        // Compute normalized UV bounds of original mesh to know how padding maps into parent space
        var minU = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val u = uv[i * 2]
            val v = uv[i * 2 + 1]
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val uSpan = max(1e-4f, maxU - minU)
        val vSpan = max(1e-4f, maxV - minV)

        val padFracX = raster.padLeftPx / max(1f, srcWidth.toFloat())
        val padFracY = raster.padTopPx / max(1f, srcHeight.toFloat())
        val expandX = meshW * (padFracX / uSpan)
        val expandY = meshH * (padFracY / vSpan)

        val outPos = FloatArray(pos.size)
        val outUv = FloatArray(uv.size)
        val paddedTotalW = srcWidth + raster.padLeftPx + raster.padRightPx
        val paddedTotalH = srcHeight + raster.padTopPx + raster.padBottomPx

        for (i in 0 until count) {
            val nx = ((pos[i * 2] - minX) / meshW) * 2f - 1f
            val ny = ((pos[i * 2 + 1] - minY) / meshH) * 2f - 1f
            outPos[i * 2] = pos[i * 2] + nx * expandX
            outPos[i * 2 + 1] = pos[i * 2 + 1] + ny * expandY

            val uNorm = (uv[i * 2] - minU) / uSpan
            val vNorm = (uv[i * 2 + 1] - minV) / vSpan
            val uTarget = ((raster.padLeftPx + (uNorm * srcWidth) + nx * raster.padLeftPx) / paddedTotalW).coerceIn(0f, 1f)
            val vTarget = ((raster.padTopPx + (vNorm * srcHeight) + ny * raster.padTopPx) / paddedTotalH).coerceIn(0f, 1f)
            outUv[i * 2] = uTarget
            outUv[i * 2 + 1] = vTarget
        }
        return MeshGeometry(Floats.wrap(outPos), Floats.wrap(outUv), geometry.indices)
    }

    /** Scale displacement per-vertex by vertical position so hair roots stay pinned while tips stretch. */
    fun buildCastOffsetsGrid(
        source: Mesh,
        geometry: MeshGeometry,
        baseOffset: ShadowOffset2D,
        rootPinning: Float,
        parallax: Float,
        rig: RigIR,
        isWarpChild: Boolean,
    ): KeyGrid<MeshOffsets>? {
        val pos = geometry.positions.shared()
        val count = pos.size / 2
        if (count == 0) return null

        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val y = pos[i * 2 + 1]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val spanY = max(1e-4f, maxY - minY)

        // Convert canvas-pixel offset into parent space (approximate for warp normalized space)
        val scale = if (isWarpChild) {
            // Warp local space is 0..1; estimate ~1/250 of canvas size if bounds unavailable
            1f / max(80f, min(rig.canvas.width, rig.canvas.height) * 0.25f)
        } else 1f

        val dx = baseOffset.dx * scale
        val dy = baseOffset.dy * scale

        val existing = source.offsets
        val hasAngleX = rig.parameters.any { it.id == PARAM_ANGLE_X }
        val hasAngleY = rig.parameters.any { it.id == PARAM_ANGLE_Y }
        val pShift = (if (isWarpChild) 0.025f else 6f) * parallax

        fun vertexWeight(i: Int): Float {
            val t = ((pos[i * 2 + 1] - minY) / spanY).coerceIn(0f, 1f)
            return (1f - rootPinning) + rootPinning * t
        }

        if (existing != null && existing.axes.isNotEmpty()) {
            val axIdxX = existing.axes.indexOfFirst { it.parameter == PARAM_ANGLE_X }
            val axIdxY = existing.axes.indexOfFirst { it.parameter == PARAM_ANGLE_Y }
            val newCells = existing.cells.map { cell ->
                val coord = cell.coordinate.shared()
                val axValX = if (axIdxX >= 0) existing.axes[axIdxX].keys.shared()[coord[axIdxX]] else 0f
                val axValY = if (axIdxY >= 0) existing.axes[axIdxY].keys.shared()[coord[axIdxY]] else 0f
                val normX = (axValX / 30f).coerceIn(-1f, 1f)
                val normY = (axValY / 30f).coerceIn(-1f, 1f)
                val baseDeltas = cell.form.deltas.shared()
                val next = FloatArray(count * 2)
                for (i in 0 until count) {
                    val w = vertexWeight(i)
                    val bx = if (i * 2 < baseDeltas.size) baseDeltas[i * 2] else 0f
                    val by = if (i * 2 + 1 < baseDeltas.size) baseDeltas[i * 2 + 1] else 0f
                    next[i * 2] = bx + dx * w - normX * pShift * w
                    next[i * 2 + 1] = by + dy * w + normY * pShift * 0.6f * w
                }
                KeyCell(cell.coordinate, MeshOffsets(Floats.wrap(next)))
            }
            return KeyGrid(existing.axes, newCells)
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
                    val w = vertexWeight(i)
                    deltas[i * 2] = dx * w - normX * pShift * w
                    deltas[i * 2 + 1] = dy * w + normY * pShift * 0.6f * w
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

    /** Expand mesh outward along radial direction from center by [expandPx] (for inverse-mask cutter anti-bleed). */
    fun expandRadial(geometry: MeshGeometry, expandPx: Float, isWarpChild: Boolean, rig: RigIR): MeshGeometry {
        val pos = geometry.positions.toArray()
        val count = pos.size / 2
        if (count == 0 || expandPx <= 0f) return geometry
        var cx = 0f
        var cy = 0f
        for (i in 0 until count) {
            cx += pos[i * 2]
            cy += pos[i * 2 + 1]
        }
        cx /= count
        cy /= count
        val scale = if (isWarpChild) 1f / max(80f, min(rig.canvas.width, rig.canvas.height) * 0.25f) else 1f
        val delta = expandPx * scale
        for (i in 0 until count) {
            val vx = pos[i * 2] - cx
            val vy = pos[i * 2 + 1] - cy
            val len = sqrt(vx * vx + vy * vy)
            if (len > 1e-5f) {
                pos[i * 2] += (vx / len) * delta
                pos[i * 2 + 1] += (vy / len) * delta
            }
        }
        return geometry.copy(positions = Floats.wrap(pos))
    }
}
