package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierParameterGroups
import io.github.psd2live.core.modifier.ModifierParameterI18n
import io.github.psd2live.core.modifier.ModifierRasterOutput
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Unified mouth part plugin (`mode: fitted | legacy`) combining base mouth fitting/binding and
 * optional mouth sub-features (`outline`, `vowel`, `capture`, `tongue`, `jaw`, `cheekLid`,
 * `depth`, `spring`) in one single pass.
 */
class MouthPartAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        val mode = (context.values["mode"]?.jsonPrimitive?.content
            ?: instance.config["mode"]?.jsonPrimitive?.content
            ?: "fitted")
        require(mode == "fitted" || mode == "legacy") { "Unsupported mouth mode: $mode" }

        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val baseType = if (mode == "fitted") "mouth/fitted" else MouthLegacyAlgorithm.TYPE
        val baseVersion = if (mode == "fitted") 2 else MouthLegacyAlgorithm.VERSION
        val baseConfig = buildJsonObject {
            put("scaffold", scaffold)
            instance.config["tuning"]?.let { put("tuning", it) }
            instance.config["values"]?.let { put("values", it) }
        }
        val baseInstance = RigModifierInstance(
            id = instance.id,
            type = baseType,
            version = baseVersion,
            targets = instance.targets,
            config = baseConfig,
            outputAliases = instance.outputAliases,
            plugin = instance.plugin,
        )
        val baseValues = context.values["base_context"]?.jsonObject ?: context.values
        val upstream = context.upstream.toMutableList()
        val baseContext = ModifierAlgorithmContext(baseValues, context.resources.filterKeys { !it.startsWith("sub:") }, upstream)

        var current = if (mode == "fitted") {
            MouthFittedAlgorithm(upgradeToSource = true).changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint).let { fittedRig ->
                val i18n = ModifierParameterI18n.fromContext(context)
                val formName = i18n.resolveName("ParamMouthForm", "Mouth Form")
                val openName = i18n.resolveName("ParamMouthOpenY", "Mouth Open")
                val groupName = i18n.resolveGroup(ModifierParameterGroups.MOUTH).let { if (it == ModifierParameterGroups.MOUTH) "Mouth" else it }
                val missing = listOfNotNull(
                    Parameter("ParamMouthForm", formName, -1f, 1f, 0f).takeIf { p -> fittedRig.parameters.none { it.id == p.id } },
                    Parameter("ParamMouthOpenY", openName, 0f, 1f, 0f).takeIf { p -> fittedRig.parameters.none { it.id == p.id } },
                )
                if (missing.isEmpty()) fittedRig else {
                    val puppet = PuppetIr.toPuppet(fittedRig)
                    val folder = PuppetParameterNode.Group(
                        ParameterGroupId(ModifierParameterGroups.MOUTH),
                        groupName,
                        true,
                        missing.map { PuppetParameterNode.Param(ParameterId(it.id)) },
                    )
                    val updatedTree = BindingMerge.appendTree(puppet, listOf(folder), missing.mapTo(hashSetOf()) { ParameterId(it.id) }, false)
                    val convertedTree = PuppetIr.toIr(puppet.copy(parameterTree = updatedTree)).parameterTree
                    fittedRig.copy(parameters = fittedRig.parameters + missing, parameterTree = convertedTree)
                }
            }
        } else {
            MouthLegacyAlgorithm().changes(input, baseInstance, baseContext, checkpoint).apply(input, checkpoint)
        }

        upstream += ModifierUpstreamCapability(
            capability = baseType,
            type = baseType,
            version = baseVersion,
            targets = baseInstance.targets,
            instanceId = baseInstance.id,
            config = baseConfig,
        )

        val subContexts = context.values["sub_contexts"]?.jsonObject
        val subConfigs = context.values["sub_configs"]?.jsonObject
        val rasters = mutableListOf<ModifierRasterOutput>()

        for (feature in subFeatures) {
            if (instance.config[feature.configFlag]?.jsonPrimitive?.booleanOrNull != true) continue
            val subContextObj = subContexts?.get(feature.type)?.jsonObject ?: continue
            val subCfgObj = subConfigs?.get(feature.type)?.jsonObject
            val subTargets = subCfgObj?.get("targets")?.jsonArray?.let {
                Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ModifierTarget.serializer()), it)
            } ?: instance.targets
            val subConfig = buildJsonObject {
                put("scaffold", scaffold)
                put("base", instance.id)
                subCfgObj?.get("values")?.let { put("values", it) }
                subCfgObj?.get("tuning")?.let { put("tuning", it) }
                subCfgObj?.get("color")?.let { put("color", it) }
                subCfgObj?.get("physics")?.let { put("physics", it) }
                subCfgObj?.get("physicsEnabled")?.let { put("physicsEnabled", it) }
                val deps = subCfgObj?.get("dependencies")?.jsonObject ?: instance.config["dependencies"]?.jsonObject ?: JsonObject(emptyMap())
                val mergedDeps = buildJsonObject {
                    deps.forEach { (k, v) -> put(k, v) }
                    if (feature.type == MouthTongueAlgorithm.TYPE) {
                        upstream.lastOrNull { it.capability == MouthCaptureAlgorithm.TYPE }?.instanceId?.let { put("mouth.capture", it) }
                    }
                    if (feature.type == MouthSpringAlgorithm.TYPE) {
                        upstream.lastOrNull { it.capability == MouthJawAlgorithm.TYPE }?.instanceId?.let { put("mouth.jaw", it) }
                    }
                    if (feature.type == MouthDepthAlgorithm.TYPE && "face/legacy" !in deps) {
                        upstream.lastOrNull { it.capability == "face/legacy" }?.instanceId?.let { put("face/legacy", it) }
                    }
                }
                if (mergedDeps.isNotEmpty()) put("dependencies", mergedDeps)
            }
            val subInstance = RigModifierInstance(
                id = "${instance.id}:${feature.configFlag}",
                type = feature.type,
                version = feature.version,
                targets = subTargets,
                config = subConfig,
                outputAliases = instance.outputAliases,
                plugin = instance.plugin,
            )
            val subPrefix = "sub:${feature.type}:"
            val subResources = context.resources
                .filterKeys { it.startsWith(subPrefix) }
                .mapKeys { it.key.removePrefix(subPrefix) }
            val subCtx = ModifierAlgorithmContext(subContextObj, subResources, upstream)
            val stepChanges = when (val plugin = feature.plugin) {
                is ReadingModifierAlgorithmPlugin -> plugin.changes(ModifierEvaluationInput(current, subCtx), subInstance, checkpoint)
                is ChangingModifierAlgorithmPlugin -> plugin.changes(current, subInstance, subCtx, checkpoint)
                else -> error("Unsupported mouth sub-feature plugin: ${feature.type}")
            }
            rasters += stepChanges.rasters
            current = stepChanges.copy(rasters = emptyList()).apply(current, checkpoint)
            upstream += ModifierUpstreamCapability(
                capability = feature.type,
                type = feature.type,
                version = feature.version,
                targets = subTargets,
                instanceId = subInstance.id,
                config = subConfig,
            )
        }

        val resolvedRasters = rasters.map { raster ->
            val finalOwner = current.meshes.firstOrNull { it.id == raster.owner.id }
            if (finalOwner != null && finalOwner != raster.owner) {
                ModifierRasterOutput(finalOwner, raster.mesh, raster.layer, raster.pixels, raster.paths, raster.uvSpace)
            } else raster
        }
        return PartChangeComposer.between(input, current, checkpoint).copy(rasters = resolvedRasters)
    }

    private data class SubFeature(
        val configFlag: String,
        val type: String,
        val version: Int,
        val plugin: ModifierAlgorithmPlugin,
    )

    companion object {
        const val TYPE = "mouth"
        const val ENTRYPOINT = "part"
        const val VERSION = 1

        private val subFeatures: List<SubFeature> = listOf(
            SubFeature("outline", "mouth.outline", 1, MouthOutlineAlgorithm()),
            SubFeature("vowel", MouthVowelAlgorithm.TYPE, MouthVowelAlgorithm.VERSION, MouthVowelAlgorithm()),
            SubFeature("capture", MouthCaptureAlgorithm.TYPE, MouthCaptureAlgorithm.VERSION, MouthCaptureAlgorithm()),
            SubFeature("tongue", MouthTongueAlgorithm.TYPE, MouthTongueAlgorithm.VERSION, MouthTongueAlgorithm()),
            SubFeature("jaw", MouthJawAlgorithm.TYPE, MouthJawAlgorithm.VERSION, MouthJawAlgorithm()),
            SubFeature("cheekLid", MouthCheekLidAlgorithm.TYPE, MouthCheekLidAlgorithm.VERSION, MouthCheekLidAlgorithm()),
            SubFeature("depth", MouthDepthAlgorithm.TYPE, MouthDepthAlgorithm.VERSION, MouthDepthAlgorithm()),
            SubFeature("spring", MouthSpringAlgorithm.TYPE, MouthSpringAlgorithm.VERSION, MouthSpringAlgorithm()),
        )
    }
}
