package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthSpringAlgorithm
import io.github.psd2live.algorithms.binding.MouthSpringV3Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV4Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV5Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV6Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV7Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV8Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV9Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV10Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV11Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV12Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV13Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV14Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV15Algorithm
import io.github.psd2live.core.CharacterAnalyzer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.PhysicsGenerator
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

    private val tongueAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV5Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun tongueDefinition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, MouthSpringV5Algorithm.VERSION) }
        return definition(source).copy(version = MouthSpringV5Algorithm.VERSION,
            parameters = { MouthSpringV5Algorithm.parameters(capture(it)) }, plugin = tongueAlgorithm, authorContext = ::capture)
    }

    private val closedAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV6Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV6Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, MouthSpringV6Algorithm.VERSION) }
        return definition(source).copy(version = MouthSpringV6Algorithm.VERSION,
            parameters = { MouthSpringV6Algorithm.parameters(capture(it)) }, plugin = closedAlgorithm, authorContext = ::capture)
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV8Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV8Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, MouthSpringV8Algorithm.VERSION) }
        return definition(source).copy(version = MouthSpringV8Algorithm.VERSION,
            parameters = { MouthSpringV8Algorithm.parameters(capture(it)) }, plugin = modernAlgorithm, authorContext = ::capture)
    }

    private val depthAlgorithm = object : ReadingModifierAlgorithmPlugin by MouthSpringV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = MouthSpringV6Algorithm.optionalUpstream
        override val canonicalOptInType = MouthSpringAlgorithm.TYPE
    }

    fun closedDepthDefinition(source: RigModifierSource?): RigModifierDefinition =
        closedDefinition(source).copy(version = MouthSpringV7Algorithm.VERSION, plugin = depthAlgorithm)

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
            authorContext = ::capture)
    }

    fun sharedFrameDefinition(source: RigModifierSource?, withSharedTongue: Boolean = false,
                              layered: Boolean = false, withTeethOnly: Boolean = false): RigModifierDefinition = when {
        layered && withTeethOnly -> sourceFrameDefinition(source, MouthSpringV14Algorithm.VERSION, MouthSpringV14Algorithm())
        layered && withSharedTongue -> sourceFrameDefinition(source, MouthSpringV13Algorithm.VERSION, MouthSpringV13Algorithm())
        layered -> sourceFrameDefinition(source, MouthSpringV11Algorithm.VERSION, MouthSpringV11Algorithm())
        withSharedTongue -> sourceFrameDefinition(source, MouthSpringV10Algorithm.VERSION, MouthSpringV10Algorithm())
        else -> sourceFrameDefinition(source, MouthSpringV9Algorithm.VERSION, MouthSpringV9Algorithm())
    }

    fun fallbackFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthSpringV12Algorithm.VERSION, MouthSpringV12Algorithm())

    fun fallbackTongueFrameDefinition(source: RigModifierSource?): RigModifierDefinition =
        sourceFrameDefinition(source, MouthSpringV15Algorithm.VERSION, MouthSpringV15Algorithm())

    private fun sourceFrameDefinition(source: RigModifierSource?, version: Int,
                                      algorithm: ChangingModifierAlgorithmPlugin): RigModifierDefinition {
        val projected = object : ChangingModifierAlgorithmPlugin by algorithm,
            ModifierOptionalUpstream, ModifierVersionSelection {
            override val canonicalOptInType = MouthSpringAlgorithm.TYPE
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(MouthSpringAlgorithm.TYPE, "mouth/fitted", "mouth.jaw")
        }
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).captureSharedFrames(instance) }
        return definition(source).copy(version = version, plugin = projected,
            parameters = { instance -> RigIrBinary.decode(capture(instance).resources.getValue("after").read())
                .parameters.filter { it.id == "ParamMouthSpring" } },
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy", "physics"),
            authorContext = ::capture)
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prefix = bindingPredecessors(instance, scaffold)
        val layeredTongue = instance.version == MouthSpringV13Algorithm.VERSION
        val layeredTeeth = instance.version == MouthSpringV14Algorithm.VERSION
        val fittedVersion = when (instance.version) {
            MouthSpringV11Algorithm.VERSION, MouthSpringV13Algorithm.VERSION, MouthSpringV14Algorithm.VERSION -> 8
            MouthSpringV12Algorithm.VERSION, MouthSpringV15Algorithm.VERSION -> 7
            else -> 6
        }
        require(prefix.any { it.id == instance.config.getValue("base").jsonPrimitive.content &&
            it.type == "mouth/fitted" && it.version == fittedVersion }) {
            "Mouth spring v${instance.version} requires a shared fitted mouth v$fittedVersion"
        }
        val baseProviders = setOf("mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid")
        val providers = when {
            layeredTongue || instance.version in setOf(MouthSpringV10Algorithm.VERSION, MouthSpringV15Algorithm.VERSION) ->
                baseProviders + setOf("mouth.tongue", "mouth.interior-depth")
            layeredTeeth -> baseProviders + "mouth.interior-depth"
            else -> baseProviders
        }
        val extraMouth = prefix.filter { it.type.startsWith("mouth.") && it.type !in providers }
        require(extraMouth.isEmpty() || (instance.version == MouthSpringV9Algorithm.VERSION &&
            extraMouth.singleOrNull()?.let { depth ->
                depth.type == "mouth.interior-depth" && depth.version == 6 &&
                    depth.config["base"] == instance.config["base"] && depth.targets.containsAll(instance.targets)
            } == true)) {
            "Mouth spring v${instance.version} requires the shared open-mouth source chain"
        }
        val face = setOf(SemanticTag.FACE, SemanticTag.NOSE, SemanticTag.FACE_DETAIL)
        return captureBindingTransition(instance, scaffold, prefix, face + CharacterAnalyzer.MOUTH_TAGS,
            projectedTags = face, includeLayeredTongueKeys = layeredTongue, values = buildJsonObject {
                put("group", tr("model.group.physics"))
                put("physics", PhysicsGenerator.moduleRules(setOf("ParamMouthOpenY", "ParamMouthForm", "ParamMouthSpring"))
                    .single { it.id == PhysicsGenerator.MOUTH_SPRING_ID }.toJson())
                putJsonObject("providers") {
                    providers.forEach { type -> put(type, prefix.single { it.type == type }.id) }
                }
            })
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, version: Int = MouthSpringAlgorithm.VERSION): ModifierAlgorithmContext {
        val full = version >= MouthSpringV3Algorithm.VERSION
        val modern = version == MouthSpringV8Algorithm.VERSION
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Mouth spring v$version requires a shared v2 scaffold" }
        val preceding = activeBefore(instance).filter { item ->
            item.config["scaffold"] == JsonPrimitive(scaffold.id) && item.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(item.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val baseId = instance.config.getValue("base").jsonPrimitive.content
        val jawId = instance.config.getValue("dependencies").jsonObject.getValue("mouth.jaw").jsonPrimitive.content
        val base = requireNotNull(preceding.singleOrNull { it.id == baseId && it.type == "mouth/fitted" })
        val closed = version == MouthSpringV6Algorithm.VERSION || modern && base.version == 5
        val sourceVersions = if (closed) 4..4 else if (version >= MouthSpringV4Algorithm.VERSION) 1..3 else 1..2
        require(if (modern) base.version in setOf(3, 5) else base.version in sourceVersions)
        val jawVersions = when {
            modern -> 7..7
            closed -> 5..6
            version == MouthSpringV5Algorithm.VERSION -> 1..4
            else -> sourceVersions
        }
        val jaw = requireNotNull(preceding.singleOrNull { it.id == jawId && it.type == "mouth.jaw" && it.version in jawVersions &&
            it.config["base"] == JsonPrimitive(baseId) }) { "Mouth spring v$version requires a compatible jaw binding" }
        val optional = when (version) {
            MouthSpringV8Algorithm.VERSION -> MouthSpringV8Algorithm.optionalUpstream
            MouthSpringV6Algorithm.VERSION -> MouthSpringV6Algorithm.optionalUpstream
            MouthSpringV5Algorithm.VERSION -> MouthSpringV5Algorithm.optionalUpstream
            MouthSpringV4Algorithm.VERSION -> MouthSpringV4Algorithm.optionalUpstream
            MouthSpringV3Algorithm.VERSION -> MouthSpringV3Algorithm.optionalUpstream
            else -> MouthSpringAlgorithm.optionalUpstream
        }
        require(full || preceding.all { it == base || it == jaw || it.type in optional }) {
            "Mouth spring v$version requires compatible source bindings. Use v1 after other mouth or face modules."
        }
        val (native, context) = RigFittedMouthModuleSource.context(this, scaffold, preceding, RigModules.MOUTH_SPRING)
        val fitted = requireNotNull(context.fittedMouth(native)?.takeIf { it.source != null && it.closedLine == closed }) {
            "Mouth spring v$version requires readable fitted artwork with a matching closed-drawing mode."
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
        return if (version >= MouthSpringV4Algorithm.VERSION) {
            MouthSpringV4Algorithm.context(measurements, lippedSkin, name, folder, physics, providers, resource)
        } else {
            MouthSpringV3Algorithm.context(measurements, lippedSkin, name, folder, physics, providers, resource)
        }
    }
}
