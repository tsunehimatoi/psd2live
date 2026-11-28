package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import org.umamo.render.eval.DrawableSpaceMapping
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Makes a receiver-opacity silhouette visible only beyond its shifted, invisible cutter. */
class FormShadowAlgorithm : ChangingModifierAlgorithmPlugin {
    private companion object {
        const val PARAM_ANGLE_X = "ParamAngleX"
        const val PARAM_ANGLE_Y = "ParamAngleY"
        const val DRAW_ORDER_OFFSET = 0.5f
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
        val targets = input.meshes.filter { it.id in config.targets && it.geometry != null && it.visible }
        val opacity = ShadowGeometry.resolveOpacity(world, config.opacityOverride)
        if (opacity <= 0f) return ModifierModelChanges()
        val styleSoftness = ShadowGeometry.resolveSoftness(world, config.softnessOverride)
        val rasters = ArrayList<ModifierRasterOutput>()
        val azimuth = world.azimuth * (PI.toFloat() / 180f)
        val elevation = world.elevation * (PI.toFloat() / 180f)

        for (target in targets) {
            checkpoint()
            val geometry = requireNotNull(target.geometry)
            if (geometry.positions.size < 2) continue
            val (width, raster) = ShadowRasterGen.findMeshRgba(input, target.id, context) ?: continue
            val (height, rgba) = raster
            val sourcePixels = ShadowGeometry.rasterPixels(input, target, width, height) ?: continue
            val mapping = ShadowGeometry.spaceMapping(input, target)
            val (spanX, spanY) = silhouetteSpans(rgba, width, sourcePixels, mapping.localToWorld(geometry.positions.shared()))
                ?: continue
            val softness = if (config.softnessOverride == null && instance.presetGroup == "shadow:auto_face_side") {
                (styleSoftness * minOf(spanX, spanY) / 200f).coerceIn(0f, 48f)
            } else styleSoftness
            val coverage = config.coverage * (1.15f - 0.55f * sin(elevation))
            val (shiftX, shiftY) = when (config.proxy) {
                FormProxyKind.FACE_ELLIPSOID -> sin(azimuth) * spanX to cos(azimuth) * spanY * 0.45f
                FormProxyKind.SPHERE -> minOf(spanX, spanY).let { diameter ->
                    sin(azimuth) * diameter to cos(azimuth) * diameter
                }
                FormProxyKind.CYLINDER -> sin(azimuth) * spanX to 0f
            }
            val cutterRaster = ShadowRasterGen.generateSolidShadow(
                width, height, rgba, softness, 0xffffff, downscale = if (softness == 0f) 1 else 2,
            )
            val paddedCutter = ShadowGeometry.paddedGeometry(geometry, sourcePixels, width, height, cutterRaster)
            val (cutterGeometry, cutterOffsets) = shapeCutter(
                input, target, mapping, paddedCutter, ShadowOffset2D(shiftX * coverage, shiftY * coverage),
                spanX, spanY, config.cutterExpandPx,
            )
            val cutter = Mesh(
                id = instance.outputId("cutter:${target.id}"),
                name = "${target.name} Shadow Cutter",
                parent = target.parent,
                geometry = cutterGeometry,
                offsets = cutterOffsets,
                drawOrder = target.drawOrder,
                opacity = 0f,
                selectable = false,
                shapes = shadowShapes(target, 0f, 0f),
            )
            val shadow = Mesh(
                id = instance.outputId("form:${target.id}"),
                name = "${target.name} Side Shadow",
                parent = target.parent,
                blend = ColorBlend.MULTIPLY,
                maskedBy = listOf(cutter.id),
                invertMask = true,
                geometry = geometry.copy(uvs = Floats.wrap(sourcePixels)),
                offsets = target.offsets,
                channels = shadowChannels(target, opacity),
                drawOrder = target.drawOrder + DRAW_ORDER_OFFSET,
                opacity = target.opacity * opacity,
                culling = target.culling,
                shapes = shadowShapes(target, opacity, DRAW_ORDER_OFFSET),
            )
            val color = ShadowGeometry.resolveShadowColorRgb(world, config.colorOverride, input, listOf(target.id), context)
            val shadowRgba = ByteArray(rgba.size)
            for (i in rgba.indices step 4) {
                shadowRgba[i] = (color shr 16).toByte()
                shadowRgba[i + 1] = (color shr 8).toByte()
                shadowRgba[i + 2] = color.toByte()
                shadowRgba[i + 3] = rgba[i + 3]
            }
            rasters += output(instance, target, cutter, "cutter", cutterRaster.width, cutterRaster.height, cutterRaster.rgba)
            rasters += output(instance, target, shadow, "form", width, height, shadowRgba)
        }

        return ModifierModelChanges(rasters = rasters)
    }

