package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import kotlinx.serialization.json.*

/** Captures numerical observations, never a generated branch. Native measurement contexts remain host-local. */
internal object RigBrowBlinkV7Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowBlinkV7Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowBlinkV7Algorithm.observed - BrowBlinkV7Algorithm.requires
        override val canonicalOptInType = BrowBlinkV7Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowBlinkV7Algorithm.TYPE), source).copy(
            version = BrowBlinkV7Algorithm.VERSION, plugin = algorithm, requires = BrowBlinkV7Algorithm.requires,
            providers = { source?.providers(it) ?: RigLegacyBindings.providers(it) },
            parameters = { emptyList() }, resolveParameter = { _, _, _ -> null },
            changeWrites = BrowBlinkV7Algorithm.writes, upstreamInputs = true, authorContext = ::capture,
        )
    }

    private fun RigModifierSource.sourcePrefix(instance: RigModifierInstance): List<RigModifierInstance> {
        require(appliedSourceCapabilities.none { it.id == null && it.targets.any(instance.targets::contains) }) {
            "Brow blink v7 requires identifiable applied providers"
        }
        val prefix = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        val scaffold = instance.config.getValue("scaffold")
        require(prefix.map { it.id }.distinct().size == prefix.size && prefix.all { owner ->
            BrowBlinkV7Algorithm.acceptsVersion(owner.type, owner.version) && owner.targets.toSet() == instance.targets.toSet() &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                BundledAlgorithmArtifacts.find(owner.type, owner.version)?.reference?.let { it == owner.plugin } == true
        }) { "Brow blink v7 rejects unknown, foreign, partial or incompatible intersecting source providers" }
        return prefix
    }

    /** Missing required providers pause before any numerical capture. */
    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        val prefix = sourcePrefix(instance)
        val selected = RigLegacyBindings.providers(instance)
        return BrowBlinkV7Algorithm.requiredVersions.mapValues { (type, _) ->
            prefix.singleOrNull { it.type == type && BrowBlinkV7Algorithm.acceptsVersion(type, it.version) && (selected[type] == null || selected[type] == it.id) }?.id
                ?: generateSequence("${instance.id}/missing:$type") { "$it?" }.first { id -> prefix.none { it.id == id } }
        }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val owners = sourcePrefix(instance)
        val capabilities = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        BrowBlinkV7Algorithm.validateProviders(capabilities, instance)
        val scaffold = provider(instance)
        require(owners.single { it.type == "rig.scaffold" } == scaffold)
        val prefix = owners.filterNot { it.id == scaffold.id }
        val eye = prefix.single { it.type == "eye/fitted" }
        val face = prefix.single { it.type == "face/fitted" }
        val shape = prefix.single { it.type == "brow.shape" }
        // Match historical Blink6's actual shape6 source snapshot. Never call shape6 authorContext,
        // which would serialize a generated brow_turn resource. Later shape/lid/blink settings
        // remain solely worker-side options, not numerical measurement inputs.
        // The shape may already be applied, so use its exact position in the active/applied
        // prefix rather than looking it up only among currently editable stack instances.
        val shapePrefix = owners.takeWhile { it.id != shape.id }.filter { owner ->
            owner.config["scaffold"] == JsonPrimitive(scaffold.id) && owner.targets.any(shape.targets::contains) &&
                RigLegacyBindings.find(owner.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val measured = RigBrowSourceMeasurementsV1.capture(this, shape, scaffold, shapePrefix, eye.id, face.id)
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", BrowBlinkV7Source.SCHEMA)
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
