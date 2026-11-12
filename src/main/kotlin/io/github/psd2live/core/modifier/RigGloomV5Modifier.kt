package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.GloomV5Algorithm
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

/** Raw source fits and topology only. In particular, this adapter never evaluates a skin binding. */
internal object RigGloomV5Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by GloomV5Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val canonicalOptInType = GloomV5Algorithm.TYPE
        override val optionalUpstream = GloomV5Algorithm.observed - GloomV5Algorithm.requires
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition =
        RigGloomModifier.definition(source).copy(
            version = GloomV5Algorithm.VERSION, plugin = algorithm, requires = GloomV5Algorithm.requires,
            providers = { instance -> source?.providers(instance) ?: RigLegacyBindings.providers(instance) },
            parameters = { emptyList() }, resolveParameter = { _, _, _ -> null },
            changeWrites = GloomV5Algorithm.writes, upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it, sourceMeasurements = true) },
        )

    /** Explicit source-measurement entry point, identical to the registered capture. */
    internal fun sourceMeasurementDefinition(source: RigModifierSource): RigModifierDefinition =
        definition(source)

    /** Native reference for exact capture comparisons, never a runtime fallback. */
    internal fun nativeCaptureDefinition(source: RigModifierSource): RigModifierDefinition =
        definition(source).copy(authorContext = { source.capture(it, sourceMeasurements = false) })

    /** Missing, disabled, ambiguous and incompatible required providers suspend before capture. */
    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        requireIdentifiableAppliedProviders(instance)
        val prefix = activeBefore(instance)
        val selected = RigLegacyBindings.providers(instance)
        val scaffold = instance.config.getValue("scaffold")
        val resolved = GloomV5Algorithm.requiredVersions.mapValues { (type, version) ->
            prefix.filter { it.type == type && it.targets.any(instance.targets::contains) &&
                (type == "rig.scaffold" || it.config["scaffold"] == scaffold) }.singleOrNull()?.takeIf {
                it.version == version && it.targets.containsAll(instance.targets) &&
                    (type != "rig.scaffold" || it.targets.toSet() == instance.targets.toSet()) &&
                    it.plugin == BuiltinModifierPlugins.reference(type, version) &&
                    (selected[type] == null || it.id == selected[type])
            }
        }.toMutableMap()
        val mouth = resolved["mouth/fitted"]?.id?.let(::JsonPrimitive)
        for (type in GloomV5Algorithm.mouthModules) {
            if (resolved[type]?.config?.get("base") != mouth) resolved[type] = null
        }
        if (resolved["mouth.spring"]?.config?.get("dependencies")?.jsonObject?.get("mouth.jaw") !=
            resolved["mouth.jaw"]?.id?.let(::JsonPrimitive)) resolved["mouth.spring"] = null
        return resolved.mapValues { (type, provider) -> provider?.id ?: generateSequence("${instance.id}/missing:$type") { "$it?" }
            .first { unavailable -> prefix.none { it.id == unavailable } } }
    }

    /** activeBefore omits anonymous legacy records, so inspect raw intersecting metadata first. */
    private fun RigModifierSource.requireIdentifiableAppliedProviders(instance: RigModifierInstance) {
        require(appliedSourceCapabilities.none { owner ->
            owner.id == null && owner.targets.any(instance.targets::contains)
        }) { "Gloom v5 requires identifiable applied source providers" }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, sourceMeasurements: Boolean): ModifierAlgorithmContext {
        requireIdentifiableAppliedProviders(instance)
        val scaffold = provider(instance)
        require(scaffold.version == 2 && scaffold.targets.toSet() == instance.targets.toSet()) {
            "Gloom v5 requires the complete shared v2 scaffold"
        }
        val preceding = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        require(preceding.map { it.id }.distinct().size == preceding.size &&
            preceding.singleOrNull { it.id == scaffold.id } == scaffold) {
            "Gloom v5 requires unique source instances and the exact preceding scaffold"
        }
        require(preceding.all { owner -> owner.id == scaffold.id ||
            owner.config["scaffold"] == JsonPrimitive(scaffold.id) && owner.type in GloomV5Algorithm.supported
        }) { "Gloom v5 rejects unknown or foreign source providers" }
        val prefix = preceding.filterNot { it.id == scaffold.id }
        val owners = listOf(scaffold) + prefix
        require(owners.all { it.plugin == BuiltinModifierPlugins.reference(it.type, it.version) }) {
            "Gloom v5 requires the exact supported source packages"
        }
        val capabilities = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        GloomV5Algorithm.validateProviders(capabilities, instance)
        val sourceAnalysis = measurementAnalysis(prefix)
        fun measurements(framing: List<RigModifierInstance>) =
            RigFittedMouthModuleSource.nativeConfig(this, scaffold, framing, null).let { native ->
                native to if (sourceMeasurements) RigBuilder.sourceSkinMeasurementContext(sourceAnalysis, native, meshCache)
                    else RigBuilder.rigContext(sourceAnalysis, native, meshCache)
            }
        val (native, measured) = measurements(prefix)
        require(measured.analysis.layers.none { it.semantic.tag == SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 }) {
            "Gloom v5 does not support a separate closed-mouth drawing"
        }
        val mouth = requireNotNull(measured.fittedMouth(native))
        require(mouth.source != null && mouth.rig != null && !mouth.layered && !mouth.lipped && !mouth.closedLine &&
            measured.analysis.layers.any { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
                it.semantic.role == null && it.opaquePixels > 0 }) { "Gloom v5 requires whole open-mouth artwork" }
        val eyes = measured.fittedEyes(native)?.rigs.orEmpty()
        require(eyes.size == 1 && measured.fittedBrowMeasurements(native).first.keys == eyes.keys &&
            measured.analysis.layers.any { it.semantic.tag == SemanticTag.EYELASH && it.semantic.side == eyes.keys.single() && it.opaquePixels > 0 }) {
            "Gloom v5 requires exactly one fitted eye with a same-side brow and eyelash"
        }
        val sourceLayers = measured.analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.associate { it.id to sourceLayers.getValue(layersByMesh.getValue(it.id)) }
        val affected = targets.filterValues { layer -> layer.semantic.tag in setOf(SemanticTag.FACE, SemanticTag.NOSE,
            SemanticTag.EARS, SemanticTag.FACE_DETAIL) }
        require(affected.isNotEmpty() && affected.values.all { layer ->
            layer.semantic.type == LayerType.PRESET && layer.semantic.parameter.isBlank()
        }) { "Gloom v5 requires preset skin without authored expression channels" }
        require(affected.all { (id, layer) -> id == primaryByLayer.getValue(layer.source.id.raw) }) {
            "Gloom v5 requires unsplit source skin topology"
        }
        // Fitted face details retain their original face frame: outline7 modifies only face/nose skin.
        // Capture each framing independently, never its generated geometry, shapes or deformer outputs.
        val resources = linkedMapOf<String, ModifierAlgorithmResource>()
        val groups = affected.entries.groupBy { it.value.semantic.tag == SemanticTag.FACE_DETAIL }.map { (detail, entries) ->
            val framing = if (detail) prefix.filterNot { it.type == "mouth.outline" } else prefix
            val (config, context) = if (detail) measurements(framing) else native to measured
            val layers = entries.associate { it.key to it.value }
            val source = RigFittedMouthModuleSource.measure(this, config, context, framing, layers)
            resources.putAll(source.resources)
            source.values
        }
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", "expression.gloom/5")
            put("providers", JsonArray(capabilities.map { Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(), it) }))
            put("groups", JsonArray(groups))
            // MouthFittedInputs.closedMouth is the historical MOUTH-presence flag used by
            // channel generation, not the presence of a separate MOUTH_CLOSE drawing.
            // Preserve it and give this candidate the actual opaque mouth-art classification.
            put("mouth_artwork", JsonArray(measured.analysis.layers.filter {
                it.opaquePixels > 0 && it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE)
            }.map { layer -> buildJsonObject {
                put("id", layer.source.id.raw)
                put("tag", layer.semantic.tag.name)
                put("role", layer.semantic.role?.name?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("parameter_name", tr("model.parameter.gloom"))
            put("group_name", tr("model.group.face"))
            putJsonObject("materials") {
                affected.forEach { (id, layer) -> putJsonObject(id) {
                    put("name", layer.source.name)
                    put("opacity", layer.source.opacity.coerceIn(0f, 1f))
                } }
            }
            put("eye_highlights", JsonArray(targets.filterValues { it.semantic.tag == SemanticTag.IRIDES &&
                it.semantic.role == PartRole.HIGHLIGHT }.keys.map(::JsonPrimitive)))
        }, resources)
    }

    /** Applied outline rasters remain render assets, not new source landmarks. */
    private fun RigModifierSource.measurementAnalysis(prefix: List<RigModifierInstance>): PipelineAnalysis {
        val originalIds = analysis.source.layers.mapTo(hashSetOf()) { it.id.raw }
        val originalLayers = analysis.layers.filter { it.source.id.raw in originalIds }.associateBy { it.source.id.raw }
        val outline = prefix.single { it.type == "mouth.outline" }
        // Restored assets lose the MouthLipLayer runtime type, so identify them through
        // the real outline owner's output contract, never a name or ID-pattern heuristic.
        val generated = outline.targets.filter { target ->
            originalLayers[layersByMesh[target.id]]?.let { layer ->
                layer.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && layer.semantic.role == null
            } == true
        }.flatMapTo(hashSetOf()) { target -> (0..1).map { side -> outline.outputId("mouth-art:${target.id}:$side") } }
        fun sourceOnly(input: PipelineAnalysis): PipelineAnalysis {
            val authored = input.source.layers.mapTo(hashSetOf()) { it.id.raw }
            val extra = input.layers.filter { it.source.id.raw !in authored }
            require(extra.all { it.source.id.raw in generated }) {
                "Gloom v5 cannot measure unrecognized generated artwork"
            }
            val calibration = input.calibration?.let(::sourceOnly)
            if (extra.isEmpty() && calibration === input.calibration) return input
            // Never exclude an authored layer merely because its ID resembles generated artwork.
            return input.copy(layers = input.layers.filter { it.source.id.raw in authored }, calibration = calibration)
        }
        return sourceOnly(analysis)
    }
}
