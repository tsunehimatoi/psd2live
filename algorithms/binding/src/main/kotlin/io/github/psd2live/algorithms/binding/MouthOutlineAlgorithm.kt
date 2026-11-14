package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.DeformPath
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.PathPoint
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.SourceLayer
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*
import java.util.UUID

/** Builds outline ribbons from the source trace, neutral scaffold contour and declared motion providers. */
class MouthOutlineAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        val context = input.context
        val captured = MouthOutlineInputs.decode(context)
        val targets = instance.targets.mapTo(HashSet()) { it.id }
        require(instance.targets.all { it.kind == "mesh" } && targets.containsAll(captured.targets.keys))
        val thickness = instance.config["values"]?.jsonObject?.get("thickness")?.jsonPrimitive?.float ?: 1.5f
        val color = instance.config["color"]?.jsonPrimitive?.int
        require(thickness in .5f..8f && (color == null || color in 0..0xffffff))
        if (captured.targets.isEmpty()) return ModifierModelChanges()
        val source = captured.targets.keys.associateWith { requireNotNull(input.mesh(it)) { "Missing outline owner: $it" } }
        // Lowering observes the axes and shape kinds on the surrounding rig, while the geometry conversion
        // only needs the captured owners. Textures and authored sidecars are attached by the host.
        val model = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, parts = input.parts,
            deformers = input.deformers, meshes = source.values.toList(), glues = input.glues))
        val live = model.drawables.associateBy { it.id.raw }
        val fitted = captured.fitted?.rig()
        val head = captured.head?.rig(checkpoint)
        val topologies = HashMap<String, MouthOutlineInputs.Topology>()
        val rasters = buildList {
            for ((id, target) in captured.targets) {
                checkpoint()
                val mouth = target.fittedProvider?.let { provider ->
                    requireProvider(context, provider, "mouth/fitted", id)
                    requireNotNull(fitted)
                }
                val turn = if (target.headOnMesh) {
                    val provider = requireNotNull(captured.head).provider
                    require(context.upstream.any { it.instanceId == provider && it.type in setOf("face/fitted", "face.depth-turn", "face") &&
                        ModifierTarget("mesh", id) in it.targets }) { "Missing outline head provider for $id" }
                    requireNotNull(head)
                } else null
                if (target.rasterColumns.isEmpty() || target.sides.isEmpty()) continue
                val layer = captured.artwork.layerForTarget(id)
                val raster = captured.artwork.raster(layer)
                val rgb = color ?: MouthOutlineRaster.perimeterColor(raster.width, raster.height, raster.rgba(),
                    captured.alphaThreshold, checkpoint)
                val topology = target.topology?.let { digest -> topologies.getOrPut(digest) { captured.topology(target, checkpoint) } }
                    ?: captured.topology(target, checkpoint)
                val positions = topology.positions
                val triangles = topology.triangles
                val neutral = if (target.contourOwner) MouthContour.ownerContour(positions, triangles) else null
                val columns = MouthContour.uniformColumns(neutral?.positions ?: positions, neutral?.triangles ?: triangles)
                if (columns.size < 2) continue
                val owner = live.getValue(id)
                val legacy = MouthOutlineMotion(target.aperture, captured.curve.points.map { it.x to it.y }, captured.tuning)
                for (side in target.sides.sorted()) {
                    val art = MouthOutlineRaster.render(target.rasterColumns, side, rgb, thickness, target::rasterToCanvas, checkpoint)
                    add(ribbon(instance, source.getValue(id), owner, target, columns, thickness, side,
                        art, legacy, mouth, turn, model, checkpoint))
                }
            }
        }
        return ModifierModelChanges(rasters = rasters)
    }

    private fun requireProvider(context: ModifierAlgorithmContext, provider: String, type: String, id: String) {
        require(context.upstream.any { it.instanceId == provider && it.capability == type && ModifierTarget("mesh", id) in it.targets }) {
            "Missing outline $type provider for $id"
        }
    }

    private fun ribbon(
        instance: RigModifierInstance, expected: Mesh, owner: Drawable, target: MouthOutlineInputs.Target,
        columns: List<MouthColumn>, thickness: Float, side: Int, art: MouthOutlineRaster.Result,
        legacy: MouthOutlineMotion, mouth: MouthRig?, turn: HeadTurn?, model: PuppetModel, checkpoint: () -> Unit,
    ): ModifierRasterOutput {
        val stroke = MouthOutlineStroke(columns, side, thickness)
        val positions = stroke.parentPositions(target.frame)
        val triangles = stroke.indices()
        val uvs = stroke.positions().also { points ->
            for (i in points.indices step 2) {
                val (x, y) = target.toCanvas(points[i], points[i + 1])
                points[i] = (x - art.left).coerceIn(0f, art.width.toFloat())
                points[i + 1] = (y - art.top).coerceIn(0f, art.height.toFloat())
            }
        }
        val geometry = if (mouth == null) legacy.ribbonGrid(stroke, target.frame, form, open, checkpoint)
        else {
            val motion = MouthOutlineFittedMotion(columns, side, thickness, target.frame)
            motion.geometry(mouth.keyed(form, open) { pose ->
                MouthOutlineFittedMotion.PathPose(motion.sourcePath.map { (x, y) -> mouth.point(x, y, pose) })
            }, checkpoint)
        }
        val name = if (side == 0) target.upperName else target.lowerName
        val id = instance.outputId("mouth-lip:${expected.id}:$side")
        val base = owner.copy(id = DrawableId(id), name = name, mesh = DrawableMesh(positions, uvs, triangles),
            geometryGrid = geometry, blendMode = BlendMode.Normal, texturePage = 0, atlasTileId = null,
            textureSourceId = null, drawOrder = (owner.drawOrder + 1f).coerceAtMost(1000f), blendShapes = emptyList())
        val mouthShapes = mouth?.shapes({ pose ->
            stroke.delta(target.frame, positions) { x, y -> mouth.point(x, y, pose) }
        }, visible = { if (it) 1f else Float.NaN }).orEmpty()
        val frame = target.frame
        val headShapes = turn?.shapes(stroke.positions(), Bounds(frame.left, frame.top, frame.right, frame.bottom),
            HeadShell.SKIN, angleX, angleY).orEmpty()
        val posed = base.copy(blendShapes = neutralize(base, mouthShapes + headShapes))
        // Outline output reuses the live owner's parent; a canonical ribbon's private carrier is not an output.
        val active = model.parameters.mapTo(hashSetOf()) { it.id }
        val axes = listOf(form, open).filterNot { it in active }.map {
            Parameter(it, it.raw, if (it == form) -1f else 0f, 1f, 0f)
        }
        val loweringInput = model.copy(drawables = listOf(posed), parameters = model.parameters + axes)
        val lowered = BlendShapeLowering({ _, meshName -> meshName }, checkpoint).lawful(loweringInput)
        // Raster outputs own a mesh, not additional carrier deformers. Keep source forms when
        // lowering needs a carrier; the export lowering can retain that complete hierarchy.
        val local = if (lowered.drawables.single().parentDeformerId == owner.parentDeformerId) lowered else loweringInput
        val drawable = BindingProjection.retain(local, active, mapOf(open to 1f), checkpoint).drawables.single()
            .copy(parentDeformerId = owner.parentDeformerId)
        val points = stroke.handles(frame).mapNotNull { handle ->
            try {
                BindingPaths.bind(positions, triangles, handle.x, handle.y, handle.corner).let {
                    PathPoint(it.a, it.b, it.c, it.wa, it.wb, it.wc, it.corner)
                }
            } catch (_: IllegalArgumentException) { null }
        }
        val canonical = UUID.nameUUIDFromBytes("psd2live:generated-path:${target.canonical}_lip_$side:lip".encodeToByteArray())
        val paths = if (points.size < 2) emptyList() else listOf(DeformPath(
            instance.outputId("mouth-path:${expected.id}:$side:$canonical"), id, points,
            org.umamo.runtime.model.DeformPath.DEFAULT_WIDTH, org.umamo.runtime.model.DeformPath.DEFAULT_HARDNESS, false, 2))
        val layer = SourceLayer(instance.outputId("mouth-art:${expected.id}:$side"), name, target.groupPath,
            art.left, art.top, art.width, art.height, target.sourceVisible)
        return ModifierRasterOutput(expected, PuppetIr.meshToIr(drawable), layer, ModifierAlgorithmResource(art.rgba), paths)
    }

    private fun neutralize(drawable: Drawable, shapes: List<BlendShapeBinding<MeshForm>>): List<BlendShapeBinding<MeshForm>> {
        val pose: (ParameterId) -> Float = { if (it.raw == "ParamEyeLOpen" || it.raw == "ParamEyeROpen") 1f else 0f }
        val opacity by lazy { drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, pose) }
        val order by lazy { drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose) }
        val multiply by lazy { drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose) }
        val screen by lazy { drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose) }
        val rest by lazy { meshGridDefaultDeltas(drawable, pose) }
        return shapes.map { binding ->
            binding.copy(forms = binding.forms.map { value ->
                value?.let {
                    val base = if (binding.parameterId == angleX || binding.parameterId == angleY) rest else null
                    val deltas = if (base == null) it.positionDeltas else FloatArray(base.size) { i -> it.positionDeltas[i] + base[i] }
                    MeshForm(deltas, order, if (binding.parameterId in MouthRig.OPENING && !it.opacity.isNaN())
                        it.opacity * drawable.opacity else opacity, multiply, screen)
                }
            })
        }
    }

    companion object {
        private val form = ParameterId("ParamMouthForm")
        private val open = ParameterId("ParamMouthOpenY")
        private val angleX = ParameterId("ParamAngleX")
        private val angleY = ParameterId("ParamAngleY")
    }
}
