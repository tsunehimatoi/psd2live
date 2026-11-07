package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ChainKind
import io.github.psd2live.algorithms.binding.RibbonsAlgorithm
import io.github.psd2live.algorithms.binding.RibbonsInputs
import io.github.psd2live.algorithms.binding.RibbonsResidualAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.HairKind
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.ParameterId
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.targets.cubism.PuppetIr

internal object RigRibbonsModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by RibbonsAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = RibbonsAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(RibbonsAlgorithm.TYPE), source).copy(
            version = RibbonsAlgorithm.VERSION,
            parameters = { emptyList() },
            upstreamInputs = true,
            authorContext = { capture(requireNotNull(source), it) },
            changeWrites = RibbonsAlgorithm.WRITES,
            plugin = algorithm,
        )

    fun residualDefinition(source: RigModifierSource?): RigModifierDefinition {
        val worker = object : ReadingModifierAlgorithmPlugin by RibbonsResidualAlgorithm(),
            ModifierVersionSelection, ModifierOptionalUpstream {
            override val optionalUpstream = RibbonsResidualAlgorithm.PROFILE.keys
            override val canonicalOptInType = RibbonsAlgorithm.TYPE
        }
        return definition(source).copy(version = RibbonsResidualAlgorithm.VERSION, plugin = worker,
            authorContext = { captureResidual(requireNotNull(source), it) })
    }

    private fun captureResidual(source: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val base = capture(source, instance)
        val affected = RibbonsInputs.decode(base).layers.flatMap { it.meshes }.toSet()
        val targets = instance.targets.filter { it.id in affected }
        val resources = base.resources.toMutableMap()
        for ((suffix, active) in listOf("before" to false, "after" to true)) {
            for ((native, static) in source.ribbonProjection(instance, targets, active)) {
                val id = native.drawables.single().id.raw
                resources["ribbon/$id/$suffix"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(native)))
                resources["ribbon/$id/$suffix-static"] = ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(static)))
            }
        }
        return ModifierAlgorithmContext(base.values, resources)
    }

    fun capture(source: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val meshesByLayer = source.layersByMesh.entries.filter { it.key in targets }.groupBy({ it.value }, { it.key })
        val previous = source.activeBefore(instance)
        val onScaffold = previous.filter { it.config["scaffold"] == instance.config["scaffold"] }
        val ribbonLayers = source.analysis.layers.filter { HairKind.clothOf(it) in ribbonKinds }.mapTo(hashSetOf()) { it.source.id.raw }
        val ribbonTargets = instance.targets.filter { source.layersByMesh[it.id] in ribbonLayers }
        val config = RigClothingSourceFrame.config(source, instance, ribbonTargets, onScaffold)
        val context = RigBuilder.rigContext(source.analysis, config, source.meshCache)
        val subject = RecipeSubject.of(context.analysis)
        val skirtTargets = if (!RigModules.OUTFIT_SKIRT.applies(subject)) emptySet() else
            onScaffold.filter { it.type == "outfit.skirt" }.flatMap { it.targets }.toSet()
        val paired = ScaffoldPairs.build(context.analysis.layers.filter {
            it.opaquePixels > 0 && it.semantic.side != Side.NONE && !RigBuilder.isHandledByFaceRegion(it, context.faceRig)
        }.map { layer ->
            val (parent, frame) = context.parentAndFrame(layer, config)
            ScaffoldPairs.Layer(layer.source.id.raw, layer.source.name, layer.semantic.tag.name, layer.semantic.side,
                context.rigLayer(layer).bounds, requireNotNull(parent), frame,
                RigBuilder.scaffoldSlot(layer, context.analysis.anchors).id)
        }, emptySet(), { it }).flatMapTo(hashSetOf()) { it.layerIds }

        fun bounds(bounds: Bounds) = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val measurements = if (!RigModules.OUTFIT_RIBBONS.applies(subject)) emptyList() else
            context.analysis.layers.mapNotNull { layer ->
                val kind = HairKind.clothOf(layer) ?: return@mapNotNull null
                if (layer.opaquePixels <= 0 || kind == HairKind.SKIRT && skirtTargets.isEmpty()) return@mapNotNull null
                val id = layer.source.id.raw
                // Paired layers still take an ordinal, but their shared deformer already owns their attachment.
                val meshes = if (kind in ribbonKinds && id !in paired) meshesByLayer[id].orEmpty() else emptyList()
                val attachment = if (meshes.isEmpty()) null else {
                    val (anchor, frame) = context.parentAndFrame(layer, config)
                    val slot = RigBuilder.scaffoldSlot(layer, context.analysis.anchors)
                    val accessory = ScaffoldParts.Slot.ACCESSORIES
                    val hasSeparateAccessoryPart = slot == ScaffoldParts.Slot.FACE
                    RibbonsInputs.Attachment(requireNotNull(anchor).raw,
                        (if (hasSeparateAccessoryPart) accessory else slot).id.raw, bounds(frame),
                        if (hasSeparateAccessoryPart) tr(accessory.label) else null)
                }
                val name = layer.source.name
                RibbonsInputs.Layer(id, layer.semantic.tag.name, bounds(layer.bounds), layer.opaquePixels,
                    ChainKind.valueOf(kind.name), meshes, attachment, tr("model.deformer.clothChain", name),
                    tr("model.parameter.hairChain", name, RibbonsAlgorithm.TOKEN))
            }
        val selected = measurements.filter { it.meshes.isNotEmpty() }.associate { it.id to it.id }
        val topology = RigBuilder.scaffoldMeshInputs(source.analysis, source.atlas, config.copy(mouthOutlineEnabled = false),
            source.meshCache, selected)
        val impulses = previous.filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
            .flatMap { source.expressionIds(it).map(ParameterId::raw) }
        return RibbonsInputs(RibbonsInputs.Measurements(measurements,
            ribbonTargets.filter { it in skirtTargets }.mapTo(hashSetOf()) { it.id }, impulses, tr("model.group.physics"),
            tr("model.physics.clothChain", RibbonsAlgorithm.TOKEN), tr("model.physics.clothBounce", RibbonsAlgorithm.TOKEN)), topology).context()
    }

    private val ribbonKinds = setOf(HairKind.RIBBON, HairKind.PENDANT)
}
