package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/** Earlier journal entries always replay against the generation rules under which they were authored. */
internal object RigGenerationBaseline {
    const val OP = "rig_generation_baseline"
    private val fields = setOf("meshOnly", "generateDeformers", "featureDisplacementEnabled", "mouthOutlineEnabled",
        "mouthShape", "mouthCurve", "mouthColor", "mouthThickness", "headStrength", "bodyStrength", "rigTuning")

    fun present(overlay: RigEditOverlay) = overlay.authoringJournal.any { it["op"]?.jsonPrimitive?.contentOrNull == OP }

    fun preserve(overlay: RigEditOverlay, config: PipelineConfig): RigEditOverlay {
        if (present(overlay)) return overlay
        val marker = buildJsonObject {
            put("op", OP)
            put("settings", JsonObject(WorkspaceSettingsCodec.encode(config).filterKeys { it in fields }))
            put("classifications", buildJsonObject { config.layerOverrides.forEach { (id, classification) ->
                put(id, buildJsonObject {
                    put("type", classification.type.name); put("tag", classification.tag.name)
                    put("side", classification.side.name); put("parameter", classification.parameter); put("switch", classification.switchId)
                })
            } })
            put("parents", buildJsonObject { config.parentOverrides.forEach { (id, parent) -> put(id, parent?.let(::JsonPrimitive) ?: JsonNull) } })
        }
        return overlay.copy(authoringJournal = overlay.authoringJournal + marker)
    }

    /**
     * [config] with the generation rules its journal's baseline froze, while entries after it still replay on the base:
     * once a checkpoint follows the baseline those entries are history, and the document's own rules generate.
     */
    fun restore(config: PipelineConfig): PipelineConfig {
        val journal = config.rigEdits.authoringJournal
        val markers = journal.filter { it["op"]?.jsonPrimitive?.contentOrNull == OP }
        if (markers.isEmpty() || journal.indexOfLast { it["op"]?.jsonPrimitive?.contentOrNull == OP } < config.rigEdits.checkpointIndex) return config
        require(markers.size == 1) { "Duplicate rig generation baseline" }
        val command = markers.single()
        validate(command)
        val classifications = command.getValue("classifications").jsonObject.mapValues { (_, element) ->
            val value = element.jsonObject
            LayerClassificationOverride(LayerType.valueOf(value.getValue("type").jsonPrimitive.content),
                SemanticTag.valueOf(value.getValue("tag").jsonPrimitive.content), Side.valueOf(value.getValue("side").jsonPrimitive.content),
                value.getValue("parameter").jsonPrimitive.content, value.getValue("switch").jsonPrimitive.int)
        }
        return WorkspaceSettingsCodec.decode(command.getValue("settings").jsonObject, config).copy(layerOverrides = classifications,
            parentOverrides = command.getValue("parents").jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull })
    }

    private fun validate(command: JsonObject) {
        require(command.keys == setOf("op", "settings", "classifications", "parents")) { "Invalid rig generation baseline" }
        require(command.getValue("settings").jsonObject.keys == fields) { "Invalid rig generation baseline settings" }
        command.getValue("classifications").jsonObject.values.forEach {
            require(it.jsonObject.keys == setOf("type", "tag", "side", "parameter", "switch")) { "Invalid generation classification" }
            require(it.jsonObject.getValue("switch").jsonPrimitive.int >= 0) { "Invalid generation switch" }
        }
        command.getValue("parents").jsonObject
    }

    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        validate(command)
        return model
    }
}
