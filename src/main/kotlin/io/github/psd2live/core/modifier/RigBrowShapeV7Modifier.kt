package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import kotlinx.serialization.json.*

/** Captures numerical observations, never a generated branch. Native measurement contexts remain host-local. */
internal object RigBrowShapeV7Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowShapeV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowShapeV7Algorithm.observed - BrowShapeV7Algorithm.requires
        override val canonicalOptInType = BrowShapeV7Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowShapeV7Algorithm.TYPE), source).copy(
            version = BrowShapeV7Algorithm.VERSION, plugin = algorithm, requires = BrowShapeV7Algorithm.requires,
            providers = { source?.providers(it) ?: RigLegacyBindings.providers(it) },
            parameters = { BrowShapeV7Algorithm.parameters(BrowShapeV7Source.source(capture(it))) }, resolveParameter = { _, _, _ -> null },
            changeWrites = BrowShapeV7Algorithm.writes, upstreamInputs = true, authorContext = ::capture,
        )
    }

    private fun RigModifierSource.sourcePrefix(instance: RigModifierInstance): List<RigModifierInstance> {
        require(appliedSourceCapabilities.none { it.id == null && it.targets.any(instance.targets::contains) }) {
            "Brow shape v7 requires identifiable applied providers"
        }
        val prefix = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        val scaffold = instance.config.getValue("scaffold")
        require(prefix.map { it.id }.distinct().size == prefix.size && prefix.all { owner ->
            BrowSourceProvidersV1.acceptsVersion(BrowSourceProvidersV1.Stage.SHAPE, owner.type, owner.version) && owner.targets.toSet() == instance.targets.toSet() &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                BundledAlgorithmArtifacts.find(owner.type, owner.version)?.reference?.let { it == owner.plugin } == true
        }) { "Brow shape v7 rejects unknown, foreign, partial or incompatible intersecting source providers" }
        return prefix
    }

    /** Missing required providers pause before any numerical capture. */
    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        val prefix = sourcePrefix(instance)
        val selected = RigLegacyBindings.providers(instance)
        return BrowShapeV7Algorithm.requires.associateWith { type ->
            prefix.singleOrNull { it.type == type && BrowSourceProvidersV1.acceptsVersion(BrowSourceProvidersV1.Stage.SHAPE, type, it.version) && (selected[type] == null || selected[type] == it.id) }?.id
                ?: generateSequence("${instance.id}/missing:$type") { "$it?" }.first { id -> prefix.none { it.id == id } }
        }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val owners = sourcePrefix(instance)
        val capabilities = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        BrowSourceProvidersV1.validate(capabilities, instance, BrowSourceProvidersV1.Stage.SHAPE)
        val scaffold = provider(instance)
        require(owners.single { it.type == "rig.scaffold" } == scaffold)
        val prefix = owners.filterNot { it.id == scaffold.id }
        val eye = prefix.single { it.type == "eye/fitted" }
        val face = prefix.single { it.type == "face/fitted" }
        require(analysis.layers.count { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH } == 1 &&
            analysis.layers.none { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH_OPEN } &&
            analysis.layers.filter { it.semantic.tag in setOf(io.github.psd2live.core.SemanticTag.EYEBROW,
                io.github.psd2live.core.SemanticTag.EYEWHITE, io.github.psd2live.core.SemanticTag.EYELASH) }
                .all { it.semantic.role == null }) {
            "Brow shape v7 requires the fitted v5 whole-mouth cohort"
        }
        val measured = RigBrowSourceMeasurementsV1.capture(this, instance, scaffold, prefix, eye.id, face.id)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", BrowShapeV7Source.SCHEMA)
            put("brow", measured.values.getValue("brow"))
            putJsonObject("provider_packages") {
                for (owner in owners) put(owner.id, Json.encodeToJsonElement(ModifierPluginReference.serializer(), requireNotNull(owner.plugin)))
            }
            put("source_ancestry", measured.values.getValue("source_ancestry"))
            put("providers", JsonArray(owners.map { owner -> Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(),
                ModifierUpstreamCapability(owner.type, owner.type, owner.version, owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin)) }))
            put("head_turn", measured.values.getValue("head_turn"))
            put("native_ids", measured.values.getValue("native_ids"))
            put("mesh_names", measured.values.getValue("mesh_names"))
            put("turn_name", measured.values.getValue("turn_name"))
            put("shape_name", measured.values.getValue("shape_name"))
        }, measured.resources)
    }
}
