package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthOutlineInputs
import io.github.psd2live.algorithms.binding.MouthOutlineMotion
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr

/** Host capture for the outline candidate. No rig or native ribbon is generated to populate it. */
internal object RigMouthOutlineSource {
    data class Owner(
        val layer: String,
        val canonical: String,
        val fittedProvider: String? = null,
        val headOnMesh: Boolean = false,
        val contourOwner: Boolean = true,
    )

    fun capture(
        analysis: PipelineAnalysis,
        config: PipelineConfig,
        owners: Map<String, Owner>,
        rasterAlignment: HeadCoordinateSpace,
        head: MouthFittedInputs.Head? = null,
        cache: PreviewMeshCache? = null,
        checkpoint: () -> Unit = {},
    ): MouthOutlineInputs {
        val original = analysis.layers.filter { it.source !is MouthLipLayer && it.source.id.raw !in config.deletedLayerIds }
        val byLayer = original.associateBy { it.source.id.raw }
        val layered = original.any { it.semantic.tag in tags && it.semantic.role in io.github.psd2live.core.fit.FittedMouth.LIPS }
        val excluded = config.rigPendingLayerIds + DepthSplit.frontLayerIds(config)
        val split = PrimitiveResolution.of(config.rigEdits)
        val unitScale = MeshResolution.unitScale(config, analysis.source)
        val outlineConfig = config.copy(mouthOutlineEnabled = true)
        // The outline mesh footprint also determines the native ribbon's frame and aperture.
        val geometry by lazy { RigBuilder.rigContext(analysis, outlineConfig, cache) }
        val framedLayers by lazy { geometry.analysis.layers.associateBy { it.source.id.raw } }
        val targets = linkedMapOf<String, MouthOutlineInputs.Target>()
        val bindings = linkedMapOf<String, String>()
        val layers = linkedMapOf<String, ModifierSourceLayer>()
        val resources = linkedMapOf<String, ModifierAlgorithmResource>()
        val traces = hashMapOf<String, List<MouthColumn>>()
        if (!layered && !config.meshOnly && !config.deferRig) for ((id, owner) in owners) {
            checkpoint()
            val layer = byLayer[owner.layer] ?: continue
            if (owner.layer in excluded || split.isPartLayer(owner.layer) || layer.semantic.tag !in tags ||
                layer.semantic.role != null || layer.opaquePixels == 0) continue
            val source = layer.source
            val sides = (0..1).filterTo(linkedSetOf()) { MouthLipLayer.idFor(owner.layer, it) !in config.deletedLayerIds }
            if (sides.isEmpty()) continue
            val columns = traces.getOrPut(owner.layer) { MouthLipLayers.trace(layer, config, unitScale, rasterAlignment) }
            if (columns.size < 2) continue
            val framed = framedLayers.getValue(owner.layer)
            val frame = geometry.parentAndFrame(framed, outlineConfig).second
            val aperture = geometry.rigLayer(framed).bounds
            val alignment = geometry.headSpaceFor(framed, outlineConfig)
            // The native opening uses the outline-specific edge margin and the selected mouth recipe's density.
            val neutral = RigBuilder.mouthOutlineSourceMesh(framed, alignment, outlineConfig, cache, unitScale)
            layers.getOrPut(owner.layer) {
                val pixels = ModifierAlgorithmResource(source.raster.rgba)
                resources.putIfAbsent(pixels.digest, pixels)
                ModifierSourceLayer(owner.layer, layer.semantic.tag.name, layer.semantic.side.name,
                    layer.semantic.role?.name, source.bounds.left.toFloat(), source.bounds.top.toFloat(),
                    source.raster.width, source.raster.height, pixels.digest, layer.bounds.observation(),
                    layer.centroidX, layer.centroidY, layer.opaquePixels)
            }
            bindings[id] = owner.layer
            targets[id] = MouthOutlineInputs.Target(owner.canonical, frame.observation(), aperture.observation(),
                alignment?.observation(), neutral.rigPositions.toList(), neutral.mesh.indices.toList(),
                columns, source.name + " / " + tr("mouth.upperLip"), source.name + " / " + tr("mouth.lowerLip"),
                source.groupPath, source.visible, sides, owner.fittedProvider, owner.headOnMesh, owner.contourOwner,
                rasterAlignment.observation())
        }
        val tuning = config.rigTuning
        val fitted = if (targets.values.any { it.fittedProvider != null }) geometry.fittedMouth(outlineConfig)?.source else null
        return MouthOutlineInputs(ModifierSourceInputs(layers.values.toList(), resources, bindings), targets,
            config.alphaThreshold, if (config.mouthShape == "custom") config.mouthCurve else MouthCurve.preset(config.mouthShape),
            MouthOutlineMotion.Tuning(tuning.mouthClosedNarrow, tuning.mouthFormWiden, tuning.mouthSeam,
                tuning.mouthSmileBase, tuning.mouthSmile), fitted, head)
    }

    private fun Bounds.observation() = ModifierSourceBounds(left, top, right, bottom)
    private fun HeadCoordinateSpace.observation() = MouthOutlineInputs.Alignment(angleDegrees, pivotX, pivotY)
    private val tags = setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN)
}
