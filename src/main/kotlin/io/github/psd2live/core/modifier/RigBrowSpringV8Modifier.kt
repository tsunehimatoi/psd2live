package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.algorithms.binding.Side
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

/** Captures numerical observations, never a generated branch. Native measurement contexts remain host-local. */
internal object RigBrowSpringV8Modifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by BrowSpringV8Algorithm(),
        ModifierOptionalUpstream, ModifierVersionSelection {
        override val optionalUpstream = BrowSpringV8Algorithm.observed - BrowSpringV8Algorithm.requires
        override val canonicalOptInType = BrowSpringV8Algorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val captures = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun capture(instance: RigModifierInstance) = captures.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return RigLegacyBindings.definition(RigLegacyBindings.spec(BrowSpringV8Algorithm.TYPE), source).copy(
            version = BrowSpringV8Algorithm.VERSION, plugin = algorithm, requires = BrowSpringV8Algorithm.requires,
            providers = { source?.providers(it) ?: RigLegacyBindings.providers(it) },
            parameters = { BrowSpringV8Algorithm.parameters(capture(it)) }, resolveParameter = { _, _, _ -> null },
            changeWrites = BrowSpringV8Algorithm.writes, upstreamInputs = true, authorContext = ::capture,
        )
    }

    private fun RigModifierSource.sourcePrefix(instance: RigModifierInstance): List<RigModifierInstance> {
        require(appliedSourceCapabilities.none { it.id == null && it.targets.any(instance.targets::contains) }) {
            "Brow spring v8 requires identifiable applied providers"
        }
        val prefix = activeBefore(instance).filter { it.targets.any(instance.targets::contains) }
        val scaffold = instance.config.getValue("scaffold")
        require(prefix.map { it.id }.distinct().size == prefix.size && prefix.all { owner ->
            BrowSpringV8Algorithm.acceptsVersion(owner.type, owner.version) && owner.targets.toSet() == instance.targets.toSet() &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.id) == scaffold else owner.config["scaffold"] == scaffold) &&
                BundledAlgorithmArtifacts.find(owner.type, owner.version)?.reference?.let { it == owner.plugin } == true
        }) { "Brow spring v8 rejects unknown, foreign, partial or incompatible intersecting source providers" }
        return prefix
    }

    /** Missing required providers pause before any numerical capture. */
    private fun RigModifierSource.providers(instance: RigModifierInstance): Map<String, String> {
        val prefix = sourcePrefix(instance)
        val selected = RigLegacyBindings.providers(instance)
        return BrowSpringV8Algorithm.requiredVersions.mapValues { (type, _) ->
            prefix.singleOrNull { it.type == type && BrowSpringV8Algorithm.acceptsVersion(type, it.version) && (selected[type] == null || selected[type] == it.id) }?.id
                ?: generateSequence("${instance.id}/missing:$type") { "$it?" }.first { id -> prefix.none { it.id == id } }
        }
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val owners = sourcePrefix(instance)
        val capabilities = owners.map { owner -> ModifierUpstreamCapability(owner.type, owner.type, owner.version,
            owner.targets, owner.id, owner.config, owner.outputAliases, owner.plugin) }
        BrowSpringV8Algorithm.validateProviders(capabilities, instance)
        val scaffold = provider(instance)
        require(owners.single { it.type == "rig.scaffold" } == scaffold)
        val prefix = owners.filterNot { it.id == scaffold.id }
        val eye = prefix.single { it.type == "eye/fitted" }
        val face = prefix.single { it.type == "face/fitted" }
        val measured = RigBrowSourceMeasurementsV1.capture(this, instance, scaffold, prefix, eye.id, face.id)
        val names = listOf(Side.LEFT, Side.RIGHT).zip(RigModules.BROW_SPRING.defines.map { tr(it.nameKey) }).toMap()
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", BrowSpringV8Source.SCHEMA)
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
            put("spring_names", JsonObject(names.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) }))
            put("physics_names", JsonObject(listOf(Side.LEFT to "L", Side.RIGHT to "R").associate { (side, suffix) ->
                side.name to JsonPrimitive(tr("model.physics.browSpring", suffix)) }))
            put("physics_group", tr("model.group.physics"))
        }, measured.resources)
    }
}
