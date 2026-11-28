package io.github.psd2live.algorithms.shadow

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.max

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
        val casterMeshes = config.casters.distinct().mapNotNull { meshById[it] }.filter { it.geometry != null }
        val receiverMeshes = config.receivers.distinct().mapNotNull { meshById[it] }
        if (casterMeshes.isEmpty() || receiverMeshes.isEmpty()) return ModifierModelChanges()

        val sortedReceivers = receiverMeshes.map { it.id }.sorted()
        val opacity = ShadowGeometry.resolveOpacity(world, config.opacityOverride)
        val softness = ShadowGeometry.resolveSoftness(world, config.softnessOverride)
        val colorRgb = ShadowGeometry.resolveShadowColorRgb(world, config.colorOverride, input, sortedReceivers, context)
        val offset = ShadowGeometry.lightOffset(world.azimuth, world.elevation, config.distance)

        val drawOrder = resolveDrawOrder(config.placement, casterMeshes, receiverMeshes, input)
        val useOffscreen = world.compositeMode != ShadowCompositeMode.COMPAT_MERGED

        val rasters = ArrayList<ModifierRasterOutput>()
        val childRefs = ArrayList<ChildRef>()

        for (caster in casterMeshes) {
            checkpoint()
            val geom = caster.geometry ?: continue
            val srcRaster = ShadowRasterGen.findMeshRgba(input, caster.id, context) ?: continue
            val (srcW, srcHAndBytes) = srcRaster
            val (srcH, srcRgba) = srcHAndBytes

            val shadowRaster = ShadowRasterGen.generateSolidShadow(srcW, srcH, srcRgba, softness, colorRgb)
            val expandedGeom = ShadowGeometry.expandGeometryForPadding(geom, srcW, srcH, shadowRaster)
            val isWarpChild = caster.parent != null && input.deformers.any { it.id == caster.parent && it is Deformer.Warp }
            val offsetsGrid = ShadowGeometry.buildCastOffsetsGrid(
                caster, expandedGeom, offset, config.rootPinning, config.parallax, input, isWarpChild
            )

            // Apply static rest-pose offset to positions if no angle grid was emitted
            val restPositions = if (offsetsGrid == null) {
                val pos = expandedGeom.positions.toArray()
                val scale = if (isWarpChild) 1f / max(80f, minOf(input.canvas.width, input.canvas.height) * 0.25f) else 1f
                val minY = (0 until pos.size / 2).minOfOrNull { pos[it * 2 + 1] } ?: 0f
                val maxY = (0 until pos.size / 2).maxOfOrNull { pos[it * 2 + 1] } ?: 1f
                val spanY = max(1e-4f, maxY - minY)
                for (i in 0 until pos.size / 2) {
                    val t = ((pos[i * 2 + 1] - minY) / spanY).coerceIn(0f, 1f)
                    val w = (1f - config.rootPinning) + config.rootPinning * t
                    pos[i * 2] += offset.dx * scale * w
                    pos[i * 2 + 1] += offset.dy * scale * w
                }
                expandedGeom.copy(positions = Floats.wrap(pos))
            } else expandedGeom

            val shadowMeshId = instance.outputId("cast:${caster.id}")
            val shadowMesh = Mesh(
                id = shadowMeshId,
                name = "${caster.name} Shadow",
                parent = caster.parent,
                blend = if (useOffscreen) ColorBlend.NORMAL else ColorBlend.MULTIPLY,
                maskedBy = if (useOffscreen) emptyList() else sortedReceivers,
                geometry = restPositions,
                offsets = offsetsGrid,
                drawOrder = drawOrder,
                opacity = if (useOffscreen) 1f else opacity,
            )
            val layerKey = instance.outputId("raster:${caster.id}")
            val sourceLayer = SourceLayer(
                key = layerKey,
                name = "${caster.name} Shadow",
                groupPath = "Shadows",
                left = 0,
                top = 0,
                width = shadowRaster.width,
                height = shadowRaster.height,
                visible = true,
            )
            rasters += ModifierRasterOutput(
                owner = caster,
                mesh = shadowMesh,
                layer = sourceLayer,
                pixels = ModifierAlgorithmResource(shadowRaster.rgba),
                uvSpace = ModifierRasterOutput.UvSpace.RASTER_PIXELS,
            )
            childRefs += ChildRef.MeshRef(shadowMeshId)
        }

        if (rasters.isEmpty()) return ModifierModelChanges()

        return if (useOffscreen) {
            val partId = instance.outputId("part")
            val isolatedPart = Part(
                id = partId,
                name = config.name,
                children = childRefs,
                visible = true,
                groupMode = GroupMode.ISOLATED,
                drawOrder = drawOrder.toInt(),
                composite = Composite(
                    blend = ColorBlend.MULTIPLY,
                    alphaBlend = AlphaBlend.OVER,
                    maskedBy = sortedReceivers,
                    opacity = opacity,
                ),
            )
            val nextParts = input.parts + isolatedPart
            val nextRootChildren = input.rootChildren + ChildRef.PartRef(partId)
            val withPart = input.copy(parts = nextParts, rootChildren = nextRootChildren)
            val derivedRoot = PuppetIr.toIr(PuppetIr.toPuppet(withPart).withDerivedRenderRoot()).renderRoot
            ModifierModelChanges(
                parts = ModifierObjectChanges(added = listOf(isolatedPart)),
                rootChildren = ModifierFieldChange(input.rootChildren, nextRootChildren),
                renderRoot = ModifierFieldChange(input.renderRoot, derivedRoot),
                rasters = rasters,
            )
        } else {
            ModifierModelChanges(rasters = rasters)
        }
    }

    private fun resolveDrawOrder(
        placement: ShadowPlacement,
        casters: List<Mesh>,
        receivers: List<Mesh>,
        rig: RigIR,
    ): Float {
        val maxReceiver = receivers.maxOfOrNull { it.drawOrder } ?: 500f
        val minCaster = casters.minOfOrNull { it.drawOrder } ?: 550f
        return when (placement) {
            ShadowPlacement.ABOVE_FACE -> (maxReceiver + 5f).coerceAtMost(minCaster - 1f)
            ShadowPlacement.ABOVE_EYES -> {
                val eyeOrder = rig.meshes.filter {
                    val n = it.name.lowercase()
                    "eye" in n || "lash" in n || "眉" in n || "眼" in n || "睫" in n
                }.maxOfOrNull { it.drawOrder } ?: (maxReceiver + 20f)
                (eyeOrder + 2f).coerceAtMost(minCaster - 1f)
            }
            ShadowPlacement.BELOW_CASTER -> (minCaster - 1f).coerceAtLeast(maxReceiver + 1f)
        }
    }
}
