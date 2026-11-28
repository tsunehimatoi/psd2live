package io.github.psd2live.algorithms.shadow

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.ceil

/**
 * Generates cast shadows from one or more casters onto one or more receivers.
 * Uses Cubism 5.3+ Offscreen Drawing (GroupMode.ISOLATED) by default so overlapping
 * side-by-side bangs never double-darken, while masking cleanly inside receivers.
 */
class CastShadowAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        checkpoint()
        val world = ShadowGeometry.resolveWorldConfig(context)
        if (!world.previewEnabled) return ModifierModelChanges()

        val config = CastShadowConfig.fromJson(instance.config)
        val meshById = input.meshes.associateBy { it.id }
        fun eligible(ids: List<String>): List<Mesh> = ids.distinct().mapNotNull(meshById::get)
            .filter { it.visible && it.geometry?.positions?.size?.let { size -> size >= 6 } == true }
        val receiverMeshes = eligible(config.receivers)
        val sortedReceivers = receiverMeshes.map { it.id }.sorted()
        fun receives(caster: String, receiver: String): Boolean =
            caster != receiver && (config.receiversByCaster?.let { receiver in it[caster].orEmpty() } ?: true)
        val casterMeshes = eligible(config.casters).filter { caster -> sortedReceivers.any { receives(caster.id, it) } }
        if (casterMeshes.isEmpty() || receiverMeshes.isEmpty()) return ModifierModelChanges()

        val opacity = ShadowGeometry.resolveOpacity(world, config.opacityOverride)
        if (opacity <= 0f) return ModifierModelChanges()
        val styleSoftness = ShadowGeometry.resolveSoftness(world, config.softnessOverride)
        val softness = if (config.softnessOverride == null && instance.presetGroup?.startsWith("shadow:auto_") == true) {
            (styleSoftness * config.distance / 20f).coerceIn(0f, 48f)
        } else styleSoftness
        val colorRgb = ShadowGeometry.resolveShadowColorRgb(world, config.colorOverride, input, sortedReceivers, context)
        val offset = ShadowGeometry.lightOffset(world.azimuth, world.elevation, config.distance)
        val target = RuntimeTarget.valueOf(input.authoring.runtime)
        val useOffscreen = target.supports(RuntimeFeature.PartComposite) && world.compositeMode != ShadowCompositeMode.COMPAT_MERGED
        val puppet = PuppetIr.toPuppet(input)

        val projected = casterMeshes.mapNotNull { caster ->
            checkpoint()
            val geom = requireNotNull(caster.geometry)
            val (srcW, raster) = ShadowRasterGen.findMeshRgba(input, caster.id, context) ?: return@mapNotNull null
            val (srcH, srcRgba) = raster
            val pixels = ShadowGeometry.rasterPixels(input, caster, srcW, srcH) ?: return@mapNotNull null
            val shadowRaster = ShadowRasterGen.generateSolidShadow(srcW, srcH, srcRgba, softness, colorRgb)
            val padded = ShadowGeometry.paddedGeometry(geom, pixels, srcW, srcH, shadowRaster)
            val (rest, offsets) = ShadowGeometry.projectCast(caster, padded, offset, config.rootPinning, config.parallax, input, puppet)
            val shadowMesh = Mesh(
                id = instance.outputId("cast:${caster.id}"),
                name = "${caster.name} Shadow",
                parent = caster.parent,
                geometry = rest,
                offsets = offsets,
                opacity = if (useOffscreen) caster.opacity else 0f,
                shapes = caster.shapes.map { binding -> binding.copy(shapes = binding.shapes.map { shape ->
                    shape?.copy(opacity = if (useOffscreen) shape.opacity else 0f, multiply = Rgb.White, screen = Rgb.Black)
                }) },
            )
            output(caster, shadowMesh, instance.outputId("raster:${caster.id}"), shadowRaster)
        }
        if (projected.isEmpty()) return ModifierModelChanges()

        if (!useOffscreen) {
            // Traditional Cubism clipping takes the union of all mask meshes. Only the receiver-colored
            // mesh is drawn, so independent caster poses remain editable without accumulating darkness.
            val merged = receiverMeshes.mapNotNull { receiver ->
                checkpoint()
                val casters = projected.filter { receives(it.owner.id, receiver.id) }
                if (casters.isEmpty()) return@mapNotNull null
                val geom = requireNotNull(receiver.geometry)
                val (w, raster) = ShadowRasterGen.findMeshRgba(input, receiver.id, context) ?: return@mapNotNull null
                val (h, rgba) = raster
                val pixels = ShadowGeometry.rasterPixels(input, receiver, w, h) ?: return@mapNotNull null
                val tinted = ShadowRasterGen.generateSolidShadow(w, h, rgba, 0f, colorRgb, downscale = 1)
                val mesh = Mesh(
                    id = instance.outputId("merged:${receiver.id}"),
                    name = "${receiver.name} Cast Shadow",
                    parent = receiver.parent,
                    blend = ColorBlend.MULTIPLY,
                    maskedBy = casters.map { it.mesh.id },
                    geometry = ShadowGeometry.paddedGeometry(geom, pixels, w, h, tinted),
                    offsets = receiver.offsets,
                    drawOrder = resolveDrawOrder(config.placement, casters.map { it.owner }, listOf(receiver), input),
                    opacity = opacity * receiver.opacity,
                    shapes = receiver.shapes,
                )
                output(receiver, mesh, instance.outputId("merged-raster:${receiver.id}"), tinted)
            }
            return if (merged.isEmpty()) ModifierModelChanges() else ModifierModelChanges(rasters = projected + merged)
        }

        val receiversByProjection = receiverMeshes.sortedBy { it.id }
            .groupBy { receiver -> projected.filter { receives(it.owner.id, receiver.id) } }
            .filterKeys { it.isNotEmpty() }
        val emittedCasters = HashSet<String>()
        val rasters = ArrayList<ModifierRasterOutput>()
        val parts = receiversByProjection.entries.mapIndexed { index, (sources, receivers) ->
            val suffix = receivers.first().id
            val drawOrder = resolveDrawOrder(config.placement, sources.map { it.owner }, receivers, input)
            val children = sources.map { source ->
                val first = emittedCasters.add(source.owner.id)
                val mesh = source.mesh.copy(
                    id = if (first) source.mesh.id else instance.outputId("cast:${source.owner.id}:for:$suffix"),
                    drawOrder = drawOrder,
                )
                val layer = if (first) source.layer else source.layer.copy(key = instance.outputId("raster:${source.owner.id}:for:$suffix"))
                rasters += ModifierRasterOutput(source.owner, mesh, layer, source.pixels)
                ChildRef.MeshRef(mesh.id)
            }
            Part(
                id = instance.outputId(if (index == 0) "part" else "part:$suffix"),
                name = config.name,
                children = children,
                groupMode = GroupMode.ISOLATED,
                drawOrder = ceil(drawOrder).toInt(),
                composite = Composite(
                    blend = ColorBlend.MULTIPLY,
                    maskedBy = receivers.map { it.id },
                    opacity = opacity,
                ),
            )
        }
        if (parts.isEmpty()) return ModifierModelChanges()
        val nextRootChildren = input.rootChildren + parts.map { ChildRef.PartRef(it.id) }
        val withPart = input.copy(parts = input.parts + parts, rootChildren = nextRootChildren)
        val derivedRoot = PuppetIr.toIr(PuppetIr.toPuppet(withPart).withDerivedRenderRoot()).renderRoot
        return ModifierModelChanges(
            parts = ModifierObjectChanges(added = parts),
            rootChildren = ModifierFieldChange(input.rootChildren, nextRootChildren),
            renderRoot = ModifierFieldChange(input.renderRoot, derivedRoot),
            rasters = rasters,
        )
    }

    private fun output(owner: Mesh, mesh: Mesh, id: String, raster: ShadowRasterResult): ModifierRasterOutput {
        val layer = SourceLayer(id, mesh.name, "Shadows", 0, 0, raster.width, raster.height, true)
        return ModifierRasterOutput(owner, mesh, layer, ModifierAlgorithmResource(raster.rgba),
            uvSpace = ModifierRasterOutput.UvSpace.RASTER_PIXELS)
    }

    private fun resolveDrawOrder(
        placement: ShadowPlacement,
        casters: List<Mesh>,
        receivers: List<Mesh>,
        rig: RigIR,
    ): Float {
        val aboveReceiver = receivers.maxOf { it.drawOrder } + 1f
        val belowCaster = (casters.minOf { it.drawOrder } - 1f).coerceAtLeast(aboveReceiver)
        return when (placement) {
            ShadowPlacement.ABOVE_FACE -> aboveReceiver
            ShadowPlacement.ABOVE_EYES -> {
                val eyeOrder = rig.meshes.filter {
                    val n = it.name.lowercase()
                    "eye" in n || "lash" in n || "眉" in n || "眼" in n || "睫" in n
                }.maxOfOrNull { it.drawOrder }
                (eyeOrder?.plus(1f) ?: aboveReceiver).coerceIn(aboveReceiver, belowCaster)
            }
            ShadowPlacement.BELOW_CASTER -> belowCaster
        }
    }
}
