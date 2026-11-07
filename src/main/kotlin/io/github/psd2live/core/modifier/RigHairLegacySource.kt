package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.HairLegacyAlgorithm
import io.github.psd2live.algorithms.binding.HairLegacyInputs
import io.github.psd2live.algorithms.binding.HairLegacyMotion
import io.github.psd2live.algorithms.binding.ScaffoldHead
import io.github.psd2live.core.RigTuning
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/** Captures source measurements and neutral branch membership from the named scaffold. */
internal fun RigModifierSource.hairLegacyContext(instance: RigModifierInstance): ModifierAlgorithmContext {
    val provider = provider(instance)
    val unpinned = provider.copy(plugin = null)
    val head = io.github.psd2live.algorithms.binding.ScaffoldFramesContext.decode(
        scaffoldContext(unpinned).values.getValue("frames").jsonObject).head
    val checkpoint = { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException() }
    val preceding = activeBefore(instance).filter { previous ->
        RigLegacyBindings.find(previous.type)?.let { !it.expressionToggles && !it.seeThrough } == true &&
            previous.type != instance.type && previous.config["scaffold"]?.jsonPrimitive?.content == provider.id
    }
    return RigHairLegacySource.capture(instance, provider, head,
        { target -> scaffoldFor(provider, target, checkpoint) }, { target -> preceding.filter { target in it.targets } })
}

internal object RigHairLegacySource {
    const val TYPE = "hair/legacy"

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source)
        val algorithm = HairLegacyAlgorithm()
        return legacy.copy(
            version = 2,
            parameters = { instance -> parameters(requireNotNull(source).provider(instance)) },
            plugin = algorithm,
            authorContext = { requireNotNull(source).hairLegacyContext(it) },
            changeWrites = setOf("mesh", "deformer", "parameter", "hierarchy"),
            evaluate = { rig, instance, checkpoint ->
                algorithm.evaluate(rig, instance, requireNotNull(source).hairLegacyContext(instance), checkpoint)
            },
        )
    }

    fun parameters(scaffold: RigModifierInstance): List<Parameter> = HairLegacyMotion.Plane.entries.mapNotNull { plane ->
        val key = "${plane.token.lowercase()}HairSway"
        if (scaffold.config[key]?.jsonPrimitive?.boolean == false) null
        else HairLegacyInputs.parameter(plane, tr("model.parameter.hair${plane.token}"))
    }

    fun capture(
        instance: RigModifierInstance,
        scaffold: RigModifierInstance,
        head: ScaffoldHead.Input,
        neutral: (ModifierTarget) -> PuppetModel,
        preceding: (ModifierTarget) -> List<RigModifierInstance>,
    ): ModifierAlgorithmContext {
        val tuning = RigLegacyBindings.tuning(instance, RigTuning())
        val defaults = RigTuning()
        val sources = HairLegacyMotion.Plane.entries.mapNotNull { plane ->
            val hair = when (plane) {
                HairLegacyMotion.Plane.FRONT -> head.frontHair
                HairLegacyMotion.Plane.BACK -> head.backHair
            } ?: return@mapNotNull null
            plane to HairLegacyInputs.Source(
                HairLegacyMotion.Frame(hair.bounds, head.headFrame, head.canvasPivot, head.faceFrame.bottom),
                tuning.forPlane(plane), defaults.forPlane(plane))
        }.toMap()
        val targets = instance.targets.mapNotNull { target ->
            val model = neutral(target)
            val byId = model.deformers.associateBy { it.id }
            val drawable = model.drawables.single { it.id.raw == target.id }
            var parent = drawable.parentDeformerId
            val branch = hashSetOf<DeformerId>()
            while (parent != null) {
                require(branch.add(parent)) { "Cyclic hair scaffold branch" }
                parent = byId.getValue(parent).parent
            }
            val plane = sources.keys.singleOrNull { it.follow in branch } ?: return@mapNotNull null
            val prior = preceding(target)
            val hasFace = prior.any { it.type == "face/legacy" || it.type == RigLegacyBindings.FITTED_FACE }
            val fitted = prior.any { it.type == RigLegacyBindings.FITTED_FACE || it.type == "face.depth-turn" }
            fun id(canonical: DeformerId) = RigSourceModifiers.deformerId(scaffold, target.id, canonical.raw)
            target.id to HairLegacyInputs.Target(plane, id(plane.follow), plane.sway.takeIf { it in branch }?.let(::id),
                id(ScaffoldHead.headId), prior.isNotEmpty(),
                if (hasFace) HairLegacyMotion.turnAxes else emptySet(), id(ScaffoldHead.faceId).takeIf { fitted },
                drawable.mesh?.let(HairLegacyInputs::meshIdentity))
        }.toMap()
        return HairLegacyInputs.capture(HairLegacyInputs.Input(sources, targets, parameters(scaffold), tr("model.group.physics")))
    }

    private fun RigTuning.forPlane(plane: HairLegacyMotion.Plane): HairLegacyMotion.Tuning = when (plane) {
        HairLegacyMotion.Plane.FRONT -> HairLegacyMotion.Tuning(frontHairTurn, frontHairTilt, frontHairPerspective,
            frontHairSway, frontHairCurl, hairRollHold)
        HairLegacyMotion.Plane.BACK -> HairLegacyMotion.Tuning(backHairTurn, backHairTilt, 0f, backHairSway, backHairCurl, hairRollHold)
    }
}
