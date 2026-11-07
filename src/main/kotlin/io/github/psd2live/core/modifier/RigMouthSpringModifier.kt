package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthSpringAlgorithm
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigMouthSpringModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(MouthSpringAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return legacy.copy(version = MouthSpringAlgorithm.VERSION,
            parameters = { MouthSpringAlgorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            plugin = algorithm, upstreamInputs = true, changeWrites = MouthSpringAlgorithm.WRITES,
            evaluate = { _, _, _ -> error("Mouth spring requires captured fitted-mouth inputs") }, authorContext = ::capture)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth spring v2 requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val jawId = instance.config.getValue("dependencies").jsonObject.getValue("mouth.jaw").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull { it.id == baseId && it.type == "mouth/fitted" && it.version in 1..2 })
        val jaw = requireNotNull(preceding.singleOrNull { it.id == jawId && it.type == "mouth.jaw" && it.version in 1..2 &&
            it.config["base"] == JsonPrimitive(baseId) })
        require(preceding.all { it == base || it == jaw || it.type in MouthSpringAlgorithm.optionalUpstream }) {
            "Mouth spring v2 requires compatible source bindings. Use v1 after other mouth or face modules."
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_SPRING)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null && !it.closedLine }) {
            "Mouth spring v2 requires readable fitted artwork without a separate closed drawing. Use v1 for closed-drawing swaps."
        }
        val mouth = requireNotNull(fitted.rig)
        val sourceLayers = context.analysis.layers.associateBy { it.source.id.raw }
        val lippedSkin = linkedSetOf<String>()
        val layers = instance.targets.mapNotNull { target ->
            val layer = sourceLayers[layersByMesh[target.id]] ?: return@mapNotNull null
            val measured = context.rigLayer(layer)
            val tag = measured.semantic.tag
            val role = measured.semantic.role
            val below = tag == SemanticTag.FACE_DETAIL && mouth.fit.toMouth(measured.centroidX, measured.centroidY).second > 0f
            if (fitted.covers(measured) || role != null && role != PartRole.SHADOW ||
                (tag != SemanticTag.FACE && tag != SemanticTag.NOSE && !below)) return@mapNotNull null
            require(preceding.all { target in it.targets }) { "Mouth spring v2 requires a shared source frame for the jaw targets" }
            if (tag == SemanticTag.FACE && role == null && fitted.lipped && !mouth.fit.hasLines) lippedSkin += target.id
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Mouth spring v2 requires a fitted lower face" }
        val measurements = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        return MouthSpringAlgorithm.context(measurements, lippedSkin, tr(RigModules.MOUTH_SPRING.defines.single().nameKey),
            tr("model.group.physics"), tr("model.physics.mouthSpring"))
    }
}
