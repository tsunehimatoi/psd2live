package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.SkirtAlgorithm
import io.github.psd2live.algorithms.binding.SkirtInputs
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.HairKind
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.ParameterId

internal object RigSkirtModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by SkirtAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = SkirtAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(SkirtAlgorithm.TYPE), source)
        return legacy.copy(
            version = SkirtAlgorithm.VERSION,
            parameters = { emptyList() },
            upstreamInputs = true,
            authorContext = { capture(requireNotNull(source), it) },
            changeWrites = SkirtAlgorithm.WRITES,
            evaluate = { _, _, _ -> error("Skirt binding requires resolved modifier inputs") },
            plugin = algorithm,
        )
    }

    fun capture(source: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val meshesByLayer = source.layersByMesh.entries.filter { it.key in targets }.groupBy({ it.value }, { it.key })
        val previous = source.activeBefore(instance).filter { it.config["scaffold"] == instance.config["scaffold"] }
        val skirtLayers = source.analysis.layers.filter { HairKind.clothOf(it) == HairKind.SKIRT }.mapTo(hashSetOf()) { it.source.id.raw }
        val skirtTargets = instance.targets.filter { source.layersByMesh[it.id] in skirtLayers }
        val config = RigClothingSourceFrame.config(source, instance, skirtTargets, previous)
        val context = RigBuilder.rigContext(source.analysis, config, source.meshCache)
        val body = context.bodyFrame
        val layers = context.analysis.layers
        val ribbons = previous.filter { it.type == "outfit.ribbons" }.flatMap { it.targets }.toSet()
        val bodyTags = setOf(SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR)
        val pairs = ScaffoldPairs.build(layers.filter { it.semantic.tag in bodyTags && it.semantic.side != Side.NONE &&
            it.opaquePixels > 0 }.map {
            ScaffoldPairs.Layer(it.source.id.raw, it.source.name, it.semantic.tag.name, it.semantic.side, it.bounds,
                ScaffoldHead.breathId, body, ScaffoldParts.Slot.BODY.id)
        }, emptySet(), { it }).flatMapTo(hashSetOf()) { it.layerIds }
        fun bounds(bounds: Bounds) = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val measurements = if (!RigModules.OUTFIT_SKIRT.applies(RecipeSubject.of(context.analysis))) emptyList() else
            layers.mapNotNull { layer ->
                val kind = HairKind.clothOf(layer) ?: return@mapNotNull null
                if (layer.opaquePixels <= 0 || kind != HairKind.SKIRT && ribbons.isEmpty()) return@mapNotNull null
                val id = layer.source.id.raw
                val meshes = meshesByLayer[id].orEmpty()
                require(kind != HairKind.SKIRT || meshes.isEmpty() || layer.semantic.tag in bodyTags) {
                    "A skirt modifier target requires topwear or bottomwear source artwork"
                }
                val name = layer.source.name
                SkirtInputs.Layer(id, layer.semantic.tag.name, bounds(layer.bounds), layer.opaquePixels, kind.name,
                    meshes, id in pairs, tr("model.deformer.clothChain", name),
                    tr("model.parameter.hairChain", name, SkirtAlgorithm.TOKEN))
            }
        val selected = measurements.filter { it.kind == "SKIRT" && !it.paired && it.meshes.isNotEmpty() }.associate { it.id to it.id }
        val topology = RigBuilder.scaffoldMeshInputs(source.analysis, source.atlas, config.copy(mouthOutlineEnabled = false),
            source.meshCache, selected)
        val impulses = source.activeBefore(instance).filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
            .flatMap { source.expressionIds(it).map(ParameterId::raw) }
        return SkirtInputs(SkirtInputs.Measurements(bounds(body), measurements,
            instance.targets.filter { it in ribbons }.mapTo(hashSetOf()) { it.id }, impulses, tr("model.group.physics"),
            tr("model.physics.clothChain", SkirtAlgorithm.TOKEN), tr("model.physics.clothBounce", SkirtAlgorithm.TOKEN)), topology).context()
    }
}
