package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthSpringAlgorithm
import io.github.psd2live.algorithms.binding.MouthSpringV3Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV4Algorithm
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

internal object RigMouthSpringModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringAlgorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringAlgorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    private val fullAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV3Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV3Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    private val fittedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV4Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV4Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun fullDefinition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, MouthSpringV3Algorithm.VERSION) }
        return definition(source).copy(version = MouthSpringV3Algorithm.VERSION,
            parameters = { MouthSpringV3Algorithm.parameters(capture(it)) }, plugin = fullAlgorithm, authorContext = ::capture)
    }

    fun fittedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, MouthSpringV4Algorithm.VERSION) }
        return definition(source).copy(version = MouthSpringV4Algorithm.VERSION,
            parameters = { MouthSpringV4Algorithm.parameters(capture(it)) }, plugin = fittedAlgorithm, authorContext = ::capture)
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

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int = MouthSpringAlgorithm.VERSION): ModifierAlgorithmContext {
        val full = version >= MouthSpringV3Algorithm.VERSION
        val sourceVersions = 1..if (version == MouthSpringV4Algorithm.VERSION) 3 else 2
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth spring v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val jawId = instance.config.getValue("dependencies").jsonObject.getValue("mouth.jaw").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull { it.id == baseId && it.type == "mouth/fitted" && it.version in sourceVersions })
        val jaw = requireNotNull(preceding.singleOrNull { it.id == jawId && it.type == "mouth.jaw" && it.version in sourceVersions &&
            it.config["base"] == JsonPrimitive(baseId) })
        val optional = when (version) {
            MouthSpringV4Algorithm.VERSION -> MouthSpringV4Algorithm.optionalUpstream
            MouthSpringV3Algorithm.VERSION -> MouthSpringV3Algorithm.optionalUpstream
            else -> MouthSpringAlgorithm.optionalUpstream
        }
        require(full || preceding.all { it == base || it == jaw || it.type in optional }) {
            "Mouth spring v$version requires compatible source bindings. Use v1 after other mouth or face modules."
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_SPRING)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null && !it.closedLine }) {
            "Mouth spring v$version requires readable fitted artwork without a separate closed drawing. Use v1 for closed-drawing swaps."
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
            require((if (full) preceding.filter { it == base || it == jaw || it.type in optional } else preceding)
                .filterNot { full && it.type in setOf("eye/fitted", "mouth.cheek-lid", "mouth.interior-depth", "mouth.tongue") }
                .all { target in it.targets }) { "Mouth spring v$version requires a shared source frame for the jaw targets" }
            if (tag == SemanticTag.FACE && role == null && fitted.lipped && !mouth.fit.hasLines) lippedSkin += target.id
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Mouth spring v$version requires a fitted lower face" }
        val measurements = RigFittedMouthModuleSource.measure(this, native, context, preceding, layers)
        val name = tr(RigModules.MOUTH_SPRING.defines.single().nameKey)
        val folder = tr("model.group.physics")
        val physics = tr("model.physics.mouthSpring")
        if (!full) return MouthSpringAlgorithm.context(measurements, lippedSkin, name, folder, physics)
        val skin = layers.map { (id, layer) ->
            val channels = Drawable(DrawableId(id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
            PuppetIr.meshToIr(RigBuilder.skinLoweringSource(layer, context, native, atlas, meshCache, channels))
        }
        val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = skin)))
        val providers = preceding.filter { it.type in optional }
        return if (version == MouthSpringV4Algorithm.VERSION) {
            MouthSpringV4Algorithm.context(measurements, lippedSkin, name, folder, physics, providers, resource)
        } else {
            MouthSpringV3Algorithm.context(measurements, lippedSkin, name, folder, physics, providers, resource)
        }
    }
}
