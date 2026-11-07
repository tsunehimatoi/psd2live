package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.CreatureAlgorithm
import io.github.psd2live.algorithms.binding.CreatureInputs
import io.github.psd2live.algorithms.binding.CreatureResidualAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldPairs
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.HairKind
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.ParameterId

internal object RigCreatureModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by CreatureAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = CreatureAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigLegacyBindings.definition(RigLegacyBindings.spec(CreatureAlgorithm.TYPE), source).copy(
            version = CreatureAlgorithm.VERSION,
            parameters = { emptyList() },
            upstreamInputs = true,
            authorContext = { capture(requireNotNull(source), it) },
            changeWrites = CreatureAlgorithm.WRITES,
            plugin = algorithm,
        )

    fun residualDefinition(source: RigModifierSource?): RigModifierDefinition {
        val worker = object : ReadingModifierAlgorithmPlugin by CreatureResidualAlgorithm(),
            ModifierVersionSelection, ModifierOptionalUpstream {
            override val canonicalOptInType = CreatureAlgorithm.TYPE
            override val optionalUpstream = CreatureResidualAlgorithm.PROFILE.keys
        }
        return definition(source).copy(version = CreatureResidualAlgorithm.VERSION, plugin = worker,
            authorContext = { instance ->
                val author = requireNotNull(source)
                val base = capture(author, instance)
                val affected = CreatureInputs.decode(base).layers.flatMap { it.meshes }.toSet()
                author.captureAttachmentResidual(instance, affected, "creature", base)
            })
    }

    fun capture(source: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val previous = source.activeBefore(instance)
        val onScaffold = previous.filter { it.config["scaffold"] == instance.config["scaffold"] }
        val targetsByLayer = instance.targets.groupBy({ source.layersByMesh[it.id] }, { it.id })
        val creatureLayers = source.analysis.layers.filter { kind(it, source.skeletonEnabled) != null }.mapTo(hashSetOf()) { it.source.id.raw }
        val targets = instance.targets.filter { source.layersByMesh[it.id] in creatureLayers }
        val config = RigClothingSourceFrame.config(source, instance, targets, onScaffold)
        val context = RigBuilder.rigContext(source.analysis, config, source.meshCache)
        val subject = RecipeSubject.of(context.analysis)
        val paired = ScaffoldPairs.build(context.analysis.layers.filter {
            it.opaquePixels > 0 && it.semantic.side != Side.NONE && !RigBuilder.isHandledByFaceRegion(it, context.faceRig)
        }.map { layer ->
            val (parent, frame) = context.parentAndFrame(layer, config)
            ScaffoldPairs.Layer(layer.source.id.raw, layer.source.name, layer.semantic.tag.name, layer.semantic.side,
                context.rigLayer(layer).bounds, requireNotNull(parent), frame,
                RigBuilder.scaffoldSlot(layer, context.analysis.anchors).id)
        }, emptySet(), { it }).flatMapTo(hashSetOf()) { it.layerIds }

        fun bounds(bounds: Bounds) = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val measurements = if (!RigModules.OUTFIT_CREATURE.applies(subject)) emptyList() else
            context.analysis.layers.mapNotNull { layer ->
                val kind = kind(layer, source.skeletonEnabled) ?: return@mapNotNull null
                if (layer.opaquePixels <= 0) return@mapNotNull null
                val id = layer.source.id.raw
                // A matched pair consumes its place in the original chain sequence while keeping the pair warp.
                val meshes = if (id in paired) emptyList() else targetsByLayer[id].orEmpty()
                val attachment = if (meshes.isEmpty()) null else {
                    val (anchor, frame) = context.parentAndFrame(layer, config)
                    val slot = RigBuilder.scaffoldSlot(layer, context.analysis.anchors)
                    val accessory = ScaffoldParts.Slot.ACCESSORIES
                    val carrierSlot = when (slot) {
                        ScaffoldParts.Slot.FACE -> accessory
                        ScaffoldParts.Slot.EXTRA -> ScaffoldParts.Slot.BODY
                        else -> slot
                    }
                    val anchors = listOfNotNull(requireNotNull(anchor).raw,
                        anchor.raw.replace("Physics", "Follow").takeIf { anchor.raw in setOf("DeformHairFrontPhysics", "DeformHairBackPhysics") })
                    CreatureInputs.Attachment(anchors, carrierSlot.id.raw, bounds(frame),
                        if (slot == ScaffoldParts.Slot.FACE) tr(accessory.label) else null)
                }
                val name = layer.source.name
                CreatureInputs.Layer(id, layer.semantic.tag.name, bounds(layer.bounds), layer.opaquePixels, kind.motion,
                    meshes, attachment, tr("model.deformer.partChain", name), tr("model.parameter.hairChain", name, CreatureAlgorithm.TOKEN))
            }
        val selected = measurements.filter { it.meshes.isNotEmpty() }.associate { it.id to it.id }
        val topology = RigBuilder.scaffoldMeshInputs(source.analysis, source.atlas, config.copy(mouthOutlineEnabled = false),
            source.meshCache, selected)
        val impulses = previous.filter { RigLegacyBindings.find(it.type)?.expressionToggles == true }
            .flatMap { source.expressionIds(it).map(ParameterId::raw) }
        return CreatureInputs(CreatureInputs.Measurements(measurements, impulses, tr("model.group.physics"),
            tr("model.physics.partChain", CreatureAlgorithm.TOKEN), tr("model.physics.partBounce", CreatureAlgorithm.TOKEN)), topology).context()
    }

    private fun kind(layer: ClassifiedLayer, skeleton: Boolean) =
        HairKind.partOf(layer)?.takeUnless { skeleton && layer.semantic.tag in SkeletonAutoBuilder.limbTags }

}
