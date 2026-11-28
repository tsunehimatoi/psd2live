package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.jsonObject
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.inversePlacementAffine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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

    /** Maps normalized atlas UVs back to the source raster, including rotated and scaled placements. */
    fun rasterPixels(rig: RigIR, mesh: Mesh, width: Int, height: Int): FloatArray? {
        val uv = mesh.geometry?.uvs?.toArray() ?: return null
        val textures = rig.textures
        val tileId = mesh.tile ?: rig.meshes.firstOrNull { it.id == mesh.textureSource }?.tile
        val tile = textures.tiles.firstOrNull { it.id == tileId }
        val placement = tile?.placement
        if (!textures.uvsAddressPages || placement == null) {
            if (textures.uvsAddressPages && textures.pages.isNotEmpty()) return null
            for (i in uv.indices step 2) {
                uv[i] *= width
                uv[i + 1] *= height
            }
            return uv
        }
        val page = textures.tilePages.getOrNull(placement.page)
        val renderPage = textures.pages.getOrNull(textures.bindings[mesh.id] ?: mesh.page)
        val pageWidth = page?.width ?: renderPage?.width ?: return null
        val pageHeight = page?.height ?: renderPage?.height ?: return null
        val inverse = inversePlacementAffine(AtlasPlacement(
            placement.page, placement.x, placement.y, placement.scaleX, placement.scaleY, placement.rotation,
        )) ?: return null
        for (i in uv.indices step 2) {
            val x = uv[i] * pageWidth
            val y = uv[i + 1] * pageHeight
            uv[i] = (inverse[0] * x + inverse[1] * y + inverse[2]) * width / tile.width
            uv[i + 1] = (inverse[3] * x + inverse[4] * y + inverse[5]) * height / tile.height
        }
        return uv
    }

    /** Opens room for the blur without scaling the opaque art. UVs stay in pixels of the generated raster. */
    fun paddedGeometry(
        geometry: MeshGeometry,
        sourcePixels: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
        raster: ShadowRasterResult,
    ): MeshGeometry {
        val positions = geometry.positions.toArray()
        val pixels = sourcePixels.copyOf()
        val vertices = pixels.indices.step(2)
        val minX = vertices.minOf { pixels[it] }
        val minY = vertices.minOf { pixels[it + 1] }
        val spanX = (vertices.maxOf { pixels[it] } - minX).coerceAtLeast(1f)
        val spanY = (vertices.maxOf { pixels[it + 1] } - minY).coerceAtLeast(1f)
        val indices = geometry.indices.shared()
        var bestArea = 0f
        var xx = 0f
        var xy = 0f
        var yx = 0f
        var yy = 0f
        for (i in indices.indices step 3) {
            val a = indices[i] * 2
            val b = indices[i + 1] * 2
            val c = indices[i + 2] * 2
            val u1 = pixels[b] - pixels[a]
            val v1 = pixels[b + 1] - pixels[a + 1]
            val u2 = pixels[c] - pixels[a]
            val v2 = pixels[c + 1] - pixels[a + 1]
            val area = u1 * v2 - v1 * u2
            if (abs(area) <= bestArea || abs(area) < 0.01f) continue
            bestArea = abs(area)
            val dx1 = positions[b] - positions[a]
            val dx2 = positions[c] - positions[a]
            val dy1 = positions[b + 1] - positions[a + 1]
            val dy2 = positions[c + 1] - positions[a + 1]
            xx = (dx1 * v2 - dx2 * v1) / area
            xy = (dx2 * u1 - dx1 * u2) / area
            yx = (dy1 * v2 - dy2 * v1) / area
            yy = (dy2 * u1 - dy1 * u2) / area
        }
        val scaleX = raster.contentWidth.toFloat() / sourceWidth
        val scaleY = raster.contentHeight.toFloat() / sourceHeight
        val padX = (raster.width - raster.contentWidth) / 2f
        val padY = (raster.height - raster.contentHeight) / 2f
        for (i in vertices) {
            val dx = if (bestArea > 0f) ((pixels[i] - minX) / spanX * 2f - 1f) * padX / scaleX else 0f
            val dy = if (bestArea > 0f) ((pixels[i + 1] - minY) / spanY * 2f - 1f) * padY / scaleY else 0f
            positions[i] += xx * dx + xy * dy
            positions[i + 1] += yx * dx + yy * dy
            pixels[i] = padX + (pixels[i] + dx) * scaleX
            pixels[i + 1] = padY + (pixels[i + 1] + dy) * scaleY
        }
        return geometry.copy(positions = Floats.wrap(positions), uvs = Floats.wrap(pixels))
    }

    fun spaceMapping(rig: RigIR, mesh: Mesh, values: Map<String, Float> = emptyMap()): DrawableSpaceMapping =
        spaceMapping(PuppetIr.toPuppet(rig), mesh, values)

    private fun spaceMapping(puppet: PuppetModel, mesh: Mesh, values: Map<String, Float>): DrawableSpaceMapping =
        drawableSpaceMapping(puppet, values.mapKeys { ParameterId(it.key) }, DrawableId(mesh.id)) ?: DrawableSpaceMapping(null)

    /**
     * Moves each keyed caster in canvas pixels before mapping the new shape back through its actual parent chain.
     * The original grid stays intact and an unkeyed caster follows the available head parameters.
     */
    fun projectCast(
        source: Mesh,
        geometry: MeshGeometry,
        baseOffset: ShadowOffset2D,
        rootPinning: Float,
        parallax: Float,
        rig: RigIR,
        puppet: PuppetModel,
    ): Pair<MeshGeometry, KeyGrid<MeshOffsets>?> {
        val positions = geometry.positions.shared()
        val vertices = (0 until positions.size / 2).toSet()
        val angleParams = rig.parameters.filter { it.id == PARAM_ANGLE_X || it.id == PARAM_ANGLE_Y }.associateBy { it.id }
        val existing = source.offsets
        val axes = existing?.axes?.takeIf { it.isNotEmpty() } ?: angleParams.values.mapNotNull { parameter ->
            val keys = floatArrayOf(parameter.min, parameter.default, parameter.max).distinct().sorted()
            if (keys.size < 2) null else KeyAxis(parameter.id, Floats.wrap(keys.toFloatArray()))
        }
        fun normalized(parameterId: String, values: Map<String, Float>): Float {
            val parameter = angleParams[parameterId] ?: return 0f
            val diff = (values[parameterId] ?: parameter.default) - parameter.default
            val reach = if (diff < 0f) parameter.default - parameter.min else parameter.max - parameter.default
            return if (reach > 0f) (diff / reach).coerceIn(-1f, 1f) else 0f
        }
        fun projected(values: Map<String, Float>, delta: FloatArray?): FloatArray {
            val posed = if (delta == null) positions else FloatArray(positions.size) { positions[it] + delta[it] }
            val mapping = spaceMapping(puppet, source, values)
            val before = mapping.localToWorld(posed)
            val after = before.copyOf()
            val top = vertices.maxOf { before[it * 2 + 1] }
            val bottom = vertices.minOf { before[it * 2 + 1] }
            val height = (top - bottom).coerceAtLeast(0.01f)
            val pShift = 6f * parallax
            val dx = baseOffset.dx - normalized(PARAM_ANGLE_X, values) * pShift
            val dy = baseOffset.dy + normalized(PARAM_ANGLE_Y, values) * pShift * 0.6f
            for (i in vertices) {
                val weight = 1f - rootPinning + rootPinning * (top - before[i * 2 + 1]) / height
                after[i * 2] += dx * weight
                after[i * 2 + 1] -= dy * weight
            }
            return mapping.worldToLocal(after, posed, vertices)
        }

        if (axes.isEmpty()) {
            val moved = projected(emptyMap(), existing?.cells?.singleOrNull()?.form?.deltas?.shared())
            return geometry.copy(positions = Floats.wrap(moved)) to null
        }
        fun cell(coordinate: Ints, delta: FloatArray?): KeyCell<MeshOffsets> {
            val coord = coordinate.shared()
            val values = axes.mapIndexed { index, axis -> axis.parameter to axis.keys.shared()[coord[index]] }.toMap()
            val moved = projected(values, delta)
            return KeyCell(coordinate, MeshOffsets(Floats.wrap(FloatArray(moved.size) { moved[it] - positions[it] })))
        }
        val cells = if (existing != null && existing.axes.isNotEmpty()) {
            existing.cells.map { cell(it.coordinate, it.form.deltas.shared()) }
        } else buildList {
            val coord = IntArray(axes.size)
            val delta = existing?.cells?.singleOrNull()?.form?.deltas?.shared()
            fun emit(depth: Int) {
                if (depth == axes.size) {
                    add(cell(Ints.wrap(coord.copyOf()), delta))
                    return
                }
                for (i in 0 until axes[depth].keys.size) {
                    coord[depth] = i
                    emit(depth + 1)
                }
            }
            emit(0)
        }
        return geometry to KeyGrid(axes, cells)
    }
}
