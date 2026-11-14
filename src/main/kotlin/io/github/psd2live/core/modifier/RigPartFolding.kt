package io.github.psd2live.core.modifier

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Non-destructive runtime adapter that folds legacy same-scaffold part+submodule instances
 * (e.g. `eye/fitted` + `eye.wide` + `eye.smile`) into a unified part plugin invocation (`eye` / `brow`)
 * without rewriting saved project files.
 */
internal object RigPartFolding {
    data class FoldedPart(
        val folded: RigModifierInstance,
        val sourceIds: Set<String>,
        val subConfigs: JsonObject,
    )

    private fun flags(part: String) = ModifierPartConfig.features[part].orEmpty().entries.associate { it.value to it.key }
    private val eyeFlags = flags("eye")
    private val browFlags = flags("brow")
    private val faceFlags = flags("face")
    private val bodyFlags = flags("body")
    private val mouthFlags = flags("mouth")

    fun foldEye(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "eye/fitted", legacyType = "eye/legacy", partType = "eye", flagByType = eyeFlags)

    fun foldBrow(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "brow/fitted", legacyType = "brow/legacy", partType = "brow", flagByType = browFlags)

    fun foldFace(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "face/fitted", legacyType = "face/legacy", partType = "face", flagByType = faceFlags,
            matchByDependencies = true)

    fun foldBody(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "body/legacy", legacyType = "body/legacy", partType = "body", flagByType = bodyFlags,
            defaultMode = "legacy", matchByDependencies = true)

    fun foldHair(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "hair/chains", legacyType = "hair/legacy", partType = "hair", flagByType = emptyMap(),
            fittedMode = "chains")

    fun foldMouth(instances: List<RigModifierInstance>, freshInstanceIds: Set<String> = emptySet()): List<FoldedPart> =
        foldArea(instances, freshInstanceIds, fittedType = "mouth/fitted", legacyType = "mouth/legacy", partType = "mouth", flagByType = mouthFlags,
            matchByScaffoldWithoutBase = true)

    /**
     * Folds all eligible legacy part+submodule groups into unified part plugin instances while
     * preserving stack order and leaving groups with external plugin overrides or excluded types untouched.
     */
    fun foldAll(instances: List<RigModifierInstance>, excludedTypes: Set<String> = emptySet(), freshInstanceIds: Set<String> = emptySet()): List<RigModifierInstance> {
        val byId = instances.associateBy { it.id }
        val parts = (foldEye(instances, freshInstanceIds) + foldBrow(instances, freshInstanceIds) + foldMouth(instances, freshInstanceIds) + foldFace(instances, freshInstanceIds) +
            foldBody(instances, freshInstanceIds) + foldHair(instances, freshInstanceIds)).filter { part ->
            part.sourceIds.none { id ->
                val inst = byId[id] ?: return@none false
                val plugin = inst.plugin
                inst.type in excludedTypes || (plugin != null && BuiltinModifierPlugins.entry(plugin.id) == null)
            }
        }
        if (parts.isEmpty()) return instances
        val foldedByBase = parts.associateBy { it.folded.id }
        val dropped = parts.flatMapTo(HashSet()) { it.sourceIds - it.folded.id }
        return instances.mapNotNull { instance ->
            when {
                instance.id in dropped -> null
                instance.id in foldedByBase -> foldedByBase.getValue(instance.id).folded
                else -> instance
            }
        }
    }

