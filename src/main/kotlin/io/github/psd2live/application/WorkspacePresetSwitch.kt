package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/** Whole replacement is prepared inside the existing document candidate/CAS transaction. */
internal object WorkspacePresetSwitch {
    fun apply(document: WorkspaceDocument, id: String, work: WorkspaceRasterWork): WorkspaceDocument {
        require(id in WorkspacePresetBaseline.ids) { "Unknown preset baseline: $id" }
        val previous = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        require(previous.overrides.values.all { it.isEmpty() }) {
            "Preset content has authored overrides. Apply the affected presets before switching the complete preset."
        }
        val cleared = previous.instances.fold(previous) { stack, entry -> stack.remove(entry.id) }
        if (id == "empty") return document.copy(rigEdits = document.rigEdits.copy(modifierStack = cleared.toJson()))
        require(previous.appliedCapabilities.isEmpty()) { "Applied preset content already exists; switch presets before applying them" }
        val config = WorkspacePresetBaseline.configure(document.config(), id)
        var candidate = document.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = document.rigEdits.copy(modifierStack = null))
        val pipeline = PSD2LivePipeline()
        fun build() = pipeline.buildPreview(candidate.source, candidate.config(), ProgressListener { _, _ -> work.checkpoint() })
        work.progress(0f, "Building preset baseline: $id")
        var model = build()
        if (model.analysis.layers.any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }) {
            val observer = object : WorkspaceSimulationWork {
                override fun <T> run(id: String, action: ((Float) -> Unit, () -> Boolean) -> T): T =
                    action({ work.progress(it, "Baking clothing: $id") }, { work.checkpoint(); false })
            }
            candidate = WorkspaceSimulationEdits.preset(candidate, model, ModelPresets.Preset.CLOTHING, emptySet(), observer, allowNoLooseClothing = true).document
            model = build()
        }
        candidate = WorkspaceModifierConversion.convert(candidate, model, work)
        val converted = RigModifierStack.fromJson(requireNotNull(candidate.rigEdits.modifierStack))
        var result = cleared
        val identities = LinkedHashMap<String, String>()
        for (entry in converted.instances) {
            result = result.add(entry.type, entry.version, entry.targets, entry.config, id, outputAliases = entry.outputAliases)
            identities[entry.id] = result.instances.last().id
        }
        fun remap(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { remap(it.value) })
            is JsonArray -> JsonArray(value.map(::remap))
            is JsonPrimitive -> if (value.isString && value.content in identities) JsonPrimitive(identities.getValue(value.content)) else value
        }
        result = result.copy(instances = result.instances.zip(converted.instances).map { (fresh, original) ->
            fresh.copy(config = remap(original.config).jsonObject, enabled = original.enabled,
                inputFingerprint = original.inputFingerprint,
                parameterProviders = original.parameterProviders.mapValues { identities[it.value] ?: it.value })
        })
        return candidate.copy(rigEdits = candidate.rigEdits.copy(modifierStack = result.toJson()))
    }
}
