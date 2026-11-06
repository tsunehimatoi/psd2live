package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/** Whole replacement is prepared inside the existing document candidate/CAS transaction. */
internal object WorkspacePresetSwitch {
    fun apply(document: WorkspaceDocument, id: String, work: WorkspaceRasterWork, model: RigPreviewModel? = null): WorkspaceDocument {
        val preset = WorkspaceModelPresetLibrary.shared.get(id)
        val previous = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        require(previous.overrides.values.all { it.isEmpty() }) {
            "Preset content has authored overrides. Apply the affected presets before switching the complete preset."
        }
        val cleared = previous.instances.fold(previous) { stack, entry -> stack.remove(entry.id) }.copy(organization = null)
        if (preset.baseline == "empty") return document.copy(rigEdits = document.rigEdits.copy(modifierStack = cleared.toJson()))
        require(previous.appliedCapabilities.isEmpty()) { "Applied preset content already exists; switch presets before applying them" }
        if (preset.stack != null) {
            val next = instantiate(preset, cleared, requireNotNull(model))
            val candidate = document.copy(rigEdits = document.rigEdits.copy(modifierStack = next.toJson()))
            return WorkspaceModifierSimulation.prepare(candidate, work)
        }
        val config = WorkspacePresetBaseline.configure(document.config(), id)
        val candidate = document.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = document.rigEdits.copy(modifierStack = cleared.toJson()))
        val initialized = WorkspacePresetInitialization.create(candidate, work, preset.choices)
        val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        return initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = stack.copy(
            instances = stack.instances.map { it.copy(presetGroup = id) }).toJson()))
    }

    /** Resolve only exact references or unique names; ambiguous or missing targets never silently widen scope. */
    internal fun instantiate(preset: WorkspaceModelPresetEntry, cleared: RigModifierStack, model: RigPreviewModel): RigModifierStack {
        val template = RigModifierStack.fromJson(requireNotNull(preset.stack))
        val puppet = model.authored.rig.puppet
        val meshNames = puppet.drawables.associate { it.id.raw to it.name }
        val deformerNames = puppet.deformers.associate { it.id.raw to it.name }
        val partNames = puppet.parts.associate { it.id.raw to it.name }
        val layerNames = model.analysis.source.layers.associate { it.id.raw to it.name }
        val targetIds = mutableMapOf<String, String>()
        val ids = mutableMapOf<String, String>()
        var reserved = cleared
        for (entry in template.instances) {
            reserved = reserved.add(entry.type, entry.version, entry.targets, entry.config, preset.id)
            ids[entry.id] = reserved.instances.last().id
        }
        val aliases = template.instances.flatMap { entry -> entry.outputAliases.map { (local, old) ->
            old to entry.copy(id = ids.getValue(entry.id), outputAliases = emptyMap()).outputId(local)
        } }.toMap()
        val generatedOwner = Regex("modifier:(" + ids.keys.joinToString("|") { Regex.escape(it) } + "):")
        fun generated(value: String): String {
            aliases[value]?.let { return it }
            return generatedOwner.replace(value) { match -> "modifier:${ids.getValue(match.groupValues[1])}:" }
        }
        for (entry in template.instances) for (target in entry.targets) {
            if (generated(target.id) != target.id) continue
            val candidates = when (target.kind) {
                "mesh" -> meshNames
                "deformer" -> deformerNames
                "part" -> partNames
                "layer" -> layerNames
                else -> emptyMap()
            }
            if (target.id in candidates) targetIds[target.id] = target.id
            else {
                val name = preset.targetNames[target.id]
                val matches = candidates.filterValues { name != null && it == name }.keys
                require(matches.size == 1) { "Preset target cannot be resolved uniquely: ${name ?: target.id} (${target.kind})" }
                targetIds[target.id] = matches.single()
            }
        }
        fun remap(value: String) = targetIds[value] ?: generated(value)
        fun json(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.map { (key, child) -> remap(key) to json(child) }.toMap())
            is JsonArray -> JsonArray(value.map(::json))
            is JsonPrimitive -> if (value.isString) JsonPrimitive(remap(value.content)) else value
        }
        fun provider(id: String): String = requireNotNull(ids[id]) { "Preset depends on an external modifier: $id" }
        fun configuration(config: JsonObject): JsonObject {
            val remapped = json(config).jsonObject.toMutableMap()
            for (key in listOf("scaffold", "base")) config[key]?.jsonPrimitive?.content?.let { remapped[key] = JsonPrimitive(provider(it)) }
            config["dependencies"]?.jsonObject?.let { dependencies ->
                remapped["dependencies"] = JsonObject(dependencies.mapValues { JsonPrimitive(provider(it.value.jsonPrimitive.content)) })
            }
            return JsonObject(remapped)
        }
        val instances = template.instances.map { entry -> entry.copy(
            plugin = entry.plugin ?: BuiltinModifierPlugins.reference(entry.type, entry.version),
            id = ids.getValue(entry.id), presetGroup = preset.id, inputFingerprint = null, outputAliases = emptyMap(),
            targets = entry.targets.map { it.copy(id = remap(it.id)) }, config = configuration(entry.config),
            parameterProviders = entry.parameterProviders.map { (parameter, sourceProvider) -> remap(parameter) to provider(sourceProvider) }.toMap(),
        ) }
        val tree = template.organization?.map { node -> node.copy(id = ids[node.id] ?: node.id) }
        return reserved.copy(instances = instances, organization = tree).also { stack ->
            RigModifierAlgorithms.evaluator().validateOrder(stack)
            RigModifierOrganization.validate(stack, RigModifierOrganization.nodes(stack))
        }
    }
}