    private fun foldArea(
        instances: List<RigModifierInstance>,
        freshInstanceIds: Set<String>,
        fittedType: String,
        legacyType: String,
        partType: String,
        flagByType: Map<String, String>,
        fittedMode: String = "fitted",
        defaultMode: String? = null,
        matchByDependencies: Boolean = false,
        matchByScaffoldWithoutBase: Boolean = false,
    ): List<FoldedPart> {
        // Folding is only for fresh, unpinned presets. Historical algorithm/package identities
        // and authored ownership cannot be silently replaced by the part package.
        if (instances.map { it.id }.distinct().size != instances.size) return emptyList()
        val bases = instances.filter { it.type == fittedType || it.type == legacyType }
        fun references(instance: RigModifierInstance): Set<String> = buildSet {
            (instance.config["base"] as? JsonPrimitive)?.content?.let(::add)
            (instance.config["dependencies"] as? JsonObject)?.values?.forEach { value ->
                (value as? JsonPrimitive)?.content?.let(::add)
            }
            addAll(instance.parameterProviders.values)
        }
        fun eligible(instance: RigModifierInstance) = instance.enabled && (instance.plugin == null || instance.id in freshInstanceIds &&
            instance.plugin == BuiltinModifierPlugins.reference(instance.type, instance.version)) &&
            instance.version == 1 && instance.inputFingerprint == null && instance.parameterProviders.isEmpty()
        return bases.mapNotNull { base ->
            if (!eligible(base)) return@mapNotNull null
            val mode = defaultMode ?: if (base.type == fittedType) fittedMode else "legacy"
            val scaffold = base.config["scaffold"] ?: return@mapNotNull null
            val children = instances.filter { child ->
                child.type in flagByType && child.config["scaffold"] == scaffold &&
                    (child.config["base"]?.jsonPrimitive?.content == base.id ||
                        (matchByDependencies && (child.config["dependencies"] as? JsonObject)?.values?.any { it == JsonPrimitive(base.id) } == true) ||
                        (matchByScaffoldWithoutBase && child.config["base"] == null && child.config["dependencies"] == null &&
                            bases.count { it.config["scaffold"] == scaffold } == 1 && child.targets.all { it in base.targets }))
            }
            if (children.any { !eligible(it) || it.presetGroup != base.presetGroup || !base.targets.containsAll(it.targets) } ||
                children.map { it.type }.distinct().size != children.size) return@mapNotNull null
            // Neither skipped disabled members nor intervening writers/providers may be crossed.
            val start = instances.indexOf(base)
            if (instances.drop(start + 1).take(children.size) != children) return@mapNotNull null
            val order = flagByType.keys.toList()
            if (children.map { order.indexOf(it.type) } != children.map { order.indexOf(it.type) }.sorted()) return@mapNotNull null
            if (mode == "legacy" && partType in setOf("eye", "brow") && children.isNotEmpty()) return@mapNotNull null
            if (mode == "fitted" && children.any { it.type == "face.depth-turn" }) return@mapNotNull null
            val ids = setOf(base.id) + children.map { it.id }
            val removed = ids - base.id
            // The part only exports its base capability. A child referenced outside the group
            // must remain a standalone provider, even when its generated object IDs are retained.
            if (instances.any { it.id !in ids && references(it).any(removed::contains) }) return@mapNotNull null
            val childTypes = children.mapTo(hashSetOf()) { it.type }
            if (childTypes.isNotEmpty() && instances.drop(start + children.size + 1).any { consumer ->
                RigLegacyBindings.find(consumer.type)?.let { spec ->
                    val definition = RigLegacyBindings.definition(spec, null)
                    (definition.requires + definition.optionalUpstream).any(childTypes::contains)
                } == true
            }) return@mapNotNull null
            val available = instances.take(start).filter { it.enabled }.mapTo(hashSetOf()) { it.id }
            available += base.id
            for (child in children) {
                if (!available.containsAll(references(child))) return@mapNotNull null
                val related = RigLegacyBindings.spec(child.type).related
                val dependencies = (child.config["dependencies"] as? JsonObject).orEmpty()
                if (dependencies.keys != related || related.any { capability ->
                    val providerId = dependencies.getValue(capability).jsonPrimitive.content
                    instances.none { provider ->
                        provider.id == providerId && provider.id in available && provider.enabled &&
                            provider.targets.containsAll(child.targets) && RigModifierAlgorithms.registry.provides(provider, capability)
                    }
                }) return@mapNotNull null
                available += child.id
            }
            val subConfigs = buildJsonObject {
                for (child in children) putJsonObject(child.type) {
                    child.config.forEach { (key, value) -> put(key, value) }
                    put("id", child.id)
                    put("version", child.version)
                    put("targets", Json.encodeToJsonElement(child.targets))
                    if (child.outputAliases.isNotEmpty()) put("outputAliases", Json.encodeToJsonElement(child.outputAliases))
                }
            }
            val foldedConfig = buildJsonObject {
                base.config.forEach { (key, value) -> put(key, value) }
                put("mode", mode)
                for (child in children) put(flagByType.getValue(child.type), true)
                if (subConfigs.isNotEmpty()) put("subConfigs", subConfigs)
            }
            FoldedPart(base.copy(type = partType, version = 1, config = foldedConfig,
                plugin = base.plugin?.let { BuiltinModifierPlugins.reference(partType, 1) }), ids, subConfigs)
        }
    }
}