    private fun output(
        instance: RigModifierInstance,
        owner: Mesh,
        mesh: Mesh,
        kind: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ModifierRasterOutput = ModifierRasterOutput(
        owner = owner,
        mesh = mesh,
        layer = SourceLayer(instance.outputId("$kind-raster:${owner.id}"), mesh.name, "Shadows", 0, 0, width, height, true),
        pixels = ModifierAlgorithmResource(rgba),
    )

    /** Compare the painted alpha bounds with the target's UV extent and posed mesh extent. */
    private fun silhouetteSpans(
        rgba: ByteArray,
        width: Int,
        source: FloatArray,
        world: FloatArray,
    ): Pair<Float, Float>? {
        var left = width
        var top = rgba.size / 4 / width
        var right = 0
        var bottom = 0
        for (i in 0 until rgba.size / 4) {
            if (rgba[i * 4 + 3] == 0.toByte()) continue
            left = minOf(left, i % width)
            top = minOf(top, i / width)
            right = maxOf(right, i % width + 1)
            bottom = maxOf(bottom, i / width + 1)
        }
        if (right <= left || bottom <= top) return null
        val spanX = span(world, 0) * ((right - left) / span(source, 0)).coerceAtMost(1f)
        val spanY = span(world, 1) * ((bottom - top) / span(source, 1)).coerceAtMost(1f)
        return spanX to spanY
    }

    private fun span(points: FloatArray, axis: Int): Float {
        val vertices = 0 until points.size / 2
        return (vertices.maxOf { points[it * 2 + axis] } - vertices.minOf { points[it * 2 + axis] }).coerceAtLeast(1e-4f)
    }

    private fun shadowChannels(target: Mesh, opacity: Float): Channels = target.channels.mapNotNull { (channel, grid) ->
        val correction = when (channel) {
            Channel.DRAW_ORDER -> { value: Float -> value + DRAW_ORDER_OFFSET }
            Channel.OPACITY -> { value: Float -> value * opacity }
            else -> return@mapNotNull null
        }
        channel to grid.copy(cells = grid.cells.map { cell ->
            cell.copy(form = ChannelValue.Scalar(correction((cell.form as ChannelValue.Scalar).value)))
        })
    }.toMap()

    private fun shadowShapes(target: Mesh, opacity: Float, drawOrderOffset: Float): List<BlendBinding<MeshShape>> =
        target.shapes.map { blend -> blend.copy(shapes = blend.shapes.map { shape ->
            shape?.copy(drawOrder = shape.drawOrder + drawOrderOffset, opacity = shape.opacity * opacity,
                multiply = Rgb.White, screen = Rgb.Black)
        }) }

    private fun shapeCutter(
        rig: RigIR,
        target: Mesh,
        mapping: DrawableSpaceMapping,
        geometry: MeshGeometry,
        shift: ShadowOffset2D,
        spanX: Float,
        spanY: Float,
        expandPx: Float,
    ): Pair<MeshGeometry, KeyGrid<MeshOffsets>?> {
        val parameters = rig.parameters.filter { it.id == PARAM_ANGLE_X || it.id == PARAM_ANGLE_Y }.associateBy { it.id }
        val rest = geometry.positions.shared()
        val vertices = (0 until rest.size / 2).toSet()
        val existing = target.offsets
        val axes = existing?.axes ?: parameters.values.map { p ->
            KeyAxis(p.id, Floats.wrap(floatArrayOf(p.min, p.default, p.max).distinct().toFloatArray()))
        }
        val angleX = axes.indexOfFirst { it.parameter == PARAM_ANGLE_X }
        val angleY = axes.indexOfFirst { it.parameter == PARAM_ANGLE_Y }

        fun normalized(axis: Int, coord: IntArray): Float {
            if (axis < 0) return 0f
            val p = parameters[axes[axis].parameter] ?: return 0f
            val v = axes[axis].keys.shared()[coord[axis]] - p.default
            val range = if (v < 0f) p.default - p.min else p.max - p.default
            return if (range > 0f) (v / range).coerceIn(-1f, 1f) else 0f
        }

        fun position(coord: IntArray, deltas: FloatArray?): FloatArray {
            val local = if (deltas == null) rest else FloatArray(rest.size) { rest[it] + deltas[it] }
            val world = mapping.localToWorld(local)
            val placed = world.copyOf()
            val cx = vertices.sumOf { placed[it * 2].toDouble() }.toFloat() / vertices.size
            val cy = vertices.sumOf { placed[it * 2 + 1].toDouble() }.toFloat() / vertices.size
            val dx = shift.dx - normalized(angleX, coord) * spanX * 0.08f
            val dy = shift.dy - normalized(angleY, coord) * spanY * 0.05f
            for (i in placed.indices step 2) {
                val x = placed[i] - cx
                val y = placed[i + 1] - cy
                val radius = hypot(x, y)
                val grow = if (radius > 1e-4f) expandPx / radius else 0f
                placed[i] += dx + x * grow
                placed[i + 1] += dy + y * grow
            }
            return mapping.worldToLocalLinearized(placed, local, world, vertices)
        }

        if (existing == null && axes.isEmpty()) {
            return geometry.copy(positions = Floats.wrap(position(IntArray(0), null))) to null
        }
        fun cell(coord: Ints, deltas: FloatArray?) = KeyCell(coord, MeshOffsets(Floats.wrap(
            position(coord.shared(), deltas).let { next -> FloatArray(rest.size) { next[it] - rest[it] } },
        )))
        val cells = if (existing != null) {
            existing.cells.map { cell(it.coordinate, it.form.deltas.shared()) }
        } else {
            val coordinates = axes.fold(listOf(IntArray(0))) { prefix, axis ->
                prefix.flatMap { coord -> axis.keys.shared().indices.map { coord + it } }
            }
            coordinates.map { cell(Ints.wrap(it), null) }
        }
        return geometry to KeyGrid(axes, cells)
    }
}
